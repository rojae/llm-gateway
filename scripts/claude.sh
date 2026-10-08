#!/usr/bin/env bash
# 개인 Anthropic API 키를 그대로 전달하는 네이티브 Claude Code 게이트웨이.
# 서버에는 공용 Anthropic 키가 필요하지 않다. 각 사용자가 클라이언트에서 설정한다.
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077

if [[ -n "${GW_UPSTREAM_API_KEY:-}" || -n "${GATEWAY_UPSTREAM_API_KEY:-}" ]]; then
  echo "오류: 개인 키 전달 모드에서는 서버 공용 키를 설정할 수 없습니다." >&2
  echo "  unset GW_UPSTREAM_API_KEY GATEWAY_UPSTREAM_API_KEY 후 다시 실행하세요." >&2
  exit 1
fi

PORT="${GW_PORT:-8080}"
BIND="${GW_BIND_ADDRESS:-127.0.0.1}"
echo "빌드 중..."
./gradlew :llm-gateway-api:bootJar -q

echo "게이트웨이 기동 → Anthropic (개인 API 키 전달 모드)"
echo "  Messages  : http://${BIND}:${PORT}/v1/messages"
echo "  헬스체크  : http://${BIND}:${PORT}/healthz"
echo "  접근 로그 : ${GW_ACCESS_LOG_FILE:-var/access.log}"
echo "  클라이언트: ANTHROPIC_BASE_URL=http://localhost:${PORT} 및 개인 ANTHROPIC_API_KEY 설정"

# CLI 속성은 외부 설정 파일의 managed 키보다 우선한다. 비밀값은 argv에 넣지 않는다.
exec env \
  GW_PORT="$PORT" \
  GW_BIND_ADDRESS="$BIND" \
  java -jar llm-gateway-api/build/libs/llm-gateway-0.1.0.jar \
    --gateway.upstream-base-url=https://api.anthropic.com/v1 \
    --gateway.upstream-api-key=
