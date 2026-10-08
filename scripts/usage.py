#!/usr/bin/env python3
"""접근 로그(JSONL)를 집계해 "무엇에 얼마 썼는지"를 보여준다.

게이트웨이는 요청당 JSON 한 줄을 남긴다. 이 스크립트는 그 줄을 원하는 축으로 접어서
토큰과 비용을 뽑는다. 집계되지 않는 로그는 없는 것과 같으므로,
로그를 파일로 남기는 것과 이 스크립트는 한 쌍이다.

  # 접근 로그는 기본적으로 var/access.log 에 자동 저장된다.
  ./scripts/usage.py var/access.log
  ./scripts/usage.py var/access.log --by day,model
  ./scripts/usage.py var/access.log --by session      # Claude Code 세션별
  ./scripts/usage.py var/access.log --by class        # main / subagent / compaction ...
  ./scripts/usage.py var/access.log --by agent        # Explore / Plan / general-purpose ...
  ./scripts/usage.py var/access.log --budget 20       # 월 예산 대비 소진율
  ./scripts/usage.py var/access.log var/access.log.*.gz --by provider,outcome --traffic live

접근 로그가 아닌 줄(부팅 로그 등)은 조용히 건너뛴다 — 같은 stdout 에 섞여 나오기 때문이다.
"""

import argparse
import gzip
import json
import sys
from collections import defaultdict
from datetime import datetime

# 축 이름 → 레코드에서 값을 뽑는 함수.
# session / class / agent 는 Claude Code 가 보내주는 귀속 헤더에서 온다.
TOKEN_FIELDS = ("promptTokens", "completionTokens", "cacheWriteTokens",
                "cacheWrite1hTokens", "cacheReadTokens")


def outcome(record):
    if record.get("outcome"):
        return record["outcome"]
    if record.get("clientDisconnected"):
        return "cancelled"
    if record.get("upstreamError"):
        return "upstream_error"
    status = record.get("status")
    if isinstance(status, int):
        if status >= 400:
            return "http_error"
        if 200 <= status < 300:
            return "success"
    return "(unknown)"


def missing_usage(record):
    if record.get("usageAvailability") is not None:
        return record["usageAvailability"] == "missing"
    # Model lists/token counting are not billed generation requests.
    path = (record.get("path") or "").rstrip("/")
    generation = not path or path.endswith(("/messages", "/chat/completions", "/responses"))
    return (generation and outcome(record) in ("success", "cancelled")
            and record.get("costUsd") is None
            and not any(record.get(field) is not None for field in TOKEN_FIELDS))


DIMENSIONS = {
    "provider": lambda r: r.get("provider") or "(unknown)",
    "outcome": outcome,
    "traffic": lambda r: r.get("traffic") or "(unknown)",
    "key": lambda r: r.get("keyId") or "(unknown)",
    "model": lambda r: r.get("model") or "(unknown)",
    "day": lambda r: datetime.fromtimestamp(r.get("ts", 0) / 1000).strftime("%Y-%m-%d"),
    "format": lambda r: r.get("format") or "(unknown)",
    "session": lambda r: r.get("sessionId") or "(not claude code)",
    "class": lambda r: r.get("requestClass") or "(none)",
    "agent": lambda r: r.get("agentType") or r.get("agentId") or "(main)",
}


def parse(streams):
    """JSON 한 줄씩 읽는다. 접근 로그 레코드가 아닌 줄은 버린다."""
    skipped = 0
    for stream in streams:
        for line in stream:
            line = line.strip()
            if not line:
                continue  # 빈 줄은 "건너뛴 줄" 집계에 넣지 않는다 — 숫자가 거짓말을 한다
            if not line.startswith("{"):
                skipped += 1
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                skipped += 1
                continue
            # requestId 는 모든 접근 로그 줄에 있다. 없으면 다른 종류의 JSON 이다.
            if not isinstance(rec, dict) or "requestId" not in rec:
                skipped += 1
                continue
            # Historical access records have no event discriminator. Explicitly
            # typed content/diagnostic events share requestId but are not usage.
            if "event" in rec and rec["event"] != "gateway.access":
                skipped += 1
                continue
            yield rec
    parse.skipped = skipped


def percentile(values, p):
    """보간 없는 최근접 순위. 표본이 적을 때 있지도 않은 정밀도를 흉내내지 않는다."""
    if not values:
        return None
    ordered = sorted(values)
    idx = min(len(ordered) - 1, int(round(p / 100 * len(ordered) + 0.5)) - 1)
    return ordered[max(0, idx)]


def main():
    ap = argparse.ArgumentParser(description="LLM Gateway 접근 로그 집계")
    ap.add_argument("files", nargs="*", default=["-"],
                    help="로그 파일 경로. 생략하거나 '-' 면 stdin")
    ap.add_argument("--by", default="model",
                    help="집계 축, 쉼표로 여러 개. " + " / ".join(DIMENSIONS))
    ap.add_argument("--by-day", action="store_true",
                    help="--by day,model 의 줄임 (하위 호환)")
    ap.add_argument("--budget", type=float,
                    help="예산(USD). 총 비용의 소진율을 함께 보여준다")
    ap.add_argument("--traffic", choices=("live", "test", "demo"),
                    help="Filter traffic; historical unlabeled logs are excluded")
    ap.add_argument("--json", action="store_true", help="표 대신 JSON 으로 출력")
    args = ap.parse_args()

    axes = ["day", "model"] if args.by_day else [a.strip() for a in args.by.split(",") if a.strip()]
    unknown = [a for a in axes if a not in DIMENSIONS]
    if not axes:
        ap.error("--by requires at least one dimension")
    if unknown:
        ap.error(f"모르는 축: {', '.join(unknown)} (가능: {', '.join(DIMENSIONS)})")

    streams = [sys.stdin if p == "-" else (gzip.open if p.endswith(".gz") else open)(
                   p, mode="rt", encoding="utf-8", errors="replace")
               for p in (args.files or ["-"])]

    stats = defaultdict(lambda: {
        "requests": 0, "errors": 0, "cancelled": 0, "streamed": 0,
        "prompt": 0, "completion": 0, "cache_write": 0, "cache_write_1h": 0, "cache_read": 0,
        "cost": 0.0, "unpriced": 0, "missing_usage": 0, "partial_usage": 0, "priced": 0, "ttft": [],
    })

    for rec in parse(streams):
        if args.traffic and rec.get("traffic") != args.traffic:
            continue
        key = tuple(DIMENSIONS[a](rec) for a in axes)
        s = stats[key]
        s["requests"] += 1

        status = rec.get("status")
        if (outcome(rec) in ("http_error", "upstream_error") or rec.get("upstreamError")
                or (isinstance(status, int) and status >= 400)):
            s["errors"] += 1
        if rec.get("clientDisconnected") or outcome(rec) == "cancelled":
            s["cancelled"] += 1
        if rec.get("stream"):
            s["streamed"] += 1

        s["prompt"] += rec.get("promptTokens") or 0
        s["completion"] += rec.get("completionTokens") or 0
        s["cache_write"] += rec.get("cacheWriteTokens") or 0
        s["cache_write_1h"] += rec.get("cacheWrite1hTokens") or 0
        s["cache_read"] += rec.get("cacheReadTokens") or 0

        cost = rec.get("costUsd")
        if cost is not None:
            s["cost"] += cost
            s["priced"] += 1
        elif (rec.get("generation") is not False
              and rec.get("usageAvailability") != "not_applicable"
              and any(rec.get(field) or 0 for field in TOKEN_FIELDS)):
            # 토큰은 받았는데 값을 못 매긴 경우 = 단가 미등록. 0 으로 더하면 "싸다"는 착각을 만든다.
            # 실패한 요청은 애초에 usage 가 없으니 여기 해당하지 않는다 — 둘을 섞으면
            # 에러가 날 때마다 "단가를 추가하라"는 엉뚱한 각주가 붙는다.
            s["unpriced"] += 1

        if rec.get("usageAvailability") == "partial":
            s["partial_usage"] += 1

        if missing_usage(rec):
            s["missing_usage"] += 1

        if rec.get("ttftMs") is not None:
            s["ttft"].append(rec["ttftMs"])

    if not stats:
        print(f"집계할 접근 로그가 없다. (건너뛴 줄: {getattr(parse, 'skipped', 0)})",
              file=sys.stderr)
        return 1

    rows = []
    for key in sorted(stats):
        s = stats[key]
        row = dict(zip(axes, key))
        row.update({
            "requests": s["requests"],
            "errors": s["errors"],
            "cancelled": s["cancelled"],
            "streamed": s["streamed"],
            "prompt_tokens": s["prompt"],
            "completion_tokens": s["completion"],
            "cache_write_tokens": s["cache_write"],
            "cache_write_1h_tokens": s["cache_write_1h"],
            "cache_read_tokens": s["cache_read"],
            "total_tokens": (s["prompt"] + s["completion"] + s["cache_write"]
                             + s["cache_write_1h"] + s["cache_read"]),
            "cost_usd": round(s["cost"], 6),
            "unpriced_requests": s["unpriced"],
            "missing_usage_requests": s["missing_usage"],
            "partial_usage_requests": s["partial_usage"],
            "priced_requests": s["priced"],
            "cost_complete": not (s["unpriced"] or s["missing_usage"] or s["partial_usage"]),
            "ttft_p50_ms": percentile(s["ttft"], 50),
            "ttft_p95_ms": percentile(s["ttft"], 95),
        })
        rows.append(row)

    # 비싼 순으로 보여준다 — 최적화는 제일 비싼 것부터 한다.
    rows.sort(key=lambda r: (-r["cost_usd"], tuple(str(r[a]) for a in axes)))

    if args.json:
        print(json.dumps(rows, indent=2, ensure_ascii=False))
        return 0

    # 캐시 컬럼은 값이 있을 때만 보여준다. 늘 0 인 컬럼은 표를 넓히기만 한다.
    show_cache = any(r["cache_write_tokens"] or r["cache_write_1h_tokens"]
                     or r["cache_read_tokens"] for r in rows)
    # 1시간 TTL 컬럼은 실제로 쓰였을 때만 — 단가가 5분의 1.6배라 섞어 보면 오해한다.
    show_1h = any(r["cache_write_1h_tokens"] for r in rows)

    head = list(axes) + ["req", "err", "cancel", "no usage", "partial", "in", "out"]
    if show_cache:
        head += ["c.wr"] + (["c.wr1h"] if show_1h else []) + ["c.rd"]
    head += ["cost $", "ttft p50", "ttft p95"]

    def cost_label(r):
        uncertain = (r["unpriced_requests"] or r["missing_usage_requests"]
                     or r["partial_usage_requests"])
        if not r["priced_requests"]:
            return "?" if uncertain else "-"
        return f"{r['cost_usd']:.6f}" + ("*" if uncertain else "")

    def cells(r):
        out = [str(r[a]) for a in axes] + [
            str(r["requests"]), str(r["errors"]),
            str(r["cancelled"]), str(r["missing_usage_requests"]), str(r["partial_usage_requests"]),
            f"{r['prompt_tokens']:,}", f"{r['completion_tokens']:,}"]
        if show_cache:
            out += [f"{r['cache_write_tokens']:,}"]
            if show_1h:
                out += [f"{r['cache_write_1h_tokens']:,}"]
            out += [f"{r['cache_read_tokens']:,}"]
        out += [
            cost_label(r),
            "-" if r["ttft_p50_ms"] is None else f"{r['ttft_p50_ms']:.0f}",
            "-" if r["ttft_p95_ms"] is None else f"{r['ttft_p95_ms']:.0f}",
        ]
        return out

    body = [cells(r) for r in rows]
    total = {k: sum(r[k] for r in rows) for k in
             ("requests", "errors", "cancelled", "missing_usage_requests", "partial_usage_requests", "priced_requests",
              "prompt_tokens", "completion_tokens",
              "cache_write_tokens", "cache_write_1h_tokens", "cache_read_tokens",
              "cost_usd", "unpriced_requests")}

    footer = ["TOTAL"] + [""] * (len(axes) - 1) + [
        str(total["requests"]), str(total["errors"]),
        str(total["cancelled"]), str(total["missing_usage_requests"]), str(total["partial_usage_requests"]),
        f"{total['prompt_tokens']:,}", f"{total['completion_tokens']:,}"]
    if show_cache:
        footer += [f"{total['cache_write_tokens']:,}"]
        if show_1h:
            footer += [f"{total['cache_write_1h_tokens']:,}"]
        footer += [f"{total['cache_read_tokens']:,}"]
    footer += [cost_label(total), "", ""]
    body.append(footer)

    widths = [max(len(h), *(len(row[i]) for row in body)) for i, h in enumerate(head)]
    line = "  ".join(h.ljust(w) for h, w in zip(head, widths))
    print(line)
    print("-" * len(line))
    for i, row in enumerate(body):
        if i == len(body) - 1:
            print("-" * len(line))
        print("  ".join(cell.ljust(w) for cell, w in zip(row, widths)))

    if args.budget:
        used = total["cost_usd"]
        pct = used / args.budget * 100 if args.budget else 0
        bar_width = 30
        filled = min(bar_width, int(pct / 100 * bar_width))
        print(f"\n예산 ${args.budget:,.2f} 중 ${used:.6f} 소진 ({pct:.2f}%)")
        print(f"  [{'#' * filled}{'.' * (bar_width - filled)}]")
        if (total["unpriced_requests"] or total["missing_usage_requests"]
                or total["partial_usage_requests"]):
            print("  주의: 단가 또는 사용량을 모르는 요청이 있어 알려진 비용만 표시했습니다.")

    if total["unpriced_requests"]:
        print(f"\n* 단가를 모르는 모델이 {total['unpriced_requests']}건 있어 비용이 과소집계됐다."
              f" KnownModel 에 추가하면 잡힌다.")
    if total["missing_usage_requests"]:
        print(f"\n* 생성 요청 {total['missing_usage_requests']}건의 사용량을 받지 못했습니다. 비용은 미확인입니다.")
    if total["partial_usage_requests"]:
        print(f"\n* 중단된 생성 요청 {total['partial_usage_requests']}건은 관측된 토큰만 집계했습니다. 최종 비용은 미확인입니다.")
    if getattr(parse, "skipped", 0):
        print(f"\n접근 로그가 아닌 줄 {parse.skipped}개는 건너뛰었다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
