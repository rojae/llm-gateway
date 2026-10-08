# LLM Gateway

**Claude Code·Codex의 사용량과 비용을 조회하는 Java 게이트웨이 및 관리자 콘솔**입니다. 개인 API 키 요청을 공급자에게 전달하거나, 기존 구독 로그인에서 내보낸 OpenTelemetry를 직접 수신해 `계정 → 세션 → 질문`을 조회합니다. 관측별 API 환산액과 입력한 월 구독료의 배분액을 구분합니다.

```text
Claude Code + 개인 Anthropic 키 → /anthropic/v1/* → Anthropic API
Codex / OpenAI SDK + 개인 OpenAI 키 → /openai/v1/* → OpenAI API
                                      │
                              콘솔 요약 + 접근 로그

Claude Code / Codex 구독 로그인 → 기존 공급자
          └─ 내장 OpenTelemetry → Gateway → PostgreSQL → 관리자 콘솔
```

키의 유효성·모델 접근 권한과 청구는 각 공급자가 판단합니다. API 사용료는 키가 속한 계정/조직에 청구되며 Claude·ChatGPT 구독과 별개입니다. 게이트웨이 운영자는 전달되는 키와 요청을 처리할 수 있으므로 신뢰하는 서버에 연결하세요.

## 복제 후 시작

API 서버는 Java 21, 전체 관리자 환경은 추가로 Node.js 22·npm, Python 3, curl, 실행 중인 Docker와 Docker Compose가 필요합니다.

```bash
git clone https://github.com/rojae/llm-gateway.git
cd llm-gateway
./scripts/admin.sh
```

관리 화면은 `http://localhost:3000/admin`, API는 `127.0.0.1:8080`에서 시작합니다. 실행기가 DB와 관리자 키를 새로 만들고 필요한 패키지를 설치합니다. 로그인에는 생성된 `var/admin.env`의 `GW_ADMIN_TOKEN`을 사용합니다. 개인 API 키와 대화 기록은 저장소에 포함되지 않습니다.

구독 모니터링은 관리자에서 계정을 등록하고 수집 키를 발급한 뒤 [CLI 직접 전송 설정](docs/remote-monitoring.md)을 적용하세요. 기존 구독 로그인과 모델 주소를 유지합니다. API 프록시만 필요하면 아래 서버 실행 절차를 사용하세요. 원격 서버의 TLS·도메인·SSO 구성은 [운영 가이드](docs/operations.md)와 [SSO 가이드](docs/sso.md)를 따릅니다.

검증은 [비용 산정 세밀 검증](docs/verification/2026-10-08-costs-adversarial.md)과 [공개 준비 및 새 사본 검증](docs/verification/2026-10-08-publication.md)에 기록했습니다. GitHub Actions는 새 복제본에서 Java·DB 테스트, Python 테스트, 의존성 보안 검사, 관리자 타입 검사와 production 빌드를 실행합니다.

## 서버 실행

Java 21이 필요합니다. Gradle Wrapper는 포함되어 있습니다.

```bash
# 서버 공용 키 설정이 있다면 현재 셸에서만 해제한다.
unset GW_UPSTREAM_API_KEY GATEWAY_UPSTREAM_API_KEY
./scripts/gateway.sh
```

`unset`은 **현재 셸의 환경변수만 해제**합니다. 공급자 사이트의 API 키를 폐기하거나 다른 터미널·설정 파일을 바꾸지 않습니다. 사용자 터미널의 `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`도 위 명령으로 삭제되지 않습니다.

기본 수신 주소는 `127.0.0.1:8080`입니다. 실행 스크립트는 개인 키 파일을 읽지 않고, 개인 키 전달 모드를 명시적으로 적용합니다. 운영 프로파일은 다음처럼 전달합니다.

```bash
./scripts/gateway.sh --spring.profiles.active=prod
curl -fsS http://localhost:8080/healthz
```

## Claude Code 연결

각 사용자 터미널에서 자신의 **실제 Anthropic API 키**를 설정합니다. 아래 입력은 bash/zsh에서 화면·셸 히스토리에 키를 남기지 않습니다.

```bash
unset ANTHROPIC_AUTH_TOKEN
printf '개인 Anthropic API 키: '
read -r -s ANTHROPIC_API_KEY
printf '\n'
export ANTHROPIC_API_KEY
export ANTHROPIC_BASE_URL=http://localhost:8080/anthropic
claude
```

Claude Code의 base URL에는 `/v1`을 붙이지 않습니다. `ANTHROPIC_AUTH_TOKEN`을 해제하는 이유는 API 키와 별도 인증 토큰의 충돌을 피하기 위해서입니다. 원격에서는 HTTPS 주소를 사용합니다. 키에 허용된 모델을 선택하세요. [Claude Code 게이트웨이 연결 안내](https://code.claude.com/docs/en/llm-gateway-connect)

대화형 시작 시 **“Do you want to use this API key?”에서 Yes를 선택**해야 개인 API 키를 사용합니다. `ANTHROPIC_BASE_URL`은 전송 주소만 바꾸며 로그인 자격증명을 선택하지 않습니다. 이전에 No를 선택한 기록은 유지되어 같은 키의 승인 화면이 다시 나오지 않을 수 있습니다. 이 경우 전체 로그아웃이나 플러그인 초기화 대신 Claude 설정의 `customApiKeyResponses`에서 **현재 키에 해당하는 거절 기록만** 복구한 뒤 재시작·승인하세요. 키 원문을 설정이나 로그에 복사하지 마세요. 비대화형 `claude -p`의 성공만으로 대화형 키 승인까지 확인된 것은 아닙니다.

## OpenAI SDK·Codex 연결

사용자 터미널에서 자신의 **실제 OpenAI API 키**를 설정합니다.

```bash
printf '개인 OpenAI API 키: '
read -r -s OPENAI_API_KEY
printf '\n'
export OPENAI_API_KEY
export OPENAI_BASE_URL=http://localhost:8080/openai/v1
```

짧은 Responses 요청으로 연결을 확인할 수 있습니다. 실제 API 호출이며 비용이 발생할 수 있습니다. 모델은 자신의 계정에서 사용할 수 있어야 합니다.

```bash
curl -sS http://localhost:8080/openai/v1/responses \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"model":"gpt-4o-mini","input":"Reply only: OK","max_output_tokens":32}'
```

OpenAI SDK에는 `base_url="http://localhost:8080/openai/v1"`과 개인 키를 사용합니다. OpenAI base URL에는 `/v1`이 포함됩니다. SDK의 Chat Completions와 Responses 경로를 지원합니다.

Codex는 사용자 `~/.codex/config.toml`의 기존 설정에 다음 항목을 **병합**합니다. 기존 파일 전체를 덮어쓰지 마세요. 최상위 항목은 다른 TOML 테이블이 시작되기 전에 둡니다.

```toml
model_provider = "llm_gateway"
# model은 자신의 OpenAI API 계정에 허용된 Codex 호환 모델로 설정

[model_providers.llm_gateway]
name = "LLM Gateway (personal OpenAI key)"
base_url = "http://localhost:8080/openai/v1"
env_key = "OPENAI_API_KEY"
wire_api = "responses"
supports_websockets = false
requires_openai_auth = false
```

그 터미널에서 `codex`를 실행합니다. 이 구성은 Responses HTTP/SSE를 사용하며 WebSocket은 사용하지 않습니다. ChatGPT 로그인 대신 `OPENAI_API_KEY`를 공급자 인증에 사용합니다. 임의의 Chat Completions 모델이 Codex와 호환된다고 가정하지 마세요. [Codex 사용자 지정 공급자 설정](https://developers.openai.com/codex/config-reference/)

## 경로와 호환성

| 경로 | 업스트림 / 용도 |
|---|---|
| `POST /anthropic/v1/messages` | Anthropic 네이티브 응답·SSE |
| `POST /anthropic/v1/messages/count_tokens` | Anthropic 입력 토큰 계산 |
| `GET /anthropic/v1/models` | Anthropic 모델 조회, 쿼리 전달 |
| `POST /openai/v1/chat/completions` | OpenAI Chat Completions |
| `POST /openai/v1/responses` | OpenAI Responses·SSE |
| `GET /openai/v1/models` | OpenAI 모델 조회 |
| `GET /healthz` | 상태 확인 |
| `/docs`, `/openapi.json` | API 문서 |

기존 루트 `/v1/*` 경로도 유지하며 `GW_UPSTREAM_BASE_URL`의 단일 업스트림을 사용합니다. 기존 `./scripts/claude.sh`는 루트 경로를 Anthropic으로 고정하는 전용 실행기입니다. 새 다중 공급자 구성은 `gateway.sh`와 위 공급자별 접두사를 사용하세요.

게이트웨이는 모델·API 포맷을 변환하지 않습니다. Anthropic 호환성 헤더를 전달하고, `stream_options.include_usage`는 OpenAI Chat Completions에만 적용합니다. [Claude Code 게이트웨이 프로토콜 요구사항](https://code.claude.com/docs/en/llm-gateway-protocol)

## 로컬과 운영 로그

PostgreSQL 적재와 관리자 화면은 [LLM Gateway 어드민 가이드](docs/admin.md)를 참고하세요. 백엔드는 Gradle 모듈 [`llm-gateway-api/`](llm-gateway-api/)의 Spring Boot 앱이고, 화면은 독립된 Next.js/React 앱 [`llm-gateway-admin/`](llm-gateway-admin/)입니다. `./scripts/admin.sh`는 로컬 DB·API·화면을 함께 시작하며, 화면은 기본 `http://localhost:3000/admin`에서 열립니다. Next.js가 `/admin/api/*`, `/admin/auth/*`, `/oauth2/*`, `/login/oauth2/*` 요청을 API로 전달합니다. 기존 개인 키 프록시는 그대로 유지하며 저장 기능은 선택적으로 켭니다. 이전 로컬 DB가 있으면 [DB 이름 변경 절차](docs/admin.md#기존-로컬-postgresql-이전)를 먼저 확인하세요.

조직 운영에는 `GW_SECURITY_MODE=enterprise`와 **사내 OIDC SSO**를 사용합니다. Keycloak·Entra ID·Okta에서 검증한 조직과 역할에 따라 조회 범위를 제한하며, 브라우저 세션은 Redis에서 공유합니다. 관리자는 조직에 귀속된 수집 키를 발급·폐기하고 감사 기록을 조회합니다. [SSO 연결 가이드](docs/sso.md), [운영 배포·백업·복구](docs/operations.md), [Kubernetes 구성](deploy/enterprise/)

Claude Code·Codex **구독 로그인**은 모델 주소를 바꾸지 않고 내장 OpenTelemetry로 원격 서버에 직접 연결합니다. 관리자 화면에서 계정을 등록하고 개인별 수집 키를 발급한 뒤 CLI 사용자 설정에 HTTPS 수집 주소를 병합하세요. 별도 로컬 수집기 없이 `계정 → 세션 → 질문`을 조회합니다. Claude는 연결된 호출의 토큰·API 환산액을 표시하고, Codex는 개별 관측과 산정 월 최근 응답의 API 환산액을 표시합니다. 계정별 월 구독료를 입력하면 세션·질문에 기록된 질문 수 기준으로 배분합니다. 귀속 미확인 토큰은 질문 합계로 만들지 않으며, API 환산액과 구독료 배분액은 별도로 표시합니다. [원격 직접 전송과 비용 산정](docs/remote-monitoring.md)

각 필드의 의미와 실제 토큰·비용 계산은 [CONTENT·ACCESS 로그 읽는 법](docs/logging.md)을 참고하세요. 요청은 `REQUEST >>>`, 응답은 `RESPONSE <<<` 제목으로 구분하며 같은 `request` ID로 접근 원장과 연결합니다.

**로컬 기본값:** 콘솔에는 요청별 짧은 요약을 표시합니다. 공급자·결과·상태·토큰·비용·지연으로 요청 결과를 확인할 수 있습니다. 알 수 없는 토큰·지연은 `-`, 비용은 `unknown`으로 표시합니다. 아래는 출력 형식 예시이며 실측 결과가 아닙니다.

```text
12:00:00.123 ACCESS [local/live] openai · gpt-4o-mini | 200 success | 300.0ms
    request=example-request-1 key=example-key | tokens=15 (in=10 out=5 cache-write=0 cache-read=0) usage=reported | cost=$0.000005 firstByte=120.0ms
```

콘솔의 `request=` 값으로 상세 JSON을 찾습니다.

```bash
jq 'select(.requestId == "example-request-1")' var/access.log
```

상세 JSON의 `method`·`path`로 요청 경로를, `upstreamRequestId`로 공급자 측 요청을 연결할 수 있습니다. 공급자가 요청 ID를 보내지 않으면 해당 값은 비어 있습니다. 콘솔은 `keyId`를 짧게 표시하지만 JSON에는 전체 식별자가 남습니다. 콘솔의 `firstByte`와 JSON/집계의 `ttftMs`는 **첫 응답 바이트까지의 시간**이며 실제 첫 생성 토큰까지의 시간을 뜻하지 않습니다.

오류 요청의 콘솔 두 번째 줄에는 `error`, `reason`, `retryAfter`, `upstreamRequest`가 추가됩니다. JSON의 `upstreamErrorReason`은 `request_rate`, `input_token_rate`, `output_token_rate`, `spend_limit`, `insufficient_quota`, `rate_limit` 같은 제한된 분류값이며 접근 원장에는 오류 응답 원문을 저장하지 않습니다. DEBUG/TRACE 내용 로그에는 마스킹된 오류 메시지가 기록될 수 있습니다. `retryAfterSeconds`는 공급자의 `Retry-After`가 정수 초일 때 기록합니다. HTTP 날짜 형식은 클라이언트에 그대로 전달하지만 로그의 초 값은 미확인으로 둡니다. 근거가 없으면 값은 `null`이고 콘솔에는 `-`로 표시합니다.

`429`는 공급자가 요청을 제한했다는 응답이지 **API 키가 없다는 진단이 아닙니다**. 일반 `rate_limit`만으로 요청 수·토큰 수·지출 한도 중 어느 제한인지 확정할 수 없고, 재시도 시간도 추측하지 않습니다. 서로 다른 API 키도 같은 조직에 속하면 조직 한도를 공유할 수 있습니다. 실제 사용 중인 인증 방식과 공급자 콘솔의 한도를 함께 확인하세요.

**운영 `prod` 프로파일:** 접근 로그는 콘솔에도 구조화된 JSON으로 출력합니다. 수집기는 `requestId`와 `path`가 있는 접근 로그 레코드를 선택하고 `event=gateway.content`인 내용 레코드는 제외합니다. 일반 애플리케이션 로그와 접근 로그는 파일로도 분리합니다. 사람이 읽는 접근 요약은 2xx `INFO`, 4xx `WARN`, 5xx·업스트림 오류 `ERROR`이며, 집계용 JSONL 접근 원장은 별도 `INFO`로 유지합니다.

| 파일 | 내용 |
|---|---|
| `var/access.log` | 요청별 JSONL 사용량·메타데이터 |
| `var/application.log` | Java/Spring 진단 로그 |
| `build/test-logs/` | 테스트 실행 로그 |
| `var/demo/` | 로컬 데모 로그 |

파일은 날짜가 바뀌거나 50 MB에 도달하면 회전·압축하며, 파일 종류별로 최대 30일/총 1 GB를 유지합니다. 장기 회계는 별도 저장소에 수집하세요. `tee -a var/access.log`를 함께 쓰면 중복 집계됩니다.

접근 로그의 `provider`는 `anthropic`/`openai`이며 기존 루트 경로의 사용자 지정 업스트림은 `custom`, `outcome`은 `success`/`http_error`/`upstream_error`/`cancelled`입니다. `environment`는 실행 환경(`local`/`prod`/`test`), `traffic`은 트래픽 종류(`live`/`test`/`demo`)입니다. 로컬 실행도 실제 API를 호출하면 기본 `traffic=live`이며 **prod 여부는 실제 과금 여부와 다릅니다**. `usageAvailability`는 `reported`/`partial`/`missing`/`not_applicable`로 구분합니다. `partial`은 오류나 취소 전에 일부 사용량을 관측했지만 최종 사용량은 미확인인 경우입니다.

```bash
./scripts/usage.py var/access.log --by provider,outcome
./scripts/usage.py var/access.log --by key,model --traffic live
./scripts/usage.py var/access.log --by day,provider,key
./scripts/usage.py var/access.log --by session
./scripts/usage.py var/access.log --by class
./scripts/usage.py var/access.log --json
./scripts/usage.py var/access.log --budget 20
# .gz 파일이 있는 경우, 현재 파일과 함께 직접 읽을 수 있다.
./scripts/usage.py var/access.log var/access.log.*.gz --by provider --traffic live
```

사용량 표는 오류·취소·사용량 누락·부분 사용량을 별도로 셉니다. `--traffic live`는 명시적으로 live로 기록된 요청만 포함하며 과거 라벨 없는 로그는 제외합니다. 기본값은 입력한 모든 로그입니다. 과거 `keyId`·`provider`가 없는 레코드는 `(unknown)`으로 표시합니다.

`keyId`는 실제 전달된 인증 값(API 키 또는 Bearer 토큰)으로부터 서버가 계산한 HMAC 식별자입니다. 같은 키는 같은 그룹이고 사용자명·세션 헤더는 청구 식별자로 신뢰하지 않습니다. `GW_KEY_ID_SECRET`에 충분히 긴 서버 전용 무작위 비밀값을 고정하면 재시작 후에도 키별 집계를 유지할 수 있습니다. 생략하면 기동마다 생성되어 식별자가 달라집니다. 비밀값을 바꿔도 과거 로그 ID는 바뀌지 않습니다.

원문 API 키·인증 헤더·프롬프트 본문은 메타데이터 접근 원장(`var/access.log`)에 기록하지 않습니다. 별도 요청·응답 내용 로거의 범위는 아래와 같습니다. 세션·에이전트 헤더는 클라이언트가 제공하는 보조 정보입니다.

비용은 알려진 단가와 보고된 토큰으로 계산한 **추정치**이며 공급자 청구서가 최종 기준입니다. 캐시 토큰도 별도로 계산합니다. 미등록 단가나 사용량 누락은 무료로 간주하지 않습니다. 표의 `?`는 비용 미확인, `*`는 일부 요청 또는 일부 토큰만 계산된 합계, `-`는 계산된 비용 없음입니다. JSON의 `cost_usd`는 하위 호환을 위한 **알려진 비용 소계**이므로 `cost_complete`, `priced_requests`, `unpriced_requests`, `missing_usage_requests`, `partial_usage_requests`를 함께 확인하세요. 부분 사용량의 관측 토큰·비용도 소계에는 포함하지만 `cost_complete=false`로 표시합니다. `--budget`은 비용 비율 표시이며 예산 차단 기능이 아닙니다.

### 요청·응답 내용 로그: IntelliJ와 운영

`gateway.content` 로거는 사용량 원장과 분리되어 있습니다. **로컬 기본값은 DEBUG이므로 실제 요청·응답 텍스트가 콘솔에 나옵니다.** 운영 `prod`의 기본값은 INFO이며 내용을 기록하지 않습니다.

| `GW_CONTENT_LOG_LEVEL` | 기록 범위 |
|---|---|
| `INFO` | 요청·응답 내용 없음. 메타데이터 접근 원장은 계속 기록 |
| `DEBUG` | 최신 요청 메시지/텍스트와 응답 텍스트 |
| `TRACE` | 최대 20개 대화 이력, system/instructions, 도구 호출·결과까지 포함 |

IntelliJ **Run/Debug Configuration → Environment variables**에서 예를 들어 `GW_CONTENT_LOG_LEVEL=DEBUG;GW_CONTENT_LOG_MAX_CHARS=4096`을 설정하고 애플리케이션을 재시작합니다. 상세 대화가 필요하면 `TRACE`, 내용을 끄려면 `INFO`로 바꿉니다. `CONTENT` 출력에는 `request`, `phase=request/response`, `truncated`, `partial`이 표시됩니다. 스트리밍 응답 내용은 청크마다 출력하지 않고 종료 시 한 번 기록합니다.

요청·응답 **각 방향당** 최대 4,096자를 기록합니다(`GW_CONTENT_LOG_MAX_CHARS`, 256~65,536). SSE 이벤트는 최대 1 MiB까지 검사합니다(`GW_CONTENT_LOG_MAX_EVENT_BYTES`, 4,096~1,048,576). 너무 크거나 잘못된 이벤트는 건너뛰고 `partial`로 표시합니다. 스트리밍 내용은 호출·텍스트 블록별로 재조립하고 각각 마스킹한 뒤 합칩니다. 내용 조각 수도 방향당 64개로 제한하며 초과분은 `truncated`로 표시합니다. 따라서 이 로그는 전체 대화의 완전한 복사본이 아닙니다.

헤더는 수집하지 않습니다. 알려진 API 키·Bearer·비밀번호 필드 형태는 마스킹하고 이미지·오디오·PDF·base64·reasoning은 생략합니다. **범용 개인정보 제거/DLP 기능은 아니므로 프롬프트·소스 코드·일반 텍스트의 민감정보는 남을 수 있습니다.** 운영에서 내용을 켤 때는 이 범위를 고려하세요.

내용 파일 저장은 기본 OFF입니다. 필요한 경우 `content-log-file` 프로파일을 추가하면 `var/content.log`에 JSONL로 저장합니다. `prod`에서는 내용을 명시적으로 DEBUG/TRACE로 켜야 콘솔·파일에 출력되며, 콘솔 형식도 기본 JSON입니다.

```bash
GW_CONTENT_LOG_LEVEL=DEBUG ./scripts/gateway.sh --spring.profiles.active=prod,content-log-file
```

내용 파일은 일별/10 MB 단위로 압축 회전하며 최대 7일/100 MB를 보관합니다. `GW_CONTENT_LOG_FILE`, `GW_CONTENT_LOG_MAX_FILE_SIZE`, `GW_CONTENT_LOG_HISTORY`, `GW_CONTENT_LOG_TOTAL_CAP`으로 변경합니다. 내용 형식은 `GW_CONTENT_LOG_FORMAT=pretty/json`으로 선택할 수 있습니다. 로컬 콘솔에는 시간·레벨 접두사가 붙으므로 JSONL 수집은 내용 파일이나 `prod` 콘솔을 사용합니다. 콘솔·파일 내용 로그는 각각 큐 256개의 비동기 출력이며 큐가 가득 차면 요청을 막지 않고 로그를 버릴 수 있습니다. **진단용 최선 노력 기록이며 청구·감사 원장이 아닙니다.** 사용량 JSONL 접근 원장의 동작은 바뀌지 않습니다.

## 원격 연결: HTTPS와 SSE

같은 호스트의 nginx가 TLS를 종료하고 loopback 게이트웨이에 연결하는 예입니다. 실제 도메인·인증서 경로로 바꾸고 기존 nginx 설정에 추가합니다.

```nginx
server {
    listen 443 ssl;
    server_name gateway.example.com;
    ssl_certificate /etc/letsencrypt/live/gateway.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/gateway.example.com/privkey.pem;

    location = /telemetry/v1/logs {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header Authorization $http_authorization;
        proxy_cache off;
        client_max_body_size 2m;
        proxy_read_timeout 30s;
    }

    location ~ ^/(anthropic/v1|openai/v1|v1)/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Connection "";
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
        client_max_body_size 32m;
    }
}
```

클라이언트 주소는 `https://gateway.example.com/anthropic`, `https://gateway.example.com/openai/v1`입니다. 8080을 인터넷에 직접 공개하지 마세요. 다른 호스트/컨테이너의 프록시가 연결할 때만 `GW_BIND_ADDRESS`를 변경하고 네트워크 접근을 제한합니다. 프록시·APM도 인증 헤더와 본문을 기록하지 않도록 설정하세요. TLS 인증서 발급·갱신과 방화벽은 배포 환경에서 설정합니다.

## 설정

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `GW_PORT` | `8080` | 수신 포트 |
| `GW_BIND_ADDRESS` | `127.0.0.1` | 수신 주소 |
| `GW_ACCESS_LOG_FILE` | `var/access.log` | 접근 로그 파일 |
| `GW_APPLICATION_LOG_FILE` | `var/application.log` | 진단 로그 파일 |
| `GW_LOGS_MAX_FILE_SIZE` | `50MB` | 회전 파일 크기 |
| `GW_LOGS_HISTORY` | `30` | 보관 일수 |
| `GW_LOGS_TOTAL_SIZE_CAP` | `1GB` | 파일 종류별 보관 용량 |
| `GW_ENVIRONMENT` | `local` / prod 프로파일에서 `prod` | 환경 라벨 |
| `GW_TRAFFIC_LABEL` | `live` | 트래픽 라벨 |
| `GW_KEY_ID_SECRET` | 기동 시 무작위 | 키 ID용 서버 HMAC 비밀값 |
| `GW_OPENAI_BASE_URL` | `https://api.openai.com/v1` | OpenAI 접두사 경로 업스트림 |
| `GW_ANTHROPIC_BASE_URL` | `https://api.anthropic.com/v1` | Anthropic 접두사 경로 업스트림 |
| `GW_MAX_BODY_SIZE` | `32MB` | 요청 본문 처리 한도 |
| `GW_UPSTREAM_BASE_URL` | `https://api.openai.com/v1` | 기존 루트 `/v1/*` 업스트림 |
| `GW_UPSTREAM_API_KEY` | 비움 | 개인 키 모드에서는 비워야 함 |
| `GW_CONNECT_TIMEOUT` | `5s` | 연결 제한 시간 |
| `GW_READ_TIMEOUT` | `120s` | 업스트림 청크 사이 제한 시간 |
| `GW_INJECT_STREAM_USAGE` | `true` | Chat Completions usage 요청 |

일반 JAR의 기존 서버 공용 키(managed) 모드는 개인별 키 전달과 다릅니다. 자체 사용자 인증 없이 공유 서비스로 공개하면 서버 키가 사용될 수 있으므로 이 구성에서는 사용하지 않습니다.

## 구현과 검증

Spring WebFlux `ProxyEngine`이 요청·응답을 전달하고 `UsageSniffer`가 응답 복사본에서 토큰을 읽습니다. 클라이언트 취소는 업스트림 구독 취소로 전파됩니다. 스트리밍 비용은 헤더 확정 뒤 계산되므로 로그에서 확인합니다.

```bash
./gradlew :llm-gateway-api:test
python3 -m unittest discover -s scripts -p 'test_*.py'
bash -n scripts/gateway.sh scripts/claude.sh
```

Java 테스트는 로컬 HTTP 업스트림으로 헤더·경로·스트리밍·사용량·취소·로그를 검사합니다. Python 테스트는 과거 로그 호환, 키/공급자별 집계, gzip, 트래픽 필터, 미확인 비용과 실행기를 검사합니다. 오프라인 검증은 실제 공급자 계정 권한이나 모든 Claude Code/Codex 기능의 실서비스 호환성을 증명하지 않습니다. 실제 API 호출은 비용이 발생할 수 있습니다.

키 없는 데모는 `./scripts/demo.sh`이며 모델·사용량은 가짜 데이터입니다. 구조 설명은 [`docs/request-lifecycle.html`](docs/request-lifecycle.html), Python 참조 구현은 `reference/python/`에 있습니다.

사용자 계정의 생성·퇴사자 회수는 사내 IdP에서 관리합니다. 키별 RPM 제한과 지출 강제 한도는 제공하지 않으며, 관측 비용은 청구서 정산 원장이 아닙니다. 자체 재시도나 스트리밍 중 모델 전환을 하지 않으며, Responses WebSocket·다른 OpenAI API 경로는 지원 범위에 포함하지 않습니다.

## 라이선스

[MIT License](LICENSE)에 따라 사용·수정·재배포할 수 있습니다.
