"""Access logging and usage extraction.

Two jobs live here:

1. `UsageSniffer` reads token counts out of a streamed SSE body *without*
   altering the bytes the client receives. The proxy forwards raw chunks
   verbatim and hands a copy to the sniffer.
2. `access_log` writes one JSON object per request. This is the whole point of
   v0: you cannot optimise what you cannot see.
"""

from __future__ import annotations

import json
import sys
import time
from dataclasses import asdict, dataclass, field
from typing import Any

from .config import estimate_cost, settings


@dataclass
class Usage:
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "Usage | None":
        raw = payload.get("usage")
        if not isinstance(raw, dict):
            return None
        return cls(
            prompt_tokens=int(raw.get("prompt_tokens") or 0),
            completion_tokens=int(raw.get("completion_tokens") or 0),
            total_tokens=int(raw.get("total_tokens") or 0),
        )


class UsageSniffer:
    """Extracts the final `usage` object from an SSE stream.

    OpenAI sends usage in the last data frame before `[DONE]`, and only when
    `stream_options.include_usage` was requested. We scan for the literal
    b'"usage"' first so the common chunk costs one substring check.
    """

    def __init__(self) -> None:
        self._buffer = bytearray()
        self.usage: Usage | None = None
        self.model: str | None = None

    def feed(self, chunk: bytes) -> None:
        if self.usage is not None and self.model is not None:
            return
        self._buffer.extend(chunk)
        # Keep only whole SSE events; leave a partial tail for the next chunk.
        while b"\n\n" in self._buffer:
            event, _, rest = bytes(self._buffer).partition(b"\n\n")
            self._buffer = bytearray(rest)
            self._consume_event(event)

    def _consume_event(self, event: bytes) -> None:
        for line in event.split(b"\n"):
            if not line.startswith(b"data:"):
                continue
            payload = line[len(b"data:") :].strip()
            if not payload or payload == b"[DONE]":
                continue
            try:
                obj = json.loads(payload)
            except (ValueError, UnicodeDecodeError):
                continue
            if not isinstance(obj, dict):
                continue
            if self.model is None and isinstance(obj.get("model"), str):
                self.model = obj["model"]
            found = Usage.from_payload(obj)
            if found is not None:
                self.usage = found


@dataclass
class RequestRecord:
    """One line of the access log."""

    request_id: str
    path: str
    model: str | None = None
    stream: bool = False
    status: int | None = None
    ttft_ms: float | None = None
    total_ms: float | None = None
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    cost_usd: float | None = None
    client_disconnected: bool = False
    upstream_error: str | None = None
    started_at: float = field(default_factory=time.monotonic)

    def finish(self, usage: Usage | None = None) -> None:
        self.total_ms = round((time.monotonic() - self.started_at) * 1000, 2)
        if usage is not None:
            self.prompt_tokens = usage.prompt_tokens
            self.completion_tokens = usage.completion_tokens
            self.cost_usd = estimate_cost(
                self.model, usage.prompt_tokens, usage.completion_tokens
            )

    def emit(self) -> None:
        payload = asdict(self)
        payload.pop("started_at", None)
        payload["ts"] = time.time()
        line = json.dumps(payload, ensure_ascii=False)
        print(line, file=sys.stdout, flush=True)
        if settings.log_file:
            with open(settings.log_file, "a", encoding="utf-8") as handle:
                handle.write(line + "\n")
