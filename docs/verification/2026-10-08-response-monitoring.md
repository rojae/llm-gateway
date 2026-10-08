# 질문·답변 모니터링 검증

Claude Code 2.1.293과 Codex CLI 0.161.0의 실제 전송 형식을 확인하고, 답변을 저장·조회하는 경로를 추가했다. 검증은 localhost 환경에서 수행하며 실제 원격 HTTPS 운영 환경의 전달 성공을 대신하지 않는다.

## 데이터 경로와 경계

- Claude: native `claude_code.assistant_response`의 `response`를 보관한다. `session.id + prompt.id`로 질문에 연결하며 `message.uuid`로 중복을 제거한다. 같은 질문의 여러 응답을 순서대로 표시한다. prompt ID가 없으면 세션의 미연결 답변으로 표시한다.
- Codex: native OTel에는 정확한 턴 연결 식별자와 답변 본문이 없어 완료 알림 어댑터를 사용한다. `agent-turn-complete`의 질문·최종 답변과 thread/turn ID를 `codex.turn_response`로 보낸다. 기존 notify 명령에는 원래 JSON argument를 그대로 전달한다.
- Codex exporter의 `X-LLM-Gateway-Codex-Question-Source: notify`는 native 질문을 관측 이벤트로만 남긴다. 완료된 턴의 질문만 목록을 구성하며, 중단된 턴·중간 답변·생각 과정·도구 출력은 이 경로에서 수집하지 않는다.
- 답변은 토큰·비용을 추가 합산하지 않는다. 기존 Codex 사용량 관측의 질문별 귀속 한계는 그대로 표시한다. 본문에서 HTML을 실행하지 않는다.
- 본문은 별도 테이블에 저장하고 `content:read`와 같은 트랜잭션의 조회 감사를 요구한다. viewer에는 본문을 반환하지 않는다. 본문 삭제 후 같은 이벤트를 재전송해도 복원되지 않는다. 새 메시지는 자신의 수신 시각을 기준으로 보관기간을 계산한다.
- V8 migration을 추가했으며 기존 migration과 이전 모델 단가 수정은 유지했다. 과거 대화는 가져오지 않는다.

## 자동 검증

검증 명령:

```sh
DOCKER_CONTEXT=colima-llm-gateway ./scripts/test-analytics.sh
/opt/homebrew/bin/python3 -m unittest discover -s scripts -p 'test_*.py'
npm --prefix llm-gateway-admin run typecheck
PLAYWRIGHT_MODULE=/path/to/playwright/index.mjs node llm-gateway-admin/scripts/monitoring-responses.browser.mjs
```

프런트 프로덕션 빌드는 실행 중인 개발 서버와 `.next`를 공유하지 않는 복사본에서 수행한다. 브라우저 fixture는 합성 인증·API 응답을 사용하며 실제 대화나 구독료를 변경하지 않는다. 기존 `monitoring-costs-adversarial.browser.js`도 함께 검증한다.

주요 검증 범위:

- 응답 선도착, 복수 응답, 재전송, 세션·소유자·tenant 격리와 DB 외래키 제약.
- 잘못된 OTLP 형식, 빈 본문·가림 표시, surrogate pair와 길이 제한, native/notify 모드 및 잘못된 헤더.
- viewer 본문 차단, 감사 실패 시 본문 미반환, 본문·metadata 보관기간 및 삭제 후 재전송.
- 원래 알림 인자 보존, 수집 실패 시 알림 유지, 오프라인 queue, partialSuccess·영구 오류, 동시 실행과 용량 제한.
- 질문 상세의 복수 답변, 세션 답변 탭, 미연결·권한 부족·삭제 상태, 안전한 HTML 텍스트 표시, 390px 화면.
- 답변 revision이 그대로일 때 본문을 반복 조회하지 않고, 변경됐을 때 열린 답변을 갱신.

독립 리뷰에서 선택한 세션이 목록 페이지 밖으로 밀려났을 때 갱신이 멈추는 경우, 잘못된 ACK의 숫자 문자열, atomic queue 재작성 중의 임시 디스크 사용량을 추가로 확인했다. 해당 경로는 회귀 테스트로 고정한다.

개인 수집 키, 설정 백업, 실제 CLI 출력과 DB 백업은 공개 문서에 포함하지 않고 ignored `var/verification/`에 보관한다. 설정 방법과 중단·재전송 방법은 [원격 모니터링 설정](../remote-monitoring.md#codex-최종-답변-어댑터)에 정리했다.

## 로컬 실행 결과

2026-10-08에 V8 migration이 성공한 로컬 서버에서 다음을 확인했다.

- Claude와 Codex의 새 합성 질문을 실제 구독 로그인으로 각각 실행했다. CLI 결과와 Gateway 질문·답변 본문이 각각 정확히 일치했다. 각 세션은 질문 1개·답변 1개이며 연결된 question ID도 동일했다.
- Codex native `user_prompt` 관측은 남고, 완료 알림이 만든 질문만 1개 표시됐다. 답변 이벤트에는 토큰 사용량이 추가되지 않았다.
- 실제 관리자 브라우저에서 두 CLI의 질문 상세와 세션 답변 탭을 열어 같은 본문을 확인했다. 브라우저 오류는 없었다.
- 서버에 닿지 않는 endpoint로 합성 완료 알림을 보내 pending queue가 남는 것을 확인했다. 실제 로컬 Gateway로 endpoint를 복원하고 `--flush`하면 queue가 제거됐으며, 같은 턴을 재전송해도 질문·답변은 각 1개였다. queue에 수집 키가 없는 것도 확인했다.
- Claude는 응답 수집 flag만 바꾸고, Codex는 기존 notify 배열을 sidecar에 그대로 보관한 뒤 어댑터 체인과 exporter 모드 헤더를 적용했다. 나머지 Codex 설정은 의미상 동일하며 hooks 파일은 byte 단위로 동일하다. 수집 키·sidecar·설정은 0600, spool은 0700이다. 기존 설정과 DB 백업을 유지한다.

전체 Java/실제 PostgreSQL·Redis 검증은 **296개 테스트 / 43 suites**, Python은 **55개 테스트**에서 실패·오류·skip 없이 통과했다. 프런트 타입 검사와 격리된 프로덕션 빌드도 통과했다. Python의 기존 Claude 테스트에서는 HTTPError 307 ResourceWarning이 남아 있으나 실패하지 않았다.

최종 브라우저 회귀는 응답 **15개**와 기존 비용 **6개**가 통과했다. 세션이 목록 페이지 밖으로 이동한 경우, 질문 자체가 보관 정리로 사라진 경우, metadata 조회 실패·지연·일시 중지와 페이지 이동을 추가 검증했다. 성공한 최신 metadata에서 선택한 질문이 사라지면 질문과 답변 본문을 함께 숨긴다.
