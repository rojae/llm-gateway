#!/usr/bin/env bash
# v0 게이트웨이를 실제 API 키 없이 끝까지 돌려보는 데모.
#
#   ./scripts/demo.sh
#
# 창을 하나 더 열어서 아래를 직접 해보면 취소 전파를 눈으로 볼 수 있다:
#
#   curl -N http://localhost:8080/v1/chat/completions \
#     -H 'Content-Type: application/json' \
#     -d '{"model":"gpt-4o-mini","stream":true,"messages":[{"role":"user","content":"ping"}]}'
#
#   ... 토큰이 흐르는 중에 Ctrl+C
#   ... 게이트웨이 로그에 ">>> [demo-upstream] 구독이 취소되었다" 가 찍히고
#       접근 로그의 clientDisconnected 가 true 로 남는다.

set -euo pipefail
cd "$(dirname "$0")/.."

echo "빌드 중..."
./gradlew :llm-gateway-api:bootJar -q

echo "게이트웨이 기동 (demo 프로파일, http://localhost:8080)"
exec java -jar llm-gateway-api/build/libs/llm-gateway-0.1.0.jar --spring.profiles.active=demo
