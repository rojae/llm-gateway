# 어드민 UI와 비용 산정 검증

검증 일자: 2026-10-08, 로컬 어드민 `http://localhost:3000/admin`, API `127.0.0.1:8082`.

## 적용한 동작

로그인 첫 화면을 구독 모니터링으로 변경했습니다. 계정·세션 목록을 유지하며 질문을 본문 영역에서 읽습니다. 본문 14px, 현재 페이지 계정 검색, 기간 단축 버튼, 수신 연결/기록 시각 구분, 자동 갱신 중지/재개를 제공합니다. 감사 기록은 읽기 쉬운 작업명과 원본 action을 함께 표시합니다.

`집계 미확정` 대신 질문 귀속·토큰 누락·모델 단가 미등록 등 사유를 구분합니다. Codex의 질문별 토큰 합계는 여전히 확인되지 않았지만, 개별 관측값에는 비용을 산정할 수 있습니다.

API 환산액은 모델 단가를 적용한 관측의 참고값입니다. 구독료 배분액은 계정·월별 입력 금액을 보관된 질문 수 기준으로 나눈 값입니다. 두 금액을 합산하거나 질문 귀속을 추정해 확정 비용으로 표시하지 않습니다.

## 실제 데이터 확인

로컬 검증 계정의 Codex 0.160.0 수집 기록에 새 계산기를 적용했습니다. 새 모델 호출이나 과거 대화 업로드는 하지 않았습니다.

| 관측 | 입력 | 출력 | 캐시 읽기 | 일반 입력 | API 환산액 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 앞선 응답 관측 | 12,497 | 0 | 0 | 12,497 | $0.024994 |
| 산정 월 최근 응답 관측 | 28,985 | 10 | 12,288 | 16,697 | $0.034723 |

계산에 사용한 `gpt-6.1-sol` Standard USD/100만 토큰 단가는 입력 2, 캐시 읽기 0.10, 캐시 쓰기 2.50, 출력 10입니다. 272K 초과 입력은 전체 요청에 입력·캐시 2배, 출력 1.5배를 적용합니다. [공식 모델 단가](https://developers.openai.com/api/docs/models/gpt-6.1-sol), 확인 2026-10-08.

일반 입력에서 캐시를 제외하므로 캐시 토큰을 중복 과금하지 않습니다. Codex 입력의 캐시 포함 관계는 API 사용량 기준을 적용한 가정입니다. Fast·지역 할증·도구 비용은 제외합니다. 누락된 캐시 값은 0으로 가정하며 토큰 입력/출력이 없으면 금액을 생성하지 않습니다. [공식 구독 가격 안내](https://learn.chatgpt.com/docs/pricing)는 API 토큰 가격과 포함된 구독 사용을 구분합니다.

위 두 관측을 세션 전체 비용으로 합산하지 않았습니다. 최근 응답 조회는 이벤트 페이지와 무관하게 산정 월 전체 기록에서 조회합니다. 질문별 API 사용량의 귀속 문제는 별도로 남아 있습니다.

실제 계정 월 구독료는 미설정 상태를 유지했습니다. 입력하지 않은 요금제/금액을 가정하지 않습니다. 월 USD20·질문 2개일 때 질문당/각 1개 질문 세션당 USD10 표시를 **브라우저 응답 fixture**로 검증했고 실제 계정 금액을 변경하지 않았습니다. 영속 저장·월 분리·권한·감사 원자성은 별도 임시 PostgreSQL에서 검증했습니다.

## 검증 결과

- `bash scripts/test-analytics.sh`: 실제 임시 PostgreSQL/Redis에서 전체 237개 성공, 실패·오류·skip 0.
- 이후 추가한 감사 실패 rollback 테스트를 포함해 `bash scripts/test-analytics.sh --tests '*MonitoringCostsContractTest'`: 5개 성공. 감사 기록 저장 실패 시 HTTP503, 기존 월 금액·통화 유지, 실패한 변경의 성공 감사 기록 없음.
- 계산기 테스트: 캐시 중복 계산, 272K 경계, 미등록 모델/누락/음수/캐시 초과/오버플로, CLI 보고 환산액 구분.
- HTTP 계약: tenant/owner/session/question 격리, viewer/operator 변경 거절, 중복/초과 필드·숫자·월·시간대 검증, 보관 삭제 제외, 최근 관측 조회와 pagination 분리, 금액 0과 미설정 구분.
- 프런트엔드 `npm run typecheck` 및 격리 복사본 `npm run build -- --webpack` 성공. 빌드 산출물은 실행 중인 `.next`를 덮어쓰지 않았습니다. 별도 lint script는 제공되지 않습니다.
- 실제 브라우저: 비용 metadata 조회가 질문 본문을 미리 읽지 않음, 명시 선택 1회만 본문 조회, 자동 갱신/CSRF 변경/비용 저장은 본문을 재조회하지 않음.
- 권한 변경 fixture: 기존 본문 삭제, viewer 본문 요청 없음, 비용 변경 form 제거. 상태503 fixture: 이전 집계 표시와 연결 실패 문구. 늦게 도착한 질문 응답은 선택 해제 뒤 복원되지 않음.
- 초기 metadata 요청 중 갱신 중지: 진행 표시 해제, 취소된 응답 폐기, 명시 새로고침 정상. 처음 시험에서 첫 요청만 지연해 StrictMode의 두 번째 요청이 통과한 fixture 문제가 있었고, 모든 응답을 제어한 재시험은 통과했습니다.
- 1440/1280/768/390/320px: document 가로 넘침 없음, 질문 본문 14px 유지. 실제 데이터 flow 중 pageerror 0. 허위 실패를 포함한 fixture 시험의 console 로그와 구분했습니다.

## 서비스 적용 및 근거

V7 구독료 설정 migration을 로컬 DB에 적용했습니다. 기존 migration checksum은 변경하지 않았습니다. 적용 전 `var/admin-runtime/analytics-before-costs-20261008-025648.dump`를 0600 권한으로 생성했습니다. 에이전트가 기동한 8082/3000 프로세스만 재시작했으며 기존 8080 서비스는 적용 전·후 health200을 확인했습니다. CLI 설정·공급자 인증·모델 주소는 이번 변경으로 수정하지 않았습니다.

주요 변경 파일:

- `llm-gateway-admin/app/admin/{console,monitoring,model,views,costs}.tsx`와 `app/globals.css` (`model`은 `.ts`).
- `llm-gateway-api/.../analytics/MonitoringCostEstimator.java`, `MonitoringCosts.java`, `MonitoringCostsController.java`, `MonitoringQueries.java`, `AuditService.java`.
- `V7__monitoring_costs.sql`, `MonitoringCostEstimatorTest.java`, `MonitoringCostsContractTest.java`, `DESIGN.md`.

실측/화면 근거는 ignored `output/playwright/`에 있습니다:

- `admin-costs-live-api.json`, `admin-costs-browser-checks.json`, `admin-costs-contract-checks.json`, `admin-ux-pause-checks.json`, `admin-ux-live-checks.json`.
- `admin-costs-desktop-final.png`, `admin-costs-settings-final.png`, `admin-costs-observations.png`, `admin-costs-{1280,768,390,320}.png`.

실제 공급자 청구서 대사, 세금/추가 결제, 누락된 활동의 복원, 모든 모델의 단가 카탈로그, 원격 production 배포는 이 로컬 검증에 포함하지 않았습니다. 질문 수가 수신·삭제로 바뀌면 구독료 배분액도 바뀌므로 고정 회계 장부가 아닙니다.
