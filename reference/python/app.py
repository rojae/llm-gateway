"""v0 gateway: a transparent, instrumented proxy in front of one upstream.

Deliberately *not* in v0: routing, caching, quotas, retries, fallback.
The only job is to sit in the request path without anybody noticing, and to
write down what happened.

Three things here are easy to get wrong and are the actual reason this file
exists:

* **Streaming must not be buffered.** Chunks are forwarded raw, the moment they
  arrive. Nothing is re-serialised.
* **Cancellation must propagate.** When the caller hangs up, the upstream
  request has to die too — otherwise the GPU keeps working and you keep paying
  for output nobody will read.
* **The upstream status must be known before the response starts.** We open the
  upstream stream first, so a 429 stays a 429 instead of becoming a 200 with an
  error buried in the body.
"""

from __future__ import annotations

import json
import time
import uuid
from contextlib import asynccontextmanager, suppress
from typing import Any, AsyncIterator

import httpx
from fastapi import APIRouter, FastAPI, Request, Response
from fastapi.responses import JSONResponse, StreamingResponse

from .config import settings
from .observability import RequestRecord, Usage, UsageSniffer

# Headers that describe a single hop and must never be forwarded.
HOP_BY_HOP = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
    "host",
    "content-length",
}

router = APIRouter()


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    app.state.client = httpx.AsyncClient(
        timeout=httpx.Timeout(
            connect=settings.connect_timeout,
            read=settings.read_timeout,
            write=30.0,
            pool=settings.connect_timeout,
        ),
        limits=httpx.Limits(max_connections=1000, max_keepalive_connections=100),
        follow_redirects=False,
    )
    try:
        yield
    finally:
        await app.state.client.aclose()


app = FastAPI(title="LLM Gateway", version="0.1.0", lifespan=lifespan)


def forward_headers(request: Request) -> dict[str, str]:
    """Copy the caller's headers, minus hop-by-hop ones.

    Provider-specific headers (`anthropic-version`, `OpenAI-Beta`, ...) are
    preserved deliberately: dropping them breaks features silently.
    """
    headers = {
        key: value
        for key, value in request.headers.items()
        if key.lower() not in HOP_BY_HOP
    }
    if settings.upstream_api_key:
        # Managed-key mode: the gateway owns the credential.
        headers["authorization"] = f"Bearer {settings.upstream_api_key}"
    # Otherwise we are in passthrough mode and the caller's own key rides along.
    return headers


def upstream_url(path: str) -> str:
    return f"{settings.upstream_base_url}/{path.lstrip('/')}"


def response_headers(record: RequestRecord, streaming: bool) -> dict[str, str]:
    headers = {"x-gw-request-id": record.request_id}
    if streaming:
        # Response headers are flushed before the first token, so cost and token
        # counts cannot appear here — they are only known at the end of the
        # stream. They go to the access log instead. This is not an oversight.
        headers["cache-control"] = "no-cache"
        headers["x-accel-buffering"] = "no"
    return headers


@router.get("/healthz")
async def healthz() -> dict[str, Any]:
    return {
        "status": "ok",
        "upstream": settings.upstream_base_url,
        "key_mode": "managed" if settings.upstream_api_key else "passthrough",
    }


@router.post("/v1/chat/completions")
async def chat_completions(request: Request) -> Response:
    client: httpx.AsyncClient = request.app.state.client
    record = RequestRecord(
        request_id=uuid.uuid4().hex, path="/v1/chat/completions"
    )

    raw_body = await request.body()
    try:
        payload = json.loads(raw_body) if raw_body else {}
    except ValueError:
        return JSONResponse(
            {"error": {"message": "request body is not valid JSON"}}, status_code=400
        )

    record.model = payload.get("model") if isinstance(payload, dict) else None
    streaming = bool(isinstance(payload, dict) and payload.get("stream"))
    record.stream = streaming

    if streaming and settings.inject_stream_usage and isinstance(payload, dict):
        # Without this the upstream never reports token counts for a streamed
        # response, and every streamed request becomes unaccountable.
        options = payload.get("stream_options")
        if not isinstance(options, dict):
            options = {}
        options.setdefault("include_usage", True)
        payload["stream_options"] = options
        raw_body = json.dumps(payload).encode()

    headers = forward_headers(request)
    headers["content-type"] = "application/json"
    url = upstream_url("chat/completions")

    if not streaming:
        return await _proxy_unary(client, record, url, raw_body, headers)
    return await _proxy_stream(client, record, url, raw_body, headers)


async def _proxy_unary(
    client: httpx.AsyncClient,
    record: RequestRecord,
    url: str,
    body: bytes,
    headers: dict[str, str],
) -> Response:
    try:
        upstream = await client.post(url, content=body, headers=headers)
    except httpx.HTTPError as exc:
        record.upstream_error = f"{type(exc).__name__}: {exc}"
        record.status = 502
        record.finish()
        record.emit()
        return JSONResponse(
            {"error": {"message": "upstream request failed", "type": "bad_gateway"}},
            status_code=502,
            headers={"x-gw-request-id": record.request_id},
        )

    record.status = upstream.status_code
    usage: Usage | None = None
    with suppress(ValueError):
        parsed = upstream.json()
        if isinstance(parsed, dict):
            usage = Usage.from_payload(parsed)
            if isinstance(parsed.get("model"), str):
                record.model = parsed["model"]
    record.finish(usage)
    record.emit()

    out = response_headers(record, streaming=False)
    if record.model:
        out["x-gw-model"] = record.model
    if record.cost_usd is not None:
        out["x-gw-cost-usd"] = f"{record.cost_usd:.6f}"
    if record.total_ms is not None:
        out["x-gw-upstream-ms"] = f"{record.total_ms:.2f}"

    passthrough = {
        key: value
        for key, value in upstream.headers.items()
        if key.lower() not in HOP_BY_HOP and not key.lower().startswith("x-gw-")
    }
    passthrough.update(out)
    return Response(
        content=upstream.content,
        status_code=upstream.status_code,
        headers=passthrough,
        media_type=upstream.headers.get("content-type"),
    )


async def _proxy_stream(
    client: httpx.AsyncClient,
    record: RequestRecord,
    url: str,
    body: bytes,
    headers: dict[str, str],
) -> Response:
    request = client.build_request("POST", url, content=body, headers=headers)
    try:
        # `stream=True` returns as soon as the response headers arrive, so the
        # status code is known before we commit to a 200 streaming response.
        upstream = await client.send(request, stream=True)
    except httpx.HTTPError as exc:
        record.upstream_error = f"{type(exc).__name__}: {exc}"
        record.status = 502
        record.finish()
        record.emit()
        return JSONResponse(
            {"error": {"message": "upstream request failed", "type": "bad_gateway"}},
            status_code=502,
            headers={"x-gw-request-id": record.request_id},
        )

    record.status = upstream.status_code

    if upstream.status_code >= 400:
        # An error is not a stream. Read it, close, and return it as-is.
        error_body = await upstream.aread()
        await upstream.aclose()
        record.finish()
        record.emit()
        return Response(
            content=error_body,
            status_code=upstream.status_code,
            headers={"x-gw-request-id": record.request_id},
            media_type=upstream.headers.get("content-type"),
        )

    started = time.monotonic()

    async def body_iter() -> AsyncIterator[bytes]:
        sniffer = UsageSniffer()
        completed = False
        try:
            async for chunk in upstream.aiter_raw():
                if record.ttft_ms is None:
                    record.ttft_ms = round((time.monotonic() - started) * 1000, 2)
                yield chunk
                sniffer.feed(chunk)
            completed = True
        finally:
            # Reached on normal completion AND on cancellation (client hang-up).
            # Closing the httpx response tears down the upstream connection,
            # which is what actually stops the upstream from generating.
            record.client_disconnected = not completed
            with suppress(Exception):
                await upstream.aclose()
            if sniffer.model and not record.model:
                record.model = sniffer.model
            record.finish(sniffer.usage)
            record.emit()

    return StreamingResponse(
        body_iter(),
        status_code=upstream.status_code,
        media_type=upstream.headers.get("content-type", "text/event-stream"),
        headers=response_headers(record, streaming=True),
    )


@router.get("/v1/{path:path}")
async def passthrough_get(path: str, request: Request) -> Response:
    """Plain GET passthrough (`/v1/models`, etc.). No body, no streaming."""
    client: httpx.AsyncClient = request.app.state.client
    record = RequestRecord(request_id=uuid.uuid4().hex, path=f"/v1/{path}")
    try:
        upstream = await client.get(
            upstream_url(path),
            headers=forward_headers(request),
            params=dict(request.query_params),
        )
    except httpx.HTTPError as exc:
        record.upstream_error = f"{type(exc).__name__}: {exc}"
        record.status = 502
        record.finish()
        record.emit()
        return JSONResponse(
            {"error": {"message": "upstream request failed"}},
            status_code=502,
            headers={"x-gw-request-id": record.request_id},
        )
    record.status = upstream.status_code
    record.finish()
    record.emit()
    return Response(
        content=upstream.content,
        status_code=upstream.status_code,
        headers={"x-gw-request-id": record.request_id},
        media_type=upstream.headers.get("content-type"),
    )


app.include_router(router)
