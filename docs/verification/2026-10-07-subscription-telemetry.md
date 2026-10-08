# 구독 기반 Claude / Codex 모니터링 검증

검증일: 2026-10-07 (Asia/Seoul)

## 결론

공식 CLI에서 구독 인증을 유지하면서 사용자 질문, 세션 식별자, 토큰 사용량을 별도 OTLP HTTP/JSON 수집기로 전송하는 동작을 실제로 확인했다. 모델 API의 base URL을 바꿀 필요는 없었다.

현재 Gateway가 이 정보를 모두 저장한다는 뜻은 아니다. 같은 실제 전송 기록을 격리된 Gateway와 임시 PostgreSQL로 재전송하자 Claude 사용량만 저장됐고, Claude 질문/질문 ID/계정 ID와 Codex 데이터는 저장되지 않았다.

## 실제 실행 결과

Claude에 한 번, Codex에 초기 확인 한 번과 주소 설정을 제외한 재검증 한 번, 다음 질문을 전달했다. 아래 Codex 수치는 마지막 재검증 기준이다.

> Telemetry verification: reply with exactly OK. Do not use tools.

| 항목 | Claude Code | Codex CLI |
|---|---|---|
| 설치 버전 | 2.1.285 | 0.160.0 |
| 인증 | claude.ai / Max | Chatgpt |
| 응답 | OK, 성공 | OK, 성공 |
| 모델 | claude-haiku-4-5-20251001 | gpt-6.1-sol |
| 질문 본문 전송 | user_prompt 이벤트에서 확인 | codex.user_prompt 이벤트에서 확인 |
| 세션 식별자 | session.id | conversation.id |
| 계정 식별 속성 | user.account_id 및 user.account_uuid 포함 | user.account_id 포함 |
| 질문-호출 연결 | user_prompt와 api_request의 prompt.id 일치 | 이번 기록에는 질문/턴 ID가 없었음; 추가 검증 필요 |
| 사용량 | 입력 431, 출력 126, 캐시 0, 합계 557 | CLI turn.completed: 입력 29,220, 출력 5, 합계 29,225; 입력 중 캐시 12,288 |
| 비용 | CLI와 OTLP 모두 API 단가 환산액 $0.001061 | 이번 OTLP 기록에는 비용 속성 없음 |

Codex 입력에는 CLI가 제공한 지침과 도구 문맥 등이 포함된다. 짧은 사용자 질문 자체의 길이를 뜻하지 않는다. 캐시 입력은 입력 토큰의 부분집합이므로 별도로 더하지 않는다.

Codex OTLP에서는 response.completed 이벤트가 두 개 발생했다. 설치 버전은 input_token_count, output_token_count, cached_token_count, cache_write_token_count, reasoning_token_count 등의 필드를 문자열 값으로 전송했다. 입력/출력 값은 각각 12,497/0과 29,220/5였다. 이를 단순 합산한 입력 41,717는 CLI 턴 사용량 29,220와 다르다. 첫 이벤트의 처리 의미와 누적/보조 호출 경계는 이번 검증으로 확정하지 않았다. 따라서 OTel 완료 이벤트를 무조건 더해 질문 비용을 확정하는 구현은 검증되지 않았다. 이 결과는 설치된 버전의 한 질문 실행에 한정된다.

## 현재 Gateway로 재전송한 결과

기존 서비스 대신 임시 포트의 최신 bootJar와 신규 일회용 PostgreSQL 컨테이너를 사용했다. 로컬 수집기가 수신한 실제 OTLP 원본을 그대로 전송했다. 컨테이너와 격리된 서버는 검증 후 종료했다.

| 대상 | HTTP 응답 | DB 추가 사용량 | 질문 본문 |
|---|---|---:|---|
| Claude 기록 2배치 | 모두 200 | 1건 | 저장 0건 |
| Codex 기록 6배치 | 모두 200 | 0건 | 저장 0건 |

Claude 저장 레코드는 source=claude_code, billing_mode=subscription_declared, total_tokens=557, cost_usd=null, api_equivalent_cost_usd=0.001061이었다. session_id는 저장됐지만 prompt.id와 user.account_id는 저장되지 않았다. 관리자 /admin/api/requests 조회는 200, total=1이었다.

현재 TelemetryNormalizer는 api_request와 api_error만 저장하고 나머지 이벤트를 무시한다. Codex 이벤트를 처리하는 어댑터가 없다. HTTP 200은 전송 수락을 의미할 뿐, 원하는 데이터가 DB에 저장됐다는 증거로 사용할 수 없다.

## 실제로 적용한 설정

전역 설정 파일을 편집하지 않고 이번 프로세스에만 아래 값을 적용했다. 최종 검증에서는 기존 공급자 API 키 및 별도 모델 주소 환경변수를 자식 프로세스에서 제거했고 구독 인증 모드를 확인했다. 수집 토큰은 모델 인증 토큰과 별도로 생성했다.

Claude의 사용자 settings.json env에 해당하는 값:

```json
{
  "env": {
    "CLAUDE_CODE_ENABLE_TELEMETRY": "1",
    "OTEL_LOGS_EXPORTER": "otlp",
    "OTEL_METRICS_EXPORTER": "none",
    "OTEL_TRACES_EXPORTER": "none",
    "OTEL_EXPORTER_OTLP_LOGS_PROTOCOL": "http/json",
    "OTEL_EXPORTER_OTLP_LOGS_ENDPOINT": "http://127.0.0.1:<collector-port>/claude/v1/logs",
    "OTEL_EXPORTER_OTLP_LOGS_HEADERS": "Authorization=Bearer <collector-token>",
    "OTEL_EXPORTER_OTLP_LOGS_COMPRESSION": "none",
    "OTEL_LOG_USER_PROMPTS": "1",
    "OTEL_LOG_ASSISTANT_RESPONSES": "0",
    "OTEL_LOG_TOOL_DETAILS": "0",
    "OTEL_LOG_RAW_API_BODIES": "0"
  }
}
```

Codex config.toml에 해당하는 값:

```toml
[otel]
log_user_prompt = true

[otel.exporter.otlp-http]
endpoint = "http://127.0.0.1:<collector-port>/codex/v1/logs"
protocol = "json"
headers = { Authorization = "Bearer <collector-token>" }
```

Codex에서는 동일한 구조를 -c 실행 옵션의 TOML inline table로 지정했다. ChatGPT 인증을 강제하고 내장 openai 공급자를 선택했으며 모델 API 주소는 덮어쓰지 않았다. 이번 검증은 CLI에 한정되며 App/IDE 설정 전파는 확인하지 않았다.

## 재현 시 발견한 문제

Codex의 --strict-config 사전 검사는 기존 사용자 설정의 features.child_agents_md를 설치 CLI가 알 수 없다는 이유로 실패했다. 기존 설정을 수정하지 않고 strict 검사를 제외한 일반 실행으로 검증을 완료했다. Codex 초기 확인 실행에는 기존 OPENAI_BASE_URL 환경변수가 남아 있었다. 해당 변수와 CODEX_API_KEY, OPENAI_API_KEY를 자식 프로세스에서 제외한 재검증도 성공했다. 사용자 config.toml에는 openai_base_url 및 chatgpt_base_url 설정이 없었다. 재검증 이벤트의 auth_mode=Chatgpt, API 키 환경변수 부재를 확인했다. Claude 성공 요청 1회와 Codex 성공 요청 2회가 발생했다. 비용은 공급자 청구서와 대조하지 않았다.

## 남은 구현과 검증

- 수집 토큰과 관리 계정의 연결, 수신한 계정 식별자의 검증/저장 정책
- Claude user_prompt와 prompt.id 저장 및 질문별 요청 합산
- Codex 이벤트 정규화, 세션/질문 연결, 토큰 집계 기준 확정
- 계정 > 세션 > 질문 > 사용량/환산 비용 화면
- 여러 질문, 도구 실행, 병렬 에이전트, 재개/분기, 재전송의 중복 방지
- 과거 대화 가져오기, 실제 청구 금액, 구독 잔여 한도는 이번 검증 범위 밖

공급자 계정 ID, 이메일, 실제 수집 토큰, 원본 OTLP는 이 문서에 포함하지 않았다. evidence.json에는 질문/세션 ID를 해시한 요약과 실제 저장 결과를 담았다.

## 근거

- [Claude Code Monitoring](https://code.claude.com/docs/en/monitoring-usage)
- [Codex Advanced Configuration / Observability](https://learn.chatgpt.com/docs/config-file/config-advanced#observability-and-telemetry)
- llm-gateway-api/src/main/java/dev/rojae/llmgateway/analytics/TelemetryNormalizer.java
- llm-gateway-api/src/main/java/dev/rojae/llmgateway/analytics/TelemetryController.java
- 2026-10-07-subscription-telemetry-evidence.json
