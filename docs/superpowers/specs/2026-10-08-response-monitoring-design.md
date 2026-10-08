# 질문과 응답 본문 모니터링

## 목적과 범위

기존 구독 로그인과 비용 관측을 유지하면서 관리자가 질문에 대한 실제 답변 텍스트를 조회한다. 사용자는 로컬 Claude·Codex 설정 적용과 기능 구현을 요청했다. 이전 단가 수정은 보존한다. 과거 대화나 기존 `[REDACTED]` 기록을 자동으로 업로드하지 않는다.

Claude Code 2.1.293 실측은 `claude_code.assistant_response`의 `response`, `message.uuid`, `request_id`, `session.id`, `prompt.id`, `event.sequence`를 확인했다. 한 질문에 여러 API 응답이 있을 수 있다. 생각 과정과 도구 인자는 수집하지 않는다.

Codex 0.161.0 native OTel에는 `conversation.id`만 있고 질문/턴 ID와 답변 본문이 없다. 반면 기존 `notify`의 `agent-turn-complete`는 `thread-id`, `turn-id`, `input-messages`, `last-assistant-message`를 함께 제공한다. 완료 알림을 전달하는 작은 Python 어댑터가 질문과 최종 답변을 한 이벤트로 전송한다. 기존 알림 명령과 hooks는 보존한다. Codex의 중단되거나 아직 완료되지 않은 턴은 이 경로에서 질문/답변으로 표시되지 않는다.

## 수집 계약

- 기존 인증된 OTLP HTTP/JSON `/telemetry/v1/logs`를 사용한다.
- Claude 응답은 native `assistant_response`로 수신하고, `session.id + prompt.id`가 있을 때만 해당 질문에 연결한다. 질문보다 응답이 먼저 도착해도 같은 식별자로 정렬된다.
- `message.uuid`를 우선 식별자로 사용하고, 없으면 request ID 및 기존 이벤트 식별 정보를 이용한다. 재전송은 응답을 추가하거나 삭제된 본문을 되살리지 않는다.
- Codex 어댑터 이벤트 이름은 `codex.turn_response`다. `conversation.id`, `turn.id`, `prompt`, `response`, `event.timestamp`를 포함한다. 이는 Gateway 전용 이벤트이며 native OTel로 표기하지 않는다.
- Codex 질문 식별자는 `turn.id`다. 세션과 턴이 없거나 prompt/response가 잘못된 형식이면 거절한다. 여러 `input-messages`는 순서를 보존해 빈 줄로 구분한다. 모델은 알림에서 확인되지 않으므로 추측해서 채우지 않는다.
- Codex native exporter와 어댑터는 `X-LLM-Gateway-Codex-Question-Source: notify` 헤더를 사용한다. 이 헤더가 있는 배치의 native `codex.user_prompt`는 이벤트 관측으로만 저장하고 중복 질문을 만들지 않는다. 헤더 미지정 시 기존 동작을 유지한다. 값은 `native` 또는 `notify`만 허용하며, 인증/소유자를 변경하지 않는다.
- 질문과 답변을 시간 근접성이나 텍스트 유사성으로 연결하지 않는다. 연결 식별자가 없는 Claude 응답은 세션의 미연결 답변으로 조회한다.

## 저장·조회

새 Flyway V8 migration으로 응답 메타데이터와 보호된 응답 본문을 별도 테이블에 저장한다. 이전 migration은 수정하지 않는다. 질문/답변 식별자는 tenant, owner, source, session 범위에서 격리한다.

응답 본문 최대 길이는 65,536 UTF-16 code units이며, surrogate pair 중간에서 자르지 않는다. CLI truncation marker나 원본 길이가 제한보다 큰 경우에도 `truncated=true`로 표시한다. 빈 응답은 본문 미수집으로 표시하고, 임의 HTML은 실행하지 않는다.

`GET /admin/api/monitoring/questions/{id}`의 기존 필드는 유지하고 다음을 추가한다.

```text
responses: Response[]
responseAvailability: available | not_collected | permission_required | purged
Response = { id, questionId?, source, emittedAt, sequence?, model?, attribution,
             content: {text, truncated} | null,
             contentAvailability: available | not_collected | permission_required | purged }
```

`GET /admin/api/monitoring/sessions/{id}/responses?limit=25&offset=0`는 같은 Response의 페이지를 반환한다. 시간·sequence·ID 순서로 안정적으로 정렬하며 미연결 응답도 포함한다. 일반 계정/세션/질문/이벤트 목록과 비용 API에는 응답 본문을 넣지 않는다.

질문 목록과 상세의 question에는 본문 없는 `responseRevision`을 제공한다. 응답 추가 또는 본문 삭제로 revision이 바뀔 때만 열린 질문 상세를 다시 읽어, 변화 없는 2초 poll이 감사된 본문 조회를 반복하지 않도록 한다. 세션 metadata에도 같은 revision을 제공한다. `GET /admin/api/monitoring/sessions?sessionId={id}`의 정확한 세션 필터를 사용해 선택한 세션의 revision을 별도로 조회한다. 목록 페이지 밖으로 세션이 밀려나도 갱신이 유지되며, 성공한 metadata 조회에서 세션이 사라지면 이전 본문을 숨긴다.

본문에는 기존 `content:read` 권한과 조회 감사 정책을 그대로 적용한다. viewer에는 null 본문과 permission_required 상태만 반환한다. 교차 tenant/owner/session 참조는 거절한다. 본문 보관기간, 메타데이터 보관기간, purge marker, 재전송 중복 제거는 질문과 같은 강도를 유지한다. 응답 텍스트는 사용량이나 비용을 추가 합산하지 않는다.

## Codex 어댑터

Python 표준 라이브러리만 사용한다. Codex 설정의 endpoint와 headers를 읽고, 원래 notify 명령은 별도 0600 설정 파일에서 읽어 동일한 payload argument를 전달한다. shell interpolation은 사용하지 않는다. CLI 사용을 방해하지 않도록 전송과 기존 알림의 실패를 서로 격리하고 timeout을 제한한다.

로컬 0700 spool 디렉터리에 0600 이벤트 파일을 원자적으로 기록한다. 인증 키는 queue에 저장하지 않는다. 정상 ACK 후에만 제거하고, 오프라인/5xx는 다음 알림 또는 `--flush`에서 재시도한다. 동시 실행은 파일 잠금으로 처리하며 queue 크기와 개수를 제한한다. partialSuccess와 영구 오류는 성공으로 처리하지 않고 별도 상태로 남긴다. 임의 remote HTTP는 금지하고 loopback HTTP 또는 HTTPS만 사용한다.

## 화면

질문 상세에서 질문 아래에 답변 목록을 표시한다. 한 질문의 여러 Claude 응답은 순서대로 구분한다. 별도 답변 탭에서 세션의 응답을 확인하고 질문 미연결 상태를 명확히 표시한다. 대기/미수집, 권한 부족, 삭제, 길이 제한 상태는 빈 문자열이나 답변 없음으로 뭉개지 않는다.

## 검증

native Claude와 Codex notify synthetic payload로 계약을 고정한다. 순서 역전, 재전송, 같은 질문의 복수 응답, 다른 소유자의 같은 IDs, 잘못된/누락된 IDs, 빈 본문, Unicode/truncation, 본문 권한·감사·보관·purge 후 재전송, 비용 중복 방지, notify 체인·오프라인 queue·partialSuccess·동시 실행을 검증한다. 전체 Java+DB/Redis 테스트, Python 테스트, 프런트 타입 검사/빌드와 브라우저 확인을 수행한다. 로컬 DB를 백업한 뒤 배포하고, 각 CLI의 실제 질문·답변 쌍을 확인한다.

공식 근거: [Claude monitoring](https://code.claude.com/docs/en/monitoring-usage), [Codex notifications](https://learn.chatgpt.com/docs/config-file/config-advanced), [Codex hooks](https://learn.chatgpt.com/docs/hooks). 실측은 ignored `var/verification`에 보관한다.
