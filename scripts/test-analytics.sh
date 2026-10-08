#!/usr/bin/env bash
# Full Java suite with disposable PostgreSQL and Redis. No paid provider calls.
set -euo pipefail
cd "$(dirname "$0")/.."
docker info >/dev/null 2>&1 || { echo '실제 PostgreSQL 테스트를 위해 Docker를 실행하세요.' >&2; exit 1; }
test_container="llm-gateway-analytics-test-$$"
redis_container="llm-gateway-session-test-$$"
cleanup() { docker rm -f "$test_container" "$redis_container" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run --rm -d --name "$test_container" \
  -e POSTGRES_USER=llm_gateway_test -e POSTGRES_PASSWORD=llm-gateway-test-only-local -e POSTGRES_DB=llm_gateway_test \
  -p 127.0.0.1::5432 postgres:17-alpine >/dev/null
for attempt in $(seq 1 60); do
  if docker exec "$test_container" pg_isready -U llm_gateway_test -d llm_gateway_test >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$test_container" pg_isready -U llm_gateway_test -d llm_gateway_test >/dev/null
docker run --rm -d --name "$redis_container" -p 127.0.0.1::6379 \
  redis:7.2-alpine redis-server --save '' --appendonly no >/dev/null
for attempt in $(seq 1 30); do
  if [ "$(docker exec "$redis_container" redis-cli ping 2>/dev/null)" = PONG ]; then break; fi
  sleep 1
done
[ "$(docker exec "$redis_container" redis-cli ping)" = PONG ]
test_port="$(docker port "$test_container" 5432/tcp | awk -F: '{print $NF}')"
redis_port="$(docker port "$redis_container" 6379/tcp | awk -F: '{print $NF}')"
export GW_TEST_DB_URL="jdbc:postgresql://127.0.0.1:${test_port}/llm_gateway_test"
export GW_TEST_DB_USER=llm_gateway_test
export GW_TEST_DB_PASSWORD=llm-gateway-test-only-local
export GW_TEST_REDIS_URL="redis://127.0.0.1:${redis_port}"
./gradlew :llm-gateway-api:test --rerun-tasks "$@"
