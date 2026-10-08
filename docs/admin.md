# 사용량 적재와 LLM Gateway 어드민

LLM Gateway는 각 사용자의 Anthropic/OpenAI 키를 그대로 전달하면서 요청 결과를 JSONL로 기록합니다. 선택 기능인 analytics를 켜면 백그라운드 작업이 이 기록을 PostgreSQL로 모으고 Next.js/React 콘솔의 `/admin`에서 조회합니다. 공급자 호출에 PostgreSQL 응답을 기다리게 하지 않습니다.

## 운영 인증 방식

사내 배포는 `GW_SECURITY_MODE=enterprise`로 실행합니다. `/admin`에서 OIDC 인증을 시작하고, 토큰은 API 서버 세션에 보관합니다. 브라우저는 HttpOnly 세션 쿠키를 사용합니다. 조직은 IdP의 검증된 클레임으로 결정되며 화면이나 요청 파라미터로 바꿀 수 없습니다.

| 역할 | 사용량·요청 메타데이터 | 요청·응답 본문 / 적재 상태 | 수집 키 / 감사 기록 |
|---|---|---|---|
| `viewer` | 조회 | 불가 | 불가 |
| `operator` | 조회 | 조회 | 불가 |
| `admin` | 조회 | 조회 | 관리 / 조회 |

관리자도 자기 조직 데이터만 조회합니다. 본문 조회는 감사 기록 저장이 성공해야 반환하며, 수집 키 생성·폐기와 해당 감사 기록은 한 트랜잭션으로 저장합니다. 상세 연결은 [SSO 설정](sso.md), 배포·보관기간·백업은 [운영 가이드](operations.md)를 따릅니다.

`local` 모드는 아래 개발용 실행기와 기존 관리자 키를 위한 호환 모드입니다. Enterprise 모드에서는 공용 `GW_ADMIN_TOKEN`, `GW_TELEMETRY_TOKEN` 대신 SSO와 DB에서 발급한 개별 수집 키를 사용합니다.

## 로컬에서 시작

Java 21, Node.js 22·npm, Python 3, curl, Docker Compose를 설치하고 Docker를 실행한 상태에서:

```bash
./scripts/admin.sh
# 또는 llm-gateway-admin 디렉터리에서: npm run dev:stack
```

이 실행기는 로컬 PostgreSQL(`54329`), Spring Boot API(`8080`), Next.js 화면(`3000`)을 시작합니다. `http://localhost:3000/admin`의 로그인 화면에서 **`var/admin.env`의 `GW_ADMIN_TOKEN` 값**을 입력합니다. 개인 Anthropic/OpenAI API 키를 입력하는 화면이 아닙니다. 화면 요청은 Next.js의 rewrite를 통해 API로 전달합니다.

`var/admin.env`는 처음 실행 시 무작위로 생성되며, 파일 권한은 소유자만 읽고 쓰는 `0600`입니다. DB 비밀번호·관리자 접속 키·수집 전용 키·키 식별자용 HMAC 비밀값을 담습니다. Git에서 제외된 `var/` 아래에 두고, 값을 로그·이슈·공유 화면에 복사하지 마세요. 관리자 키를 브라우저 저장소나 URL에 보관하지 않으므로 페이지를 새로 열면 다시 로그인합니다. 로그아웃하면 화면과 메모리에서 지웁니다.

Next.js 화면의 API 연결 주소는 [`llm-gateway-admin/.env.example`](../llm-gateway-admin/.env.example)에 예시가 있고, 실제 로컬 기본값은 Git에서 제외된 `llm-gateway-admin/.env.local`에 있습니다. Spring Boot의 로컬 설정은 [`llm-gateway-api/.env.example`](../llm-gateway-api/.env.example)을 복사한 `llm-gateway-api/.env.local`에서 읽습니다. `admin.sh`로 실행하면 연결 주소를 실행기가 전달하며, 서버 비밀값은 `var/admin.env`에 별도로 보관합니다. 로그인 키를 확인하려면 `sed -n 's/^GW_ADMIN_TOKEN=//p' var/admin.env`를 실행하고 출력된 **값만** 입력하세요. 공급자 API 키는 각 사용자가 별도로 관리합니다.

다른 서버가 8080에서 실행 중이라면 실행기는 해당 프로세스를 종료하지 않습니다. 별도 포트와 **별도 출력 로그**를 쓰면서 기존 로그를 조회하려면:

```bash
GW_PORT=8082 \
GW_ACCESS_LOG_FILE=var/admin-runtime/access.log \
GW_APPLICATION_LOG_FILE=var/admin-runtime/application.log \
GW_CONTENT_LOG_FILE=var/admin-runtime/content.log \
GW_ANALYTICS_ACCESS_LOG=var/access.log \
GW_ANALYTICS_CONTENT_LOG=var/content.log \
GW_ADMIN_PORT=3001 \
./scripts/admin.sh
```

이 경우 API는 `8082`, 화면은 `http://localhost:3001/admin`에서 실행됩니다. `admin.sh`가 `GATEWAY_API_URL=http://127.0.0.1:8082`와 `GW_ADMIN_UI_URL=http://localhost:3001/admin`을 기본 설정합니다. 기존 8080의 호출은 그대로 두고 해당 파일을 읽습니다. 두 Java 프로세스가 같은 회전 로그 파일에 동시에 쓰지 않도록 출력 경로를 분리하세요.

`llm-gateway-admin`의 `npm run dev`는 `scripts/dev.mjs`를 통해 `.env.local`의 `GATEWAY_API_URL`을 읽어 Next.js 화면만 시작합니다. 실행 시 전달한 `GATEWAY_API_URL`이 있으면 그 값이 우선합니다. 다른 컴퓨터에서 `.env.local`이 없으면 `.env.example`을 복사한 뒤 실행하세요. API에서 analytics가 꺼져 있으면 로그인 API가 503을 반환하므로, 로컬 어드민 전체를 쓰려면 `npm run dev:stack`을 사용하세요. 이 명령은 루트 `./scripts/admin.sh`와 동일하며 PostgreSQL·Spring Boot API·Next.js를 함께 시작합니다. 포트가 이미 쓰이는 경우 `GW_PORT=8082 GW_ADMIN_PORT=3001 npm run dev:stack`처럼 지정할 수 있습니다. 화면의 기본 포트는 `3000`이고 `PORT`로 바꿀 수 있습니다. Enterprise SSO에서 API의 로그인 성공·실패가 화면으로 돌아오도록 `GW_ADMIN_UI_URL`을 화면의 외부 `/admin` 주소로 설정하세요.

### 기존 로컬 PostgreSQL 이전

새 로컬 설치는 `llm-gateway-analytics` Compose 프로젝트를 사용합니다. 이름 변경 전의 로컬 Compose 데이터를 계속 쓰려면 기존 `jev-analytics` Compose 프로젝트와 볼륨을 유지한 채 DB·역할·스키마를 이전해야 합니다. 먼저 별도 `pg_dump` 백업을 만들고 복원 가능성을 확인하세요. 앱의 DB 연결을 멈추고 기존 PostgreSQL 컨테이너는 실행한 상태에서 프로젝트 루트에서 다음 순서로 실행합니다.

```bash
bash scripts/migrate-legacy-analytics-db.sh --check
bash scripts/migrate-legacy-analytics-db.sh --apply
./scripts/admin.sh
```

`--check`는 변경 없이 이전 조건을 검사합니다. 기존 DB의 Flyway V1–V5 이력이 모두 성공했고, 기존 DB 연결이 없으며, 역할 암호가 SCRAM 형식이고 새 DB·역할·스키마와 충돌하지 않아야 합니다. `--apply`는 기존 DB·역할과 `jev_analytics` 스키마를 현재 이름인 `llm_gateway` DB·역할과 `llm_gateway_analytics` 스키마로 변경한 뒤 결과를 확인합니다. 예전 Flyway V1–V5 SQL 파일의 기존 스키마 표기는 설치된 마이그레이션 체크섬을 위해 역사적으로 남아 있으며 현재 쿼리 대상이 아닙니다. 이전 스크립트는 기존 Compose 프로젝트/볼륨을 이름 변경하거나 데이터를 복사하지 않습니다. 실제 데이터의 이전 결과는 백업과 요청 건수·조직별 건수를 대조해 확인하세요.

## IntelliJ 또는 기존 PostgreSQL 사용

Spring Boot는 `.env` 파일을 자동으로 읽지 않으므로 `application.yml`이 로컬 파일을 선택적으로 가져옵니다. 프로젝트 루트와 `llm-gateway-api` 모듈 디렉터리 중 어느 쪽을 작업 디렉터리로 사용해도 `llm-gateway-api/.env.local`의 설정과 `var/admin.env`의 비밀값을 읽습니다. 새 로컬 설치에서는 `./scripts/admin.sh`를 한 번 실행해 `var/admin.env`와 PostgreSQL을 준비한 뒤 IntelliJ에서 `GatewayApplication`을 실행하세요. 그때 `admin.sh`가 띄운 API와 포트가 겹치지 않도록 먼저 실행기를 종료하거나 IntelliJ의 `GW_PORT`를 다른 값으로 지정하세요. UI는 별도 터미널에서 `cd llm-gateway-admin && npm run dev`로 시작합니다.

`llm-gateway-api/.env.local`은 Git에서 제외됩니다. 필요한 값을 바꾸려면 [예시 파일](../llm-gateway-api/.env.example)을 참고하세요. `GW_ANALYTICS_ENABLED=true`와 DB 주소 등의 로컬 기본값이 들어 있고, 관리자 키와 DB 비밀번호는 `var/admin.env`에 남깁니다. 실제 프로세스 환경변수나 CLI 옵션이 파일 값보다 우선합니다. 운영 환경에서는 아래 값을 배포 환경의 비밀값 관리 방식으로 주입하세요.

| 환경변수 | 값 / 역할 |
|---|---|
| `GW_ANALYTICS_ENABLED` | `true` |
| `GW_ANALYTICS_JDBC_URL` | 예: `jdbc:postgresql://127.0.0.1:54329/llm_gateway` |
| `GW_ANALYTICS_DB_USER` | DB 사용자. 로컬 실행기는 `llm_gateway` |
| `GW_ANALYTICS_DB_PASSWORD` | DB 비밀번호 |
| `GW_ADMIN_TOKEN` | 최소 32자 관리자 전용 접속 키 |
| `GW_ANALYTICS_SOURCE_ID` | 이 로그 소스의 고유 이름. 기본 `local`, 여러 서버는 서로 다른 값 |
| `GW_ANALYTICS_TENANT_ID` | 이 배포의 파일 로그가 속한 조직. 기본 `local`; 기업 모드는 허용된 조직을 명시 |
| `GW_ANALYTICS_MAX_POOL_SIZE` | JDBC 풀 최대 연결 수. 기본 `8` |
| `GW_ANALYTICS_CONNECTION_TIMEOUT` | 풀에서 연결을 기다리는 상한. 기본 `3s` |
| `GW_ANALYTICS_REQUEST_RETENTION_DAYS` | 요청 보관 일수. 기본 `0`은 자동 삭제 안 함 |
| `GW_ANALYTICS_CONTENT_RETENTION_DAYS` | 본문 보관 일수. 기본 `0`은 자동 삭제 안 함 |
| `GW_ANALYTICS_ACCESS_LOG` | 읽을 JSONL 경로. 기본은 `GW_ACCESS_LOG_FILE` 또는 `var/access.log` |
| `GW_ANALYTICS_CONTENT_LOG` | 선택적 내용 JSONL 경로. 기본은 `GW_CONTENT_LOG_FILE` 또는 `var/content.log` |
| `GW_ANALYTICS_POLL_INTERVAL` | 폴링 간격. 기본 `2s` |
| `GW_ANALYTICS_BATCH_SIZE` | 한 번에 처리하는 줄 수. 기본 `250` |
| `GW_ANALYTICS_MAX_LINE_BYTES` | 한 줄의 최대 크기. 기본 `1048576` |
| `GW_KEY_ID_SECRET` | 재시작 뒤에도 개인 키별 식별자를 유지할 고정된 서버 비밀값 |

DB 안의 `llm_gateway_analytics` 스키마를 Flyway 버전 마이그레이션으로 관리하므로 해당 스키마의 생성·변경 권한이 필요합니다. 변경 전 백업하고 검증 환경에서 먼저 마이그레이션하세요. 이전의 버전 이력 없는 LLM Gateway DB는 `GW_ANALYTICS_LEGACY_TENANT_ID`로 기존 데이터의 조직을 명시해야 채택하며, 알 수 없는 스키마는 자동 채택하지 않습니다. 로컬 Compose의 DB 데이터는 이름 있는 볼륨에 남습니다. `var/admin.env`의 비밀번호만 바꾸면 기존 PostgreSQL 사용자의 비밀번호가 자동 변경되지는 않습니다.

로컬 관리자 키는 설정된 `GW_ANALYTICS_TENANT_ID` 조직에 접근합니다. 사내 서비스에는 이 개발용 키를 배포하지 않고 위 SSO 구성을 적용하세요. 개인 공급자 API 키를 전달하는 프록시 경로와 관리자 인증은 별개입니다.

## 화면에서 확인할 수 있는 것

- 개요: 선택 기간의 요청·오류·토큰·알려진 비용 소계, 모델별/일별 사용량.
- 요청: 날짜·시간대·키 식별자·공급자·모델·세션·결과·트래픽 종류로 필터하고 페이지별 조회.
- 요청 상세: 상태·사용량과 요청/응답 내용을 별도 영역에 표시. 로그에 없던 내용은 만들어내지 않습니다.
- 적재 상태: 최근 성공, 파일별 읽은 위치, 잘못된 줄 수, DB 또는 파일 오류 상태.
- 구독 모니터링: 계정별 세션·질문, 연결된 Claude 호출의 토큰·API 환산액, Codex 미귀속 이벤트 및 수신 상태. [CLI 직접 전송 설정](remote-monitoring.md).

기본 기간은 선택 시간대의 오늘이며 기본 트래픽은 `live`입니다. `local` 환경에서도 실제 공급자 호출은 live일 수 있습니다. 날짜의 끝은 선택 시간대에서 다음 날 00:00 미만으로 처리합니다. 한 조회 기간은 최대 366일, 목록은 최대 100건씩 조회합니다.

`costUsd`는 **알려진 비용의 소계**입니다. 사용량 누락·부분 사용량·단가 미등록 요청이 있으면 전체 비용이 확정된 것처럼 표시하지 않습니다. `keyId`는 개인 API 키의 익명 식별자이며 사용자 이름이 아닙니다. `sessionId`도 클라이언트가 제공한 분석용 값입니다.

### Claude 구독과 API 과금

Claude Pro/Max 구독과 Anthropic API 사용료는 별도입니다. 개인 API 키를 사용하는 이 게이트웨이 경로는 구독 잔여량으로 자동 전환되지 않습니다. API 키나 게이트웨이 인증 변수가 저장된 Claude 계정 로그인보다 우선할 수 있으므로 Claude Code의 `/status`에서 인증 출처를 확인하세요. HTTP 성공이나 이 화면의 비용 숫자만으로 구독 과금 여부를 판정할 수 없습니다.

구독 로그인 상태의 개발 사용량은 아래의 Claude Code OpenTelemetry 수집 경로로 조회합니다. API 단가로 환산한 추정 비용은 월 구독료·잔여 한도·실제 청구액과 다릅니다.

공식 문서: [구독과 API의 별도 과금](https://support.claude.com/en/articles/9876003-i-have-a-paid-claude-subscription-pro-max-team-or-enterprise-plans-why-do-i-have-to-pay-separately-to-use-the-claude-api-and-console), [게이트웨이 인증 우선순위](https://code.claude.com/docs/en/llm-gateway-connect#conflicts-with-an-existing-login), [Claude Code 모니터링](https://code.claude.com/docs/en/monitoring-usage).

## 구독 Claude Code 모니터링

새 계정·질문 모니터링은 [원격 직접 전송 가이드](remote-monitoring.md)를 따릅니다. 관리자 화면에서 owner 계정을 연결한 키를 발급하고 Claude/Codex 사용자 설정에 수집 주소를 적용합니다. 아래 실행기·owner 없는 기존 키는 Claude 사용량만 기존 요청 화면에 저장하는 호환 경로입니다. 실행기는 질문 수집을 끄므로 새 질문 화면을 위한 설정으로 사용하지 않습니다.

```text
Claude Code ── 구독 로그인 / 모델 요청 ──→ Anthropic
      └──── 사용량 이벤트 ──→ LLM Gateway /telemetry/v1/logs ──→ PostgreSQL ──→ 어드민
```

사내 서비스에서는 관리자가 SSO로 로그인한 뒤 **수집 키** 메뉴에서 사용자·장치별 키를 발급합니다. 키는 발급 화면에 한 번만 표시되며, 만료일과 청구 모드 선언값을 갖습니다. 새 키는 `llmgw_col_` 접두사를 사용합니다. 발급한 키를 사용자의 `GW_TELEMETRY_TOKEN`, 서비스 주소를 `GW_TELEMETRY_ENDPOINT=https://gateway.example.com/telemetry/v1/logs`로 설정합니다. 수집기의 저장 조직은 키에 귀속되어 클라이언트 이벤트로 위조할 수 없습니다. 폐기된 키는 다음 요청부터 거절됩니다.

개발용으로는 `./scripts/admin.sh`가 생성한 `var/admin.env`의 **쓰기 전용 `GW_TELEMETRY_TOKEN`**을 사용합니다. 관리자 키나 공급자 자격증명은 수집 키로 사용하지 않습니다.

Claude Code에서 구독 계정으로 로그인한 다음, 작업할 프로젝트 디렉터리에서 실행하세요:

```bash
# 모델 호출 없이 구독 인증 상태·수집기 연결만 확인
/path/to/llm-gateway/scripts/claude-monitor.py --check

# 평소 Claude Code처럼 대화·개발 작업
/path/to/llm-gateway/scripts/claude-monitor.py

# 어드민을 8082에 실행했다면
/path/to/llm-gateway/scripts/claude-monitor.py --endpoint http://127.0.0.1:8082/telemetry/v1/logs
```

이 실행기는 **실행할 자식 프로세스에서만** `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_BASE_URL`을 제거하고 `claude auth status --json`으로 Claude 계정 구독 로그인을 확인합니다. 원래 셸·저장된 로그인·설정 파일은 변경하지 않습니다. 설정 파일의 `apiKeyHelper`나 공급자 설정 때문에 구독 인증이 확인되지 않으면 실행을 중단합니다. 실행 후 `/status`에서도 인증 출처를 확인할 수 있습니다. 기존에 직접 실행한 Claude Code 프로세스의 설정을 소급 변경하지 않습니다.

수집 주소는 `GW_TELEMETRY_ENDPOINT`, 수집 키는 `GW_TELEMETRY_TOKEN` 환경변수로도 지정합니다. 로컬 환경변수가 없으면 실행기가 LLM Gateway의 `var/admin.env`에서 수집 키만 읽습니다. 원격 주소에는 HTTPS를 사용합니다. 이 키는 모델 API에 전송되지 않습니다.

어드민 **데이터 소스 → Claude Code**를 선택하면 토큰·모델·세션·성공/오류와 CLI가 보고한 API 단가 환산액을 조회합니다. 게이트웨이 API 비용과 환산액을 합산하지 않습니다. `전체` 소스는 관측 기록 수를 보여주므로 같은 호출을 게이트웨이와 텔레메트리에서 모두 수집했다면 중복 관측될 수 있습니다.

이 기존 호환 경로는 `claude_code.api_request`, `claude_code.api_error`만 저장합니다. 프롬프트·응답 본문·도구 인자·이메일·공급자 키는 저장하지 않습니다. 따라서 기존 요청 화면의 구독 텔레메트리 상세에는 요청·응답 본문이 없습니다. 계정이 연결된 새 키의 질문 본문은 별도 보호 저장소와 `content:read` 권한을 사용합니다. 세션/설치 식별자와 `query_source` 등의 값은 클라이언트가 보낸 분석용 정보이며 결제 인증 수단이 아닙니다. `subscription_declared`는 수집기 설정상 구독 데이터라는 뜻이며, 서버가 실제 결제 수단을 검증했다는 뜻은 아닙니다.

### 기존 실행 환경에 직접 설정

```bash
export CLAUDE_CODE_ENABLE_TELEMETRY=1
export OTEL_LOGS_EXPORTER=otlp
export OTEL_METRICS_EXPORTER=none
export OTEL_TRACES_EXPORTER=none
export OTEL_EXPORTER_OTLP_LOGS_PROTOCOL=http/json
export OTEL_EXPORTER_OTLP_LOGS_COMPRESSION=none
export OTEL_EXPORTER_OTLP_LOGS_ENDPOINT=http://127.0.0.1:8080/telemetry/v1/logs
export OTEL_EXPORTER_OTLP_LOGS_HEADERS="Authorization=Bearer ${GW_TELEMETRY_TOKEN}"
export OTEL_LOG_USER_PROMPTS=0
export OTEL_LOG_ASSISTANT_RESPONSES=0
export OTEL_LOG_TOOL_DETAILS=0
export OTEL_LOG_RAW_API_BODIES=0
claude
```

이 설정은 인증 방식을 바꾸지 않습니다. 구독으로 사용하려면 API 키·게이트웨이 인증 설정이 우선하고 있지 않은지 별도로 확인해야 하므로 로컬에서는 위 실행기를 권장합니다. OTLP는 배치로 전송되어 요청 직후 즉시 화면에 나타나지 않을 수 있습니다. CLI를 강제 종료하거나 네트워크 장애가 SDK 재시도 기간보다 길면 미전송 이벤트가 유실될 수 있습니다.

수집기 서버의 환경변수입니다. `GW_TELEMETRY_TOKEN`과 `GW_TELEMETRY_BILLING_MODE`는 **local 모드 서버 전용**입니다. Enterprise 서버에는 공용 토큰을 설정하지 않고, 관리 화면에서 발급한 키마다 조직·청구 모드를 저장합니다. 같은 이름의 `GW_TELEMETRY_TOKEN`을 사용자 PC의 실행기에 설정하는 것은 서버 설정과 별개입니다.

| 환경변수 | 값 / 역할 |
|---|---|
| `GW_TELEMETRY_ENABLED` | `true`; analytics도 활성화되어야 함 |
| `GW_TELEMETRY_TOKEN` | local 서버 전용: 최소 32자 수집 전용 키; 관리자 키와 다르게 설정 |
| `GW_TELEMETRY_BILLING_MODE` | local 서버 전용: 기본 `subscription_declared`; `api_key_declared` 또는 `unknown`도 허용 |
| `GW_TELEMETRY_MAX_BODY_BYTES` | 기본 2097152바이트 |
| `GW_TELEMETRY_MAX_RECORDS` | 배치 최대 1000개 |

수신 경로는 `POST /telemetry/v1/logs`이며 OTLP **HTTP/JSON·비압축**만 지원합니다. gRPC·Protobuf·gzip·`/v1/metrics` 수신은 제공하지 않습니다. 데이터베이스 커밋 후 200을 반환하고 장애는 503으로 돌려 SDK 재시도를 유도합니다. 잘못된 개별 이벤트는 OTLP `partialSuccess`로 거절 수를 반환합니다. 같은 이벤트 재전송은 중복 저장하지 않으며, 공급자/클라이언트 요청 ID가 없을 때는 원본 시각·세션·순서 등으로 만든 식별자를 사용하므로 완전한 공급자 청구 원장으로 간주하지 않습니다.

## 요청·응답 본문을 보려면

콘솔에만 출력된 과거 본문은 DB에서 복원할 수 없습니다. 원본 게이트웨이에서 `content-log-file` 프로파일과 DEBUG/TRACE를 켜서 `var/content.log`에 남겨야 합니다. `admin.sh`는 기존 파일을 수집하며 본문 캡처 설정을 자동으로 바꾸지 않습니다. 새로 실행하는 로컬 게이트웨이에서 본문 파일 기록도 켜려면:

```bash
./scripts/admin.sh --spring.profiles.include=content-log-file
```

운영 프로파일에서는 로그 레벨도 명시합니다. 별도 8080 프로세스의 설정은 그 프로세스에 적용해야 합니다.

```bash
GW_CONTENT_LOG_LEVEL=DEBUG ./scripts/gateway.sh --spring.profiles.active=prod,content-log-file
```

본문 저장은 [기존 내용 로그의 길이 제한·생략·마스킹 정책](logging.md)을 따릅니다. `truncated`와 `partial`도 함께 저장합니다. 내용 로거는 큐가 가득 차면 기록을 버릴 수 있어 전체 대화 보존을 보장하지 않습니다. analytics 자체가 원문 캡처를 확대하거나 공급자 키를 저장하지 않습니다.

## API

모든 `/admin/api/**`는 인증이 필요하며 응답은 `Cache-Control: no-store`입니다. Enterprise는 SSO 세션 또는 검증된 IdP API JWT를 사용하고, 로컬 모드는 `Authorization: Bearer <관리자 접속 키>`를 사용합니다. 쓰기와 로그아웃은 세션 API에서 받은 CSRF 토큰을 `X-CSRF-TOKEN`에 넣습니다.

| 경로 | 응답 |
|---|---|
| `GET /admin/api/session` | 관리자 인증 확인 |
| `GET /admin/api/overview` | 합계, 모델별·일별 사용량 |
| `GET /admin/api/requests` | 요청 목록 및 전체 건수 |
| `GET /admin/api/requests/{requestId}` | 접근 레코드와 요청·응답 내용 |
| `GET /admin/api/ingestion` | 적재 작업 상태 |
| `GET/POST /admin/api/collectors` | 자기 조직 수집 키 목록 / 발급 (`admin`) |
| `DELETE /admin/api/collectors/{id}` | 수집 키 폐기 (`admin`) |
| `GET /admin/api/audit` | 자기 조직 감사 기록 (`admin`) |
| `GET/POST /admin/api/monitoring/accounts` | 조직 내 모니터링 계정 조회 / 등록 (`collectors:manage`) |
| `GET /admin/api/monitoring/sessions` | 계정·소스·기간별 세션 |
| `GET /admin/api/monitoring/sessions/{id}/questions` | 질문 목록, 본문 미포함 |
| `GET /admin/api/monitoring/questions/{id}` | 질문 사용량과 권한이 있는 본문 |
| `GET /admin/api/monitoring/sessions/{id}/events` | 안전한 이벤트 메타데이터·미귀속 관측값 |
| `GET /admin/api/monitoring/status` | 마지막 수신·저장·중복·거절·미지원·미귀속 상태 |
| `POST /admin/auth/logout` | 서버 세션 종료 |

조회 필터는 `source` (`gateway/claude_code/all`, 기본 `gateway`), `billingMode`, `from`, `to` (`YYYY-MM-DD`, 양끝 날짜 포함), `zone` (기본 `Asia/Seoul`), `keyId`, `model`, `sessionId`, `provider`, `outcome`, `traffic` (`live/test/demo/all`)입니다. 목록에 `limit`·`offset`을 추가할 수 있습니다. 모델·키·세션은 정확히 일치하는 값으로 필터합니다. 잘못된 필터는 400, 잘못된 인증은 401, 없는 요청은 404, DB 장애는 503으로 구분합니다.

## 적재 보장과 운영 범위

접근 레코드는 `(tenantId, requestId)`, 내용 레코드는 `(tenantId, requestId, phase)`로 중복을 막습니다. 파일을 다시 읽거나 회전된 파일을 처리해도 같은 요청 비용을 다시 더하지 않습니다. 데이터 저장과 읽은 위치 갱신은 한 DB 트랜잭션으로 처리하므로 DB 쓰기가 실패하면 다음 실행에서 재시도합니다. 끝에 줄바꿈이 없는 진행 중인 줄은 완료될 때까지 기다립니다. 회전된 `.gz` 파일도 읽습니다.

로그 파일 자체가 먼저 삭제되거나 디스크가 유실되면 미수집 요청을 복원할 수 없습니다. 원본 로그의 보관기간은 예상 DB 장애 기간보다 길게 설정하고, DB와 로그 볼륨을 백업하세요. DB 보관기간은 위 두 환경변수로 켭니다. 삭제는 조직별 작은 배치로 수행하고, 재전송으로 지운 본문이 다시 살아나지 않도록 중복 식별자를 남깁니다. 식별자 테이블과 감사 기록은 일반 요청 보관기간의 자동 삭제 대상이 아니므로 별도 용량·보관 정책이 필요합니다. 이 저장소는 관측된 사용량 분석용이며 공급자 청구서와의 정산 시스템은 아닙니다.

## 검증

```bash
# 일회용 PostgreSQL·Redis에서 전체 Java 테스트를 수행한 뒤 정리
./scripts/test-analytics.sh

# 기존 파일 집계기 회귀 테스트
python3 -m unittest discover -s scripts -p 'test_*.py'
```

일반 `./gradlew :llm-gateway-api:test`는 별도 DB·Redis가 없으면 해당 통합 테스트를 건너뜁니다. 저장·조회·SSO 코드를 변경한 경우 위 테스트 실행기를 함께 실행해야 합니다. 서명된 테스트 OIDC 서버와 실제 Redis를 사용하며, 실제 공급자 모델 API를 호출하지 않습니다.
