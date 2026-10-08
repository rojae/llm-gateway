#!/usr/bin/env python3
"""게이트웨이에 붙는 대화형 REPL.

Swagger UI 로는 스트리밍을 볼 수 없다 — SSE 를 다 모아서 한 번에 그리기 때문이다.
첫 토큰 지연과 취소 전파를 눈으로 확인하려면 터미널이 필요하고, 이 스크립트가 그 자리다.

  ./scripts/chat.py                      # localhost:8080
  ./scripts/chat.py --port 8081          # 포트만 바꾸기
  ./scripts/chat.py -m claude-sonnet-5   # 모델 지정
  ./scripts/chat.py --no-stream          # 비스트리밍 (비용 헤더를 볼 수 있다)

표준 라이브러리만 쓴다. pip install 이 필요한 순간 교보재로서 실패한다.
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

try:
    import readline  # noqa: F401  (import 만으로 화살표 키·히스토리가 붙는다)
except ImportError:
    pass

# 비용·토큰은 게이트웨이가 헤더로 돌려주므로 여기서 다시 계산하지 않는다.
# 같은 숫자를 두 곳에서 만들면 반드시 어긋난다.
BOLD, DIM, CYAN, YELLOW, RED, RESET = (
    "\033[1m", "\033[2m", "\033[36m", "\033[33m", "\033[31m", "\033[0m")

HELP = """\
  /exit, /quit   끝내기 (Ctrl-D 도 동일)
  /reset         대화 기록 비우기
  /model <이름>  모델 바꾸기
  /history       지금까지 쌓인 메시지 수 보기
  /help          이 도움말
"""


def color(enabled):
    """파이프로 넘길 때는 ANSI 코드를 빼야 로그가 깨지지 않는다."""
    if enabled:
        return BOLD, DIM, CYAN, YELLOW, RED, RESET
    return ("",) * 6


def probe(base):
    """기동 직후 게이트웨이에 자기소개를 시킨다.

    어느 업스트림에 물려 있는지, 키를 누가 소유하는지를 먼저 알아야
    "키를 안 넣었는데 왜 401 이지" 같은 시간 낭비를 피할 수 있다.
    """
    try:
        with urllib.request.urlopen(base + "/healthz", timeout=5) as r:
            return json.load(r)
    except Exception as e:
        sys.exit(f"게이트웨이에 닿지 못했다 ({base}/healthz): {e}\n"
                 f"  띄웠는지 확인: curl -s {base}/healthz")


def build_request(base, payload, api_key):
    req = urllib.request.Request(
        base + "/v1/chat/completions",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
    )
    if api_key:
        # passthrough 모드에서는 이 헤더가 업스트림까지 그대로 간다.
        req.add_header("Authorization", "Bearer " + api_key)
    return req


def stream_reply(resp, c, started):
    """SSE 프레임을 받는 즉시 그린다 — 모아두면 스트리밍을 보는 의미가 없다.

    `started` 는 반드시 **요청을 보내기 전** 시각이어야 한다. urlopen() 은 응답 헤더가
    올 때까지 블로킹하므로, 이 함수 안에서 시각을 잡으면 그 대기 시간이 빠져
    첫 토큰 지연이 0ms 로 찍힌다 — 측정값이 거짓말을 한다.
    """
    _, dim, cyan, _, _, reset = c
    parts, usage, first_token_ms = [], None, None

    for raw in resp:
        line = raw.decode("utf-8", "replace").strip()
        if not line.startswith("data:"):
            continue
        data = line[5:].strip()
        if data == "[DONE]":
            break
        try:
            frame = json.loads(data)
        except json.JSONDecodeError:
            continue

        # 게이트웨이가 include_usage 를 주입하므로 마지막 프레임에 usage 가 실려 온다.
        if frame.get("usage"):
            usage = frame["usage"]

        for choice in frame.get("choices") or []:
            piece = (choice.get("delta") or {}).get("content")
            if piece:
                if first_token_ms is None:
                    first_token_ms = (time.monotonic() - started) * 1000
                    print(f"{cyan}", end="", flush=True)
                parts.append(piece)
                print(piece, end="", flush=True)

    print(reset)
    return "".join(parts), usage, first_token_ms


def main():
    ap = argparse.ArgumentParser(description="LLM Gateway 대화형 클라이언트")
    ap.add_argument("--url", default=os.getenv("LLM_GATEWAY_CHAT_URL"),
                    help="게이트웨이 주소 (기본: http://localhost:8080)")
    ap.add_argument("--port", type=int, help="--url 대신 포트만 바꿀 때")
    ap.add_argument("-m", "--model", default=os.getenv("LLM_GATEWAY_CHAT_MODEL", "gpt-4o-mini"))
    ap.add_argument("--no-stream", action="store_true",
                    help="스트리밍 끄기. 비용·지연 헤더는 이때만 붙는다")
    ap.add_argument("--max-tokens", type=int, default=1024)
    ap.add_argument("--temperature", type=float,
                    help="생략이 기본. claude-sonnet-5 등은 이 값을 거부한다")
    ap.add_argument("--system", help="시스템 프롬프트")
    ap.add_argument("--no-color", action="store_true")
    args = ap.parse_args()

    base = (args.url or f"http://localhost:{args.port or 8080}").rstrip("/")
    c = color(not args.no_color and sys.stdout.isatty())
    bold, dim, cyan, yellow, red, reset = c

    health = probe(base)
    # managed 모드면 게이트웨이가 키를 소유한다 — 호출자는 아무것도 몰라도 된다.
    api_key = None
    if health.get("keyMode") == "passthrough":
        api_key = os.getenv("OPENAI_API_KEY") or os.getenv("ANTHROPIC_API_KEY")

    model = args.model
    print(f"{bold}LLM Gateway chat{reset}  {dim}({base}){reset}")
    print(f"{dim}  upstream : {health.get('upstream')}{reset}")
    print(f"{dim}  keyMode  : {health.get('keyMode')}"
          f"{'' if health.get('keyMode') == 'managed' else (' / 호출자 키 ' + ('있음' if api_key else '없음 — 401 이 날 것이다'))}{reset}")
    print(f"{dim}  model    : {model}   stream: {not args.no_stream}{reset}")
    print(f"{dim}  /help 로 명령 목록{reset}\n")

    messages = []
    if args.system:
        messages.append({"role": "system", "content": args.system})

    while True:
        try:
            line = input(f"{bold}you ›{reset} ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not line:
            continue

        if line in ("/exit", "/quit"):
            break
        if line == "/help":
            print(HELP)
            continue
        if line == "/reset":
            messages = [m for m in messages if m["role"] == "system"]
            print(f"{dim}기록을 비웠다.{reset}")
            continue
        if line == "/history":
            print(f"{dim}메시지 {len(messages)}개{reset}")
            continue
        if line.startswith("/model"):
            parts = line.split(maxsplit=1)
            if len(parts) == 2:
                model = parts[1].strip()
                print(f"{dim}model → {model}{reset}")
            else:
                print(f"{dim}현재 model: {model}{reset}")
            continue
        if line.startswith("/"):
            print(f"{yellow}모르는 명령이다. /help 참고.{reset}")
            continue

        messages.append({"role": "user", "content": line})
        payload = {
            "model": model,
            "messages": messages,
            "stream": not args.no_stream,
            "max_tokens": args.max_tokens,
        }
        # 넣지 않는 것이 기본이다. 일부 모델은 temperature 자체를 거부한다.
        if args.temperature is not None:
            payload["temperature"] = args.temperature

        started = time.monotonic()
        try:
            with urllib.request.urlopen(build_request(base, payload, api_key), timeout=300) as resp:
                rid = resp.headers.get("x-gw-request-id")
                if args.no_stream:
                    body = json.loads(resp.read())
                    reply = body["choices"][0]["message"]["content"]
                    print(f"{cyan}{reply}{reset}")
                    meta = (f"cost ${resp.headers.get('x-gw-cost-usd', '?')}"
                            f" · upstream {resp.headers.get('x-gw-upstream-ms', '?')}ms"
                            f" · {body.get('usage', {}).get('total_tokens', '?')} tok")
                else:
                    reply, usage, ttft = stream_reply(resp, c, started)
                    total = (time.monotonic() - started) * 1000
                    tok = (usage or {}).get("total_tokens", "?")
                    # 스트리밍 응답에는 비용 헤더가 붙지 않는다 —
                    # 헤더는 첫 토큰보다 먼저 나가는데 비용은 스트림이 끝나야 알 수 있다.
                    meta = (f"첫 토큰 {ttft:.0f}ms · 전체 {total:.0f}ms · {tok} tok"
                            if ttft is not None else f"전체 {total:.0f}ms")
                    meta += " · cost 는 접근 로그에서"
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")
            try:
                detail = json.loads(detail)["error"]["message"]
            except Exception:
                detail = detail[:300]
            print(f"{red}HTTP {e.code}{reset} {detail}")
            messages.pop()  # 실패한 턴은 기록에 남기지 않는다
            continue
        except KeyboardInterrupt:
            # 스트림 중간에 끊으면 게이트웨이가 업스트림으로 취소를 전파한다.
            print(f"\n{yellow}취소했다 — 업스트림까지 전파된다.{reset}")
            messages.pop()
            continue
        except Exception as e:
            print(f"{red}실패{reset} {e}")
            messages.pop()
            continue

        messages.append({"role": "assistant", "content": reply})
        print(f"{dim}  {meta}" + (f" · {rid}" if rid else "") + f"{reset}\n")

    print(f"{dim}bye{reset}")


if __name__ == "__main__":
    main()
