# 비용 산정 세밀 검증

2026-10-08, 로컬 API·어드민과 별도 임시 PostgreSQL/Redis에서 검증했습니다. [앞선 구현 검증](2026-10-08-admin-ux-costs.md)의 성공 결과만으로 결론 내리지 않고 경계값과 화면 상태를 재현했습니다.

## 발견한 문제와 수정

| 실패 시나리오 | 수정한 동작 |
| --- | --- |
| 질문을 연 뒤 사용량이 도착해도 기술 정보가 이전 값에 머무름 | 목록의 새 사용량을 반영하며 감사 대상 본문은 재조회하지 않음 |
| 다른 관리자의 설정 저장 후 자동 조회가 입력 중인 금액·통화를 지움 | 입력값 유지, 서버 변경 안내 및 명시적 다시 불러오기 제공 |
| 같은 계정의 다른 세션으로 이동하면 작성 중인 월 금액이 사라짐 | 계정·월 단위로 입력 상태 유지 |
| 비용 조회가 실패해도 질문 배분액이 정상처럼 남음 | 질문·비용 패널 배분액에 조회 실패 표시, 이전 질문 수와 구분 |
| 갱신 중지 중 숨겨진 탭에서 산정 월을 바꾸면 복귀 후 계산이 멈춤 | 미완료 metadata 조회만 복귀 시 실행, 자동 갱신 중지는 유지 |

독립 리뷰에서는 첫 수정이 갱신 중지 상태의 새 detail metadata보다 오래된 목록을 우선하는 회귀를 추가로 지적했습니다. 브라우저에서 재현한 뒤 조회 시각을 비교하도록 수정했습니다. 따라서 최신 명시적 조회와 후속 목록 갱신 모두 반영합니다.

실패 재현은 `admin-costs-adversarial-before.json`의 5건, 중간 리뷰 재현은 `admin-costs-adversarial-review-repro.json`의 1건입니다. 최종 같은 6개 시나리오는 모두 통과했습니다. 브라우저 금액·오류·권한 변화는 fixture이며 실제 구독료를 저장하지 않았습니다.

## 검증 결과

| 검증 | 결과 |
| --- | --- |
| 전체 Java 테스트, 임시 PostgreSQL/Redis 사용 | 275 성공, 실패·오류·skip 0 |
| 추가 계산기 테스트 | 19 성공, 그중 1개에서 독립 정수 산술 기준으로 600개 조합 비교 |
| 추가 PostgreSQL/controller HTTP 계약 | 18 성공 |
| 실행 중 API 및 Next rewrite 경유 실제 HTTP | 18/18 성공 |
| 완전 합성 브라우저 회귀 | 6/6 성공, fixture 밖 요청·변경 시도 0, 원래 화면 복구 후 metadata200 |
| 구독료 입력·저장 흐름 | 깨끗한 입력의 서버 갱신, 변경 안내·다시 불러오기, 중복 저장 방지, 실패 후 재시도, 소수 6자리, 0, 설정 해제 성공 |
| 기존 권한·본문 열람 경계 | 권한 축소 시 본문 삭제, viewer 본문 요청·비용 form 없음, CSRF 변경·비용 저장·폴링 중 추가 본문 조회 없음 |
| 선택·응답 지연 | 닫힌 질문의 늦은 응답 폐기, 초기 조회 중 일시정지·취소·명시 새로고침 성공 |
| 프런트엔드 | typecheck 및 실행 중 `.next`와 분리한 production build 성공 |
| 화면 | 1440/1280/768/390/320px 가로 넘침 없음, 본문14px, 모바일 설정 입력 높이38px |

계산기에는 272000/272001 입력 경계, 전체가 캐시인 요청, 두 캐시 합계 초과, Long.MAX_VALUE, 소수 반올림 경계, 누락과 0의 구분, 음수, 정확한 모델명만 단가 적용, CLI 보고 환산액을 포함했습니다. 가격 snapshot의 계산을 검증한 것이며 실제 공급자 청구서와 대사한 것은 아닙니다.

월별 배분은 UTC·Asia/Seoul·New York의 봄/가을 서머타임, 윤일 및 다음 달 시작 제외, 다른 계정·tenant 제외, 보관 삭제 제외, 전체 CLI 분모, 0과 배분 불가 구분, 질문 추가 후 재배분을 확인했습니다. 동시에 4개 설정을 저장해 최종 금액·통화가 한 요청의 완전한 쌍이며 성공 감사 기록은 저장마다 1건인 것도 확인했습니다.

HTTP 계약 테스트는 실제 임시 PostgreSQL과 `WebTestClient.bindToController`를 사용하고 principal을 테스트 필터로 제공합니다. 실행 중 서버의 별도18개 HTTP 검증은 실제 관리자 인증, 무인증401, 잘못된 필터400, 없는 계정404, 거대한 숫자·중복 JSON400, 초과 본문413과 `Cache-Control: no-store`를 두 경로에서 확인했습니다. 실제 SSO IdP 발급 과정이나 원격 TLS를 대신 검증하지는 않습니다.

실제 설정 행 및 `monitoring.cost.configure` 감사 행은 HTTP 시험 전·후 모두0이었습니다. 저장 실패·성공·설정 해제는 브라우저 응답 fixture 또는 임시 DB에서 시험했습니다. 기존 검증 세션의 관측 $0.024994와 $0.034723이 화면 계산과 일치했고, 두 값을 질문·세션 총액으로 합산하지 않았습니다.

## 재실행

Java·DB 검증:

```bash
bash scripts/test-analytics.sh
```

프런트엔드 검증:

```bash
npm --prefix llm-gateway-admin ci
npm --prefix llm-gateway-admin run typecheck
npm --prefix llm-gateway-admin run build -- --webpack
```

빌드는 개발 서버와 다른 복제본에서 실행하세요. 브라우저 회귀 소스는 [`monitoring-costs-adversarial.browser.js`](../../llm-gateway-admin/scripts/monitoring-costs-adversarial.browser.js)입니다. Playwright CLI의 인증된 관리자 페이지에서 이 파일의 `async (page)` 함수를 `run-code`에 전달합니다. `usage:read`, `content:read`, `collectors:manage` 권한과 한국어 관리자 화면이 필요합니다. 반환 JSON의 `failed`가0이고 `cleanup.passed`가true인지 확인합니다. 전체 모니터링 응답을 안전한 합성 자료로 대체하며 실제 질문 본문 조회·구독료 저장은 실행하지 않습니다.

상세 실행 산출물은 게시하지 않는 `output/playwright/`와 `var/admin-runtime/`에 보관합니다. 주요 JSON은 `admin-costs-synthetic-regressions-final.json`, `admin-costs-live-http.json`, `admin-costs-draft-save-final.json`, `admin-costs-security-adversarial-final.json`, `admin-costs-live-adversarial-final.json`, `admin-costs-pause-adversarial-final.json`, `admin-costs-responsive-adversarial-final.json`입니다.

## 확인 범위

로컬에서 확인한 비용 계산·배분·권한·화면 동작입니다. 실제 월 구독료는 미입력 상태이며 운영자가 금액을 입력해야 배분합니다. Codex의 정확한 질문별 토큰 귀속, 실제 청구서, 원격 HTTPS 배포, 다수 사용자 부하와 오프라인 복구는 이 시험의 완료 범위에 포함하지 않습니다. 새로운 프로젝트 의존성을 추가하지 않았습니다.
