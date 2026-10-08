#!/usr/bin/env bash
# 개인 Anthropic/OpenAI API 키를 사용하는 단일 게이트웨이.
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077

if [[ -n "${GW_UPSTREAM_API_KEY:-}" || -n "${GATEWAY_UPSTREAM_API_KEY:-}" ]]; then
  echo "오류: 개인 키 전달 모드에서는 서버 공용 키를 설정할 수 없습니다." >&2
  echo "  unset GW_UPSTREAM_API_KEY GATEWAY_UPSTREAM_API_KEY 후 다시 실행하세요." >&2
  echo "  unset은 현재 셸의 변수만 해제하며 공급자의 API 키를 폐기하지 않습니다." >&2
  exit 1
fi
# 시작 옵션으로 개인 키 모드를 다시 덮어쓰지 않도록 막는다.
for arg in "$@"; do
  case "$arg" in
    --gateway.upstream-api-key|--gateway.upstream-api-key=*)
      echo "오류: gateway.sh에서는 --gateway.upstream-api-key를 지정할 수 없습니다." >&2
      exit 1 ;;
  esac
done

PORT="${GW_PORT:-8080}"
BIND="${GW_BIND_ADDRESS:-127.0.0.1}"
echo "빌드 중..."
./gradlew :llm-gateway-api:bootJar -q

echo "게이트웨이 기동 → Anthropic + OpenAI (각 사용자의 실제 API 키)"
echo "  Claude Code : ANTHROPIC_BASE_URL=http://localhost:${PORT}/anthropic"
echo "  OpenAI SDK  : base_url=http://localhost:${PORT}/openai/v1"
echo "  헬스체크    : http://${BIND}:${PORT}/healthz"
echo "  접근 로그   : ${GW_ACCESS_LOG_FILE:-var/access.log}"

exec env GW_PORT="$PORT" GW_BIND_ADDRESS="$BIND" \
  java -jar llm-gateway-api/build/libs/llm-gateway-0.1.0.jar \
    "$@" --gateway.upstream-api-key=
