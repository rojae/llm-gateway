"""Gateway configuration, read once from the environment at import time."""

from __future__ import annotations

import os
from dataclasses import dataclass, field


def _env_bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _env_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    return float(raw) if raw else default


@dataclass(frozen=True)
class Settings:
    # Single upstream. v0 does no routing: every request goes here.
    upstream_base_url: str = field(
        default_factory=lambda: os.environ.get(
            "GW_UPSTREAM_BASE_URL", "https://api.openai.com/v1"
        ).rstrip("/")
    )

    # When set, the gateway injects this key and ignores whatever the caller sent.
    # When unset, the caller's Authorization header is passed through untouched
    # ("passthrough mode") — this is what makes onboarding a one-line env change.
    upstream_api_key: str | None = field(
        default_factory=lambda: os.environ.get("GW_UPSTREAM_API_KEY") or None
    )

    # Connect fast, but allow a long gap between streamed chunks.
    connect_timeout: float = field(
        default_factory=lambda: _env_float("GW_CONNECT_TIMEOUT", 5.0)
    )
    read_timeout: float = field(
        default_factory=lambda: _env_float("GW_READ_TIMEOUT", 120.0)
    )

    # Ask the upstream to emit a final usage chunk on streaming responses.
    # Without this, streamed requests have no token counts at all.
    inject_stream_usage: bool = field(
        default_factory=lambda: _env_bool("GW_INJECT_STREAM_USAGE", True)
    )

    # The gateway never retries. The SDK already retries twice by default;
    # retrying here too would multiply cost. See docs/architecture.md.
    log_file: str | None = field(
        default_factory=lambda: os.environ.get("GW_LOG_FILE") or None
    )


settings = Settings()


# Cost per 1M tokens, USD. Unknown models simply report a null cost rather than
# a wrong one — a silently wrong number is worse than a missing one.
PRICING: dict[str, tuple[float, float]] = {
    "gpt-4o": (2.50, 10.00),
    "gpt-4o-mini": (0.15, 0.60),
    "claude-sonnet-4": (3.00, 15.00),
    "claude-haiku-4-5": (1.00, 5.00),
}


def estimate_cost(model: str | None, prompt_tokens: int, completion_tokens: int) -> float | None:
    if not model:
        return None
    price = PRICING.get(model)
    if price is None:
        # Try a prefix match so "gpt-4o-mini-2024-07-18" still resolves.
        for known, candidate in PRICING.items():
            if model.startswith(known):
                price = candidate
                break
    if price is None:
        return None
    prompt_price, completion_price = price
    return round(
        (prompt_tokens * prompt_price + completion_tokens * completion_price) / 1_000_000,
        6,
    )
