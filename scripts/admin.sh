#!/usr/bin/env bash
# Local PostgreSQL + LLM Gateway admin. Credentials stay in an owner-readable ignored file.
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
command -v docker >/dev/null || { echo 'Docker가 필요합니다.' >&2; exit 1; }
command -v python3 >/dev/null || { echo 'Python 3가 필요합니다.' >&2; exit 1; }
command -v npm >/dev/null || { echo 'Node.js와 npm이 필요합니다.' >&2; exit 1; }
command -v curl >/dev/null || { echo 'curl이 필요합니다.' >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo 'Docker를 실행한 뒤 다시 시도하세요.' >&2; exit 1; }
[[ -f llm-gateway-admin/package.json ]] || {
  echo 'llm-gateway-admin/package.json이 필요합니다.' >&2
  exit 1
}

python3 - <<'PY'
from pathlib import Path
import os
import secrets
path = Path('var/admin.env')
path.parent.mkdir(parents=True, exist_ok=True)
if not path.exists():
    with path.open('x') as f:
        for key in ('GW_ANALYTICS_DB_PASSWORD', 'GW_ADMIN_TOKEN', 'GW_KEY_ID_SECRET', 'GW_TELEMETRY_TOKEN'):
            f.write(key + '=' + secrets.token_hex(32) + '\n')
# Existing local installs retain all credentials; add only the new collector key.
elif not any(line.startswith('GW_TELEMETRY_TOKEN=') for line in path.read_text().splitlines()):
    with path.open('a') as f:
        f.write('\nGW_TELEMETRY_TOKEN=' + secrets.token_hex(32) + '\n')
os.chmod(path, 0o600)
PY

# Read generated hex values as data, never source/evaluate an environment file.
while IFS='=' read -r name value; do
  case "$name" in
    GW_ANALYTICS_DB_PASSWORD|GW_ADMIN_TOKEN|GW_KEY_ID_SECRET|GW_TELEMETRY_TOKEN)
      [[ "$value" =~ ^[a-f0-9]{64}$ ]] || { echo 'var/admin.env 값 형식이 잘못됐습니다.' >&2; exit 1; }
      if [[ -z "${!name:-}" ]]; then export "$name=$value"; fi ;;
    '') continue ;;
    *) echo 'var/admin.env에 지원하지 않는 항목이 있습니다.' >&2; exit 1 ;;
  esac
done < var/admin.env

export GW_ANALYTICS_ENABLED=true
export GW_TELEMETRY_ENABLED=true
export GW_ANALYTICS_DB_USER=llm_gateway
export GW_ANALYTICS_JDBC_URL="jdbc:postgresql://127.0.0.1:${GW_ANALYTICS_DB_PORT:-54329}/llm_gateway"
export GW_PORT="${GW_PORT:-8080}"
export GW_MANAGEMENT_PORT="${GW_MANAGEMENT_PORT:-$((GW_PORT + 1010))}"
export GW_BIND_ADDRESS=127.0.0.1
export GW_ADMIN_PORT="${GW_ADMIN_PORT:-3000}"
export GW_ADMIN_UI_URL="${GW_ADMIN_UI_URL:-http://localhost:${GW_ADMIN_PORT}/admin}"
export GATEWAY_API_URL="${GATEWAY_API_URL:-http://127.0.0.1:${GW_PORT}}"
python3 - <<'PY'
import os, socket, sys
for name in ('GW_PORT', 'GW_ADMIN_PORT', 'GW_MANAGEMENT_PORT'):
    try:
        port = int(os.environ[name])
        with socket.socket() as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(('127.0.0.1', port))
    except (OSError, ValueError):
        sys.exit(f'{name} 포트를 사용할 수 없습니다. 다른 포트를 지정하세요.')
PY

compose_project=llm-gateway-analytics
legacy_volume=jev-analytics_analytics-data
current_volume=llm-gateway-analytics_analytics-data
if docker volume inspect "$legacy_volume" >/dev/null 2>&1; then
  if docker volume inspect "$current_volume" >/dev/null 2>&1; then
    echo '기존/신규 analytics 볼륨이 모두 있습니다. 사용할 데이터를 확인한 뒤 실행하세요.' >&2
    exit 1
  fi
  # Keep the legacy Compose identity so the migrated database volume is reused.
  compose_project=jev-analytics
fi
docker compose -p "$compose_project" --env-file var/admin.env -f compose.analytics.yml up -d --wait
if [[ ! -x llm-gateway-admin/node_modules/.bin/next ]]; then
  if [[ -f llm-gateway-admin/package-lock.json ]]; then
    npm_install=ci
  else
    npm_install=install
  fi
  env -u GW_ANALYTICS_DB_PASSWORD -u GW_ADMIN_TOKEN -u GW_KEY_ID_SECRET -u GW_TELEMETRY_TOKEN \
    npm --prefix llm-gateway-admin "$npm_install"
fi
if [[ ! -f llm-gateway-admin/.env.local ]]; then
  cp llm-gateway-admin/.env.example llm-gateway-admin/.env.local
  chmod 600 llm-gateway-admin/.env.local
fi

./scripts/gateway.sh "$@" &
api_pid=$!
cleanup() {
  kill "$api_pid" 2>/dev/null || true
  wait "$api_pid" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# The launcher builds the JAR first. Do not present a working console if the API failed.
api_ready=false
for ((attempt=0; attempt<90; attempt++)); do
  if curl --silent --fail "http://127.0.0.1:${GW_PORT}/healthz" >/dev/null; then
    api_ready=true
    break
  fi
  if ! kill -0 "$api_pid" 2>/dev/null; then
    echo 'API가 시작되지 않았습니다. 위의 빌드/기동 로그를 확인하세요.' >&2
    exit 1
  fi
  sleep 1
done
if [[ "$api_ready" != true ]]; then
  echo 'API 준비 시간을 초과했습니다. 기동 로그를 확인하세요.' >&2
  exit 1
fi

echo "어드민: http://localhost:${GW_ADMIN_PORT}/admin"
echo "API: http://127.0.0.1:${GW_PORT} (Next.js 프록시: ${GATEWAY_API_URL})"
echo '관리자 접속 키: var/admin.env의 GW_ADMIN_TOKEN (개인 공급자 API 키와 별도)'
echo '본문도 파일로 남기려면 --spring.profiles.include=content-log-file 옵션을 추가하세요.'
env -u GW_ANALYTICS_DB_PASSWORD -u GW_ADMIN_TOKEN -u GW_KEY_ID_SECRET -u GW_TELEMETRY_TOKEN \
  PORT="$GW_ADMIN_PORT" GATEWAY_API_URL="$GATEWAY_API_URL" \
  npm --prefix llm-gateway-admin run dev
