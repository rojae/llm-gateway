# 대화 탐색·집중 보기 UI 검증

2026-10-08에 승인된 A안의 세션 탐색과 B안의 문서형 집중 읽기를 로컬 관리 화면에 적용했다. 실행 주소는 `http://localhost:3000/admin`이다.

## 적용 범위

- 공통 왼쪽 메뉴를 상단 탐색으로 바꾸고 운영 메뉴·사용자 메뉴를 분리했다. 기존 권한에 따른 메뉴 노출을 유지한다.
- 계정 선택과 조회 범위를 상단에 모으고 세션 목록 옆에서 질문과 답변을 읽는다. 질문 선택은 본문 미리보기 없이 metadata만 표시한다.
- `집중해서 읽기`는 열린 본문과 선택을 유지하며 탐색 영역을 숨긴다. `탐색으로 돌아가기` 또는 Escape로 복귀하고 키보드 초점을 복원한다.
- 비용·사용량·기술 정보는 접을 수 있으며 질문·답변·관측 이벤트 탭과 미연결 답변 조회는 유지한다.
- 모바일 메뉴와 390px·320px 화면의 줄바꿈, 터치 영역, 가로 넘침을 확인했다.

기존 본문 조회 훅, 권한 검사, 감사 기록, 보관기간 처리와 답변 갱신 경로는 유지했다. 집중 보기 전환만으로 본문을 다시 요청하지 않는다. 계정 변경 시 이전 본문을 제거하고, 동일 계정의 세션 변경 시 비용 입력 초안은 보존한다.

## 검증 결과

| 검증 | 결과 |
| --- | --- |
| TypeScript 검사 | 통과 |
| 프로덕션 빌드 | 통과 |
| 탐색·집중 보기 브라우저 검증 | 7개 통과 |
| 기존 질문·답변 브라우저 회귀 | 15개 통과 |
| 기존 비용 브라우저 회귀 | 6개 통과 |
| 공통 탐색·메뉴·권한 브라우저 검증 | 9개 그룹 통과, JavaScript 오류 없음 |
| 변경 파일 공백 검사 | 통과 |
| 로컬 `/admin` 응답 | HTTP 200 |

브라우저 검증은 독립 Chromium 프로필에서 합성 인증과 API 응답을 사용했다. 실제 계정의 대화나 비용을 변경하지 않는다. 검증에는 본문 추가 조회 방지, 계정·세션 변경 후 이전 본문 제거, 삭제·권한 부족·조회 실패 상태, 늦게 도착한 답변, 입력 초안 보존, Escape 초점 복원, 권한별 메뉴 노출이 포함된다.

주요 재현 명령:

```sh
npm --prefix llm-gateway-admin run typecheck
npm --prefix llm-gateway-admin run build
PLAYWRIGHT_MODULE=/path/to/playwright/index.mjs node llm-gateway-admin/scripts/monitoring-inbox.browser.mjs
PLAYWRIGHT_MODULE=/path/to/playwright/index.mjs node llm-gateway-admin/scripts/monitoring-responses.browser.mjs
git diff --check
```

`SCREENSHOT_DIR`를 지정하면 탐색·집중 보기의 데스크톱 및 모바일 캡처를 저장한다. 이번 로컬 결과는 ignored `var/verification/inbox-ui/`에 보관했다. 비용·공통 메뉴 검증 실행기는 `var/verification/run-inbox-costs.mjs`, `var/verification/run-inbox-chrome.mjs`이며 공통 메뉴 결과는 `var/verification/inbox-chrome-report.json`에 있다.

실행 중인 개발 서버와 빌드 출력을 공유하지 않도록 프로덕션 빌드는 별도 복사본에서 수행하는 것을 권장한다. 빌드가 생성한 `next-env.d.ts`의 경로 변경은 제품 변경에 포함하지 않는다.

독립 코드 리뷰에서 발견한 질문 닫기 후 초점 복원 문제를 수정하고 회귀 검증을 추가했다. 최종 리뷰에서 차단 이슈는 없었다. 개선된 화면은 독립 브라우저 검증과 캡처로 확인했다. 사용자 Chrome에서는 초기 `cgWindowNotFound` 오류 이후 재연결에 성공해 실제 로컬 앱의 로그인 화면까지 확인했다. 해당 새 탭은 운영자 토큰 로그인이 필요하며, 사용자 브라우저에서 로그인 이후 화면까지 확인한 것은 아니다.
