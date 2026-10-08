# 구독 CLI의 원격 직접 전송

Claude Code와 Codex가 공급자 구독 로그인을 유지하면서 내장 OpenTelemetry로 Gateway에 이벤트를 보냅니다. PC에 수집기·상주 데몬을 설치하지 않습니다. `base_url`, 모델 공급자, OAuth 로그인, 기존 hooks와 statusline은 그대로 두고 아래 telemetry 항목만 병합합니다.

```text
Claude Code / Codex ── 구독 모델 요청 ──→ 공급자
        └── 내장 OTLP HTTPS/JSON ──→ Gateway ──→ PostgreSQL ──→ 계정 / 세션 / 질문
```

## 수집 키 발급

1. 관리자 화면의 수집 키 관리에서 실제 사용자를 구분할 표시명으로 계정을 등록합니다. 이 계정은 Gateway의 관측 소유자이며 공급자 결제 계정 인증은 아닙니다.
2. 수집 키 화면에서 해당 계정을 선택하고 개인별 키를 발급합니다. `createdBy`는 발급자이고 `ownerAccountId`는 실제 수집 대상입니다. 같은 키를 여러 사람이 공유하면 사람을 구분할 수 없습니다.
3. 키는 발급 직후에만 원문을 보여 줍니다. 사용자 설정의 해당 항목에만 보관하고 저장소·URL·보고서에 넣지 않습니다. 같은 계정과 registration으로 키를 교체해도 같은 외부 세션을 다시 만들지 않습니다.
4. 아래 `gateway.example.com`을 배포된 HTTPS 주소로 바꾸고 `<개인별 수집 키>`에 발급 값을 넣습니다. 화면이 제시하는 설정도 기존 파일에 병합합니다.

소유자가 없는 기존 키는 Claude 사용량만 기존 요청 원장에 저장합니다. 기존 API 소비자를 위해 collector 발급 API의 owner 생략도 이 호환 모드로 남깁니다. 그런 키로 질문·Codex 이벤트를 보내면 `partialSuccess`의 `account_not_linked`로 거절합니다. 신규 monitoring 연결에는 계정이 연결된 키를 사용하세요. 관리자 화면에서는 owner 선택이 필수입니다.

## Claude Code

사용자 `~/.claude/settings.json`의 기존 `env`에 병합합니다. 조직 관리자는 공식 managed settings로 배포할 수 있습니다. 저장소 `.claude/settings.json`의 exporter 설정은 CLI가 무시합니다.

```json
{
  "env": {
    "CLAUDE_CODE_ENABLE_TELEMETRY": "1",
    "OTEL_LOGS_EXPORTER": "otlp",
    "OTEL_METRICS_EXPORTER": "none",
    "OTEL_TRACES_EXPORTER": "none",
    "OTEL_EXPORTER_OTLP_LOGS_PROTOCOL": "http/json",
    "OTEL_EXPORTER_OTLP_LOGS_ENDPOINT": "https://gateway.example.com/telemetry/v1/logs",
    "OTEL_EXPORTER_OTLP_LOGS_HEADERS": "Authorization=Bearer <개인별 수집 키>",
    "OTEL_EXPORTER_OTLP_LOGS_COMPRESSION": "none",
    "OTEL_LOGS_EXPORT_INTERVAL": "1000",
    "OTEL_LOG_USER_PROMPTS": "1",
    "OTEL_LOG_ASSISTANT_RESPONSES": "0",
    "OTEL_LOG_TOOL_DETAILS": "0",
    "OTEL_LOG_RAW_API_BODIES": "0"
  }
}
```

설정 후 CLI를 재시작하고 `/status`에서 기존 구독 인증을 확인합니다. `OTEL_LOG_USER_PROMPTS=1`은 이후 질문 텍스트를 원격 서버로 보내는 설정입니다. 본문 수집을 끄려면 `0`으로 바꾸세요. 이미 저장된 본문은 서버 보관 정책에 따릅니다. managed settings나 실행 환경에 같은 변수가 있으면 공식 설정 우선순위를 확인합니다.

## Codex

실제 `$CODEX_HOME/config.toml`에 병합합니다. `CODEX_HOME`을 지정하지 않았으면 기본 위치는 `~/.codex/config.toml`입니다. 실행 관리 도구가 별도 home을 지정했다면 그 위치에도 적용합니다. Orca는 `~/.codex/config.toml`에서 실행용 home을 다시 생성하므로 **원본 `~/.codex/config.toml`에도 같은 `otel` 설정을 병합**해야 유지됩니다. 실행용 파일만 수정하면 이후 동기화에서 사라질 수 있습니다. 기존 공급자·hooks 등은 보존하고, 두 설정에 동일한 수집 주소와 개인별 키를 사용합니다. 프로젝트 `.codex/config.toml`의 `otel`은 적용되지 않습니다.

```toml
[otel]
log_user_prompt = true

[otel.exporter.otlp-http]
endpoint = "https://gateway.example.com/telemetry/v1/logs"
protocol = "json"
headers = { Authorization = "Bearer <개인별 수집 키>" }
```

기존 `[otel]`이 있으면 두 번 선언하지 말고 항목을 합칩니다. CLI를 재시작하고 기존 ChatGPT 로그인 상태를 확인합니다. 본문 수집을 끄려면 `log_user_prompt = false`로 바꿉니다. 현재 수신기는 logs만 지원하므로 trace exporter를 이 endpoint에 연결하지 않습니다.

## 저장 결과 확인

첫 질문을 보낸 뒤 관리자 화면에서 선택한 계정의 마지막 수신 시각, 세션, 질문을 확인합니다. HTTP 200만으로 설정 완료를 판정하지 않습니다. OTLP `partialSuccess`와 거절·미지원 상태를 함께 확인하세요. 화면은 활성 탭에서 2초 주기로 새 기록을 조회합니다. CLI 자체의 배치 전송 시간은 이 주기와 별개입니다.

| 데이터 | 표시 기준 |
| --- | --- |
| Claude 질문 사용량 | 실제 `session.id + prompt.id`와 요청 identity로 연결된 관측 호출만 집계 |
| Codex 질문 사용량 | native 로그의 질문 귀속이 확인되지 않으면 미확정/null; 완료 이벤트는 세션의 미귀속 관측값으로 제공 |
| API 환산액 | 확인된 원천과 단가의 추정값; 실제 구독 청구액·잔여 한도와 별개 |
| 구독료 배분액 | 계정·월별로 입력한 금액을 해당 월의 기록된 질문 수로 나눈 관리용 비용 |
| 질문 본문 | 별도 저장, `content:read` 권한과 조회 감사 필요; 목록·일반 이벤트에는 미포함 |
| 과거 대화 | 설정 이후 수신된 이벤트만 저장; 과거 로컬 대화 자동 업로드 없음 |

공급자 계정 속성이 도착하면 원문을 저장하지 않고 조직·owner 범위의 해시와 `관측됨/미확인/충돌` 상태만 표시합니다. 같은 세션의 관측 값이 달라도 수집 키에 연결된 owner는 바뀌지 않습니다. 공급자 계정 로그인이나 실제 사람을 검증했다는 뜻은 아닙니다.

Codex 0.160.0의 기존 실측에서 완료 이벤트 입력 12,497와 29,220의 합계는 CLI 최종 턴 입력 29,220와 달랐습니다. 따라서 41,717을 질문 총량으로 표시하지 않습니다. [상관관계 검증 범위](verification/2026-10-08-native-codex-correlation.md)를 참고하세요.

## 비용 산정

구독 모니터링에서 계정을 선택하면 **비용 산정**을 열 수 있습니다. 두 금액은 목적과 계산 기준이 다르며 합산하지 않습니다.

- **API 환산액:** 개별 토큰 관측에 모델 단가를 적용한 참고값입니다. 아래 정확한 모델 ID에 2026-10-08에 확인한 공식 Standard API 단가를 적용합니다. Claude가 직접 보고한 환산액은 `client_reported`로 구분합니다. 단가가 없는 모델은 0 대신 `모델 단가 미등록`으로 표시하며 다른 모델의 단가·출처를 대신 사용하지 않습니다.
- **구독료 배분액:** 산정 월과 USD/KRW 금액을 저장하면, 선택한 계정의 해당 월에 보관된 질문 전체를 분모로 배분합니다. 세션에는 그 세션의 질문 수만큼 배분합니다. 날짜·CLI 조회 필터는 분모를 줄이지 않으며 산정 시간대는 화면에 표시합니다. 이는 기록된 활동에 비용을 나누는 규칙이며, 공급자의 질문별 실제 청구 내역을 뜻하지 않습니다.

Codex의 세션 화면에는 산정 월 **최근 응답 관측 1건**의 환산액을 표시합니다. 이벤트 첫 페이지의 마지막 값을 사용하는 방식이 아니라 서버가 해당 월 전체 기록에서 최근 값을 조회합니다. 개별 이벤트에도 환산액과 계산 기준을 표시합니다. 귀속·누적 여부를 알 수 없는 이벤트들을 더해 질문 또는 세션 전체 사용액으로 만들지 않습니다.

| 모델 / 공식 출처 | 일반 입력 | 캐시 읽기 | 캐시 쓰기 | 출력 |
|---|---:|---:|---:|---:|
| [gpt-6.1-sol](https://developers.openai.com/api/docs/models/gpt-6.1-sol) | $2.00 | $0.10 | $2.50 | $10.00 |
| [gpt-6-astra](https://developers.openai.com/api/docs/models/gpt-6-astra) | $10.00 | $1.00 | $12.50 | $50.00 |
| [gpt-5.6-luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna) | $0.20 | $0.02 | $0.25 | $1.20 |

단가는 100만 토큰당 USD입니다. Codex 환산식은 `(입력 − 캐시 읽기 − 캐시 쓰기) × 일반 입력 단가 + 캐시 읽기 × 읽기 단가 + 캐시 쓰기 × 쓰기 단가 + 출력 × 출력 단가`를 100만으로 나눕니다. 위 모델들은 총 입력이 272K를 초과하면 전체 요청의 일반 입력·캐시 읽기·캐시 쓰기에 2배, 출력에 1.5배 단가를 적용합니다. 캐시 포함 관계는 API 사용량 기준을 적용한 가정이며 미수신 캐시는 0으로 가정합니다. Fast·지역 할증·도구 비용은 자동 반영하지 않습니다. [공식 구독 안내](https://learn.chatgpt.com/docs/pricing)도 API 토큰 가격과 포함된 구독 사용을 구분합니다.

금액은 **월마다 별도로 저장**되며 구독 로그인으로 요금제를 추정하지 않습니다. 빈 금액으로 저장하면 해당 월 설정을 해제합니다. 미설정은 `null`, 무료 구독료 0은 실제 0이며, 배분할 질문이 없으면 배분액은 `null`입니다. 수신·보관 삭제로 질문 수가 바뀌면 배분액도 달라집니다. 세금·다른 제품 사용·추가 결제·환율은 자동으로 추정하지 않습니다.

비용 조회는 `usage:read`, 구독료 변경은 관리자 권한 `collectors:manage`가 필요합니다. 변경은 감사 기록 `monitoring.cost.configure`와 같은 트랜잭션으로 저장합니다. [UI·비용 검증 보고서](verification/2026-10-08-admin-ux-costs.md)와 [세밀 검증](verification/2026-10-08-costs-adversarial.md)에 실제 값과 검증 범위를 기록했습니다.

## 서버 설정과 보관

기존 PostgreSQL analytics와 telemetry를 켭니다. 조직 운영은 [SSO](sso.md)와 개인별 DB 수집 키를 사용합니다. Enterprise에서는 공용 static 수집 토큰을 사용하지 않습니다. [Kubernetes 예시](../deploy/enterprise/gateway.yaml)는 HTTPS `/telemetry/v1/logs`를 API로 라우팅합니다. nginx를 사용하면 [README의 수집 경로](../README.md#원격-연결-https와-sse)를 추가합니다. Authorization 헤더를 전달하고 프록시 access log에 기록하지 않습니다.

| 설정 | 역할 |
| --- | --- |
| `GW_ANALYTICS_ENABLED=true` | DB 저장·조회 활성화 |
| `GW_TELEMETRY_ENABLED=true` | OTLP logs 수신 활성화 |
| `GW_ANALYTICS_REQUEST_RETENTION_DAYS` | 기존 요청과 새 monitoring 이벤트·질문 metadata의 보관 기간 |
| `GW_ANALYTICS_CONTENT_RETENTION_DAYS` | 요청/질문 본문 보관 기간 |

보관 기간 `0`은 자동 삭제를 끕니다. payload 삭제 뒤 재전송으로 내용이 되살아나지 않도록 identity/purge marker는 남깁니다. 그 marker와 계정·수집 키·감사 기록은 별도 운영 용량 정책이 필요합니다. V6는 monitoring 데이터, V7은 계정·월별 구독료 설정을 추가합니다. 이전 migration을 수정하지 않습니다. DB 준비·monitoring 보관 정리는 파일 로그 수집 성공 여부와 별도로 수행합니다.

지원 전송은 비압축 OTLP HTTP/JSON logs입니다. gRPC·Protobuf·gzip·metrics·traces 수신은 제공하지 않습니다. 기본 한도는 2MiB/배치 1000 records입니다. 정상 저장은 DB commit 뒤 응답하며 DB 장애는 재시도 가능한 실패로 반환합니다. native exporter의 강제 종료·장기 오프라인 유실을 복구하는 로컬 큐는 제공하지 않습니다.

## 되돌리기와 검증 경계

CLI 설정에서 이번에 추가한 telemetry 항목만 제거하고 재시작하면 전송을 중단합니다. 키를 폐기하면 기존 설정으로 다시 전송해도 인증이 거절됩니다. 이전 서버 바이너리로 되돌리면 새 monitoring 화면·계정 연결 기능을 사용하지 못하므로 기존 키의 호환 경로만 사용합니다. 적용된 V6/V7을 수동으로 삭제하거나 기존 migration checksum을 바꾸지 않습니다.

직접 exporter 지원과 실제 운영 서버 성공은 구분합니다. 현재 버전별 실제 localhost capture는 [기존 검증 보고서](verification/2026-10-07-subscription-telemetry.md)에 있으며, 실제 원격 HTTPS 주소에서 CLI→DB→화면 전달·지연을 확인하는 작업은 해당 환경에서 수행해야 합니다.

공식 설정: [Claude Monitoring](https://code.claude.com/docs/en/monitoring-usage), [Codex Advanced Configuration](https://learn.chatgpt.com/docs/config-file/config-advanced), [Codex Configuration Reference](https://learn.chatgpt.com/docs/config-file/config-reference).
