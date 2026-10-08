# Codex native OTel correlation: 2026-10-08

## 범위와 판정

- 확인 버전: `codex-cli 0.160.0` (`codex --version` 재확인).
- 공식 문서 3개, 설치 CLI 공개 help, 기존 sanitized 검증 자료만 확인했다. 새 추론, 개인 대화/인증정보 읽기, 개인 설정 변경, 원격 전송은 하지 않았다.
- **native OTLP/HTTP JSON 로그 직접 전송은 공식 지원이다. 현재 근거로 native logs만의 정확한 질문별 사용량은 보장할 수 없다.** 이는 모든 버전/실행에서 불가능하다는 판정이 아니라, 확인한 capture와 공개 문서의 보장 범위다.
- optional traces exporter도 공식 설정으로 지원된다. 그러나 설정 지원은 turn/request/response 연결이 실제로 포함된다는 증거가 아니다. 이번 범위에서 traces wire payload와 사용량 대조는 검증하지 않았다.
- actor process topology는 추정하지 않았다. app-server protocol과 native OTLP는 별도 수집 surface다.

## 공식 문서 근거

2026-10-08에 실제 연 페이지:

1. [Advanced Configuration](https://learn.chatgpt.com/docs/config-file/config-advanced): native OTel logs에는 conversation metadata, API/SSE/WebSocket 이벤트가 있고 `response.completed`에 토큰 수가 기록된다. exporter는 비동기 배치 및 shutdown flush를 사용한다. 사용자 설정은 `CODEX_HOME` 아래에 있으며 기본값은 `~/.codex`; 프로젝트 `.codex/config.toml`의 `otel`은 무시한다. 이 설명은 각 완료 이벤트에 turn/request/response ID가 포함된다는 계약이나 완료 이벤트를 합산하면 final-turn usage와 같다는 보장을 명시하지 않는다.
2. [Configuration Reference](https://learn.chatgpt.com/docs/config-file/config-reference): `otel.exporter`와 `otel.trace_exporter`를 별도로 설정하며 `otlp-http`/`otlp-grpc`, endpoint/headers/TLS를 지원한다. HTTP protocol은 `binary` 또는 `json`이다. 이 설정 schema는 emitted log/span의 correlation 필드 schema가 아니다.
3. [App Server](https://learn.chatgpt.com/docs/app-server): `thread/tokenUsage/updated`는 active thread usage notification이다. `turn/started`, `turn/completed`와 thread/turn primitive를 설명하고, 설치 binary의 `generate-json-schema` 출력이 해당 버전의 protocol을 표현한다고 안내한다. 이 JSON-RPC notification의 식별자를 native OTLP 로그의 필드로 간주하면 안 된다.

## 기존 0.160 capture 재평가

출처: [2026-10-07 검증 보고서](2026-10-07-subscription-telemetry.md) 및 [sanitized evidence](2026-10-07-subscription-telemetry-evidence.json). 새로운 모델 실행 없이 기존 수치와 attribute 이름만 재확인했다.

- 기존 evidence의 Codex telemetry 요약은 19개 이벤트를 포함한다.
- `conversation.id`는 기록된 attribute 이름에 있다. `turn.id`, `turn_id`, `request.id`, `request_id`, `response.id`, `response_id`는 없다.
- 두 completion의 입력/출력은 12,497/0과 29,220/5다. 단순 합산 입력 41,717은 CLI final-turn 입력 29,220와 다르다. 보조 호출/누적 경계와 각 completion의 회계 범위는 확정되지 않았다.
- token attributes는 문자열 값으로 전달됐다. 숫자 문자열을 지원하되 값 없음과 0을 구분해야 한다.
- 요약 evidence는 OTLP envelope의 `traceId`/`spanId`를 보존하지 않는다. attribute 목록에 `trace_id`/`span_id`가 없다는 사실로 envelope trace context가 없었다고 결론 내릴 수 없다.
- timestamps나 prompt 발생 순서만으로 completion을 질문에 연결하면 휴리스틱이다. 계정/세션 귀속이 있어도 질문 귀속과 합계 정확성이 자동으로 증명되지 않는다.

## 공개 schema 확인 경계

다음 명령은 추론 없이 성공했다. 출력은 로컬 verification 디렉터리에만 생성했고 개인 설정은 변경하지 않았다.

```sh
codex app-server generate-json-schema --experimental --out .omx/verification/codex-public-schema-20261008
```

- 생성된 `v2/ThreadTokenUsageUpdatedNotification.json`을 직접 읽었다: `threadId`, `turnId`, `tokenUsage`가 있다. `ThreadTokenUsage`에는 `last`, `total`, nullable `modelContextWindow`가 있고 usage breakdown에는 input/cached-input/cache-write-input/output/reasoning-output/total 정수 필드가 있다.
- 생성된 `v2/ThreadReadResponse.json`의 `Turn` property는 `completedAt`, `durationMs`, `error`, `id`, `items`, `itemsView`, `startedAt`, `status`이며 usage property가 없다.
- 해당 명령은 experimental **app-server protocol**용 schema다. CLI 패키지에서 native OTLP emitted-event 공개 schema는 찾지 못했다. app-server notification의 필드가 존재해도 이를 native logs에 투영할 수 없다. `last`/`total`의 회계 범위를 schema 이름만으로 per-response/per-turn이라고 단정하지 않는다.

## backend DTO 권고: 보존과 미귀속 처리

아래는 구현 권고이며 공식 vendor 계약이 아니다.

| 필드 | 의미 |
| --- | --- |
| `source`, `signal`, `event_name`, `event_kind`, `source_version` | vendor/native surface, log 또는 trace, 원래 이벤트 종류, CLI 버전 |
| `event_time`, `observed_time`, `received_at` | 원본/관측/수신 시간을 구분; 수신 순서를 실행 순서로 간주하지 않음 |
| `account_ref`, `session_id` | 수집된 계정/세션 식별자; 없으면 null. Gateway 수집 credential 자체를 vendor account로 단정하지 않음 |
| `turn_id`, `request_id`, `response_id` | 실제 수신한 식별자만 보존; 시간 기반 추정 ID를 vendor ID로 저장하지 않음 |
| `trace_id`, `span_id`, `parent_span_id` | OTLP envelope와 span 관계를 별도로 보존; 있더라도 turn 연결 보장이 아님 |
| `input_tokens`, `cached_input_tokens`, `cache_write_input_tokens`, `output_tokens`, `reasoning_output_tokens`, `tool_tokens` | nullable 정수. 누락=unknown, 0=reported zero. cached/reasoning 값의 포함 관계 검증 전 재합산 금지 |
| `usage_scope`, `attribution_status`, `attribution_method` | `unknown`/`response`/`turn`/`session` 범위, `unattributed`/`correlated`/`verified` 상태, 연결 근거를 구분 |
| `provider_attributes`, `resource_attributes`, `record_fingerprint` | 원래 필드 및 replay 진단 정보. fingerprint는 provider request ID나 exact-once 증거가 아님 |
| `api_equivalent_cost`, `pricing_version`, `billing_mode` | 검증된 회계 범위의 환산 추정과 요금표 기준; 실제 구독 청구액과 구분 |

P0 수신기는 completion 원본을 보존하고 turn 연결이 불명확한 usage를 **미귀속**으로 처리해야 한다. 명시 식별자와 범위 검증 없이 question total이나 정확 비용으로 승격하지 않는다. traces는 수신·연결·토큰 reconciliation 증거가 확보되기 전까지 optional evidence surface다. 기존 기록은 로그 export의 localhost 검증이며 원격 HTTPS·인증·재시도·전달 완전성 검증과 구분한다.
