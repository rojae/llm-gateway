# 원격 직접 전송 모니터링 구현·검증

작성일: 2026-10-08. 사용자 승인 계획의 로컬 개발·리뷰를 수행했다. **원격 직접 전송을 위한 서버·화면 구현과 로컬 통합 검증을 완료했으며, 실제 원격 HTTPS 배포·native exporter 지연 검증은 수행하지 않았다.** Codex 질문별 정확한 사용량은 지원 완료로 판정하지 않는다.

수치와 검증 경계는 [비밀 없는 결과 요약](2026-10-08-remote-monitoring-evidence.json)에도 남겼다.

## 구현한 동작

- 관리자가 관측 계정을 등록하고 개인별 수집 키의 owner/registration에 연결한다. 발급자와 소유자를 구분하며 저장 transaction에서도 owner·registration·만료·폐기를 재검증한다.
- 기존 `/telemetry/v1/logs`에서 비압축 native OTLP HTTP/JSON을 수신한다. DB commit 후 성공 응답을 반환하고 저장 실패는 재시도 가능한 실패로 처리한다.
- 별도 session/question/event 원장과 보호된 질문 본문 저장소를 추가했다. 본문은 일반 목록·이벤트에 포함하지 않고 `content:read` 검사와 감사 기록을 적용한다.
- Claude는 session/prompt/request 식별자와 명시적인 main 출처가 확인된 호출만 질문 합계로 연결한다. 미확인 출처·aux/subagent·오류는 확정 합계에서 제외한다. 비용은 알려진 모델의 CLI 추정값이며 출처는 `claude_client_estimate`다.
- Codex는 conversation별 질문·완료 이벤트를 보존하되 질문 토큰·비용은 null/미귀속이다. 12,497와 29,220의 입력을 41,717이라는 질문 확정치로 합산하지 않는다.
- 공급자 계정 속성은 원문을 저장하지 않고 tenant/owner 범위 해시와 unknown/observed/conflict 상태로 제한한다. 공급자 계정 인증이나 owner 변경 근거로 사용하지 않는다.
- 키 회전과 transport replay는 같은 identity로 중복을 막는다. 내용·질문·세션 만료 뒤에는 tombstone이 재생성을 막는다. 활성 세션의 오래된 질문도 서버 수신 시각 기준으로 만료하며 정리는 250건씩 수행한다.
- DB 준비·retention을 파일 수집 성공 여부와 분리했다. 관리자 화면은 활성 상태에서 2초 polling, 요청 취소·generation 검사, 권한 변경·로그아웃 시 본문/발급 키 state 제거를 적용한다.
- 발급 직후 Claude JSON/Codex TOML 직접 설정 예시를 제공한다. 개인 CLI 설정과 모델 base URL은 이번 개발에서 바꾸지 않았다. [설정 가이드](../remote-monitoring.md).

기존 owner 없는 collector 발급 API는 기존 소비자의 usage-only 호환을 유지한다. 새 화면의 발급은 owner를 필수로 요구한다. legacy 키로 질문/Codex 이벤트를 보내면 `account_not_linked` partialSuccess로 거절하며 새 monitoring 연결 성공으로 처리하지 않는다.

## 변경 범위

| 영역 | 주요 파일 |
| --- | --- |
| 계정·수집 키 | `CollectorCredentialService`, `CollectorCredentialController`, `AuditService` |
| native 수신·영속 저장 | `MonitoringNormalizer`, `MonitoringStore`, `TelemetryController`, `JsonlIngestor` |
| 관리자 조회 | `MonitoringQueries`, `MonitoringController` |
| DB | 신규 `V6__remote_monitoring.sql`; V1–V5 미변경 |
| 화면 | `app/admin/monitoring.tsx`, `console.tsx`, `model.ts`, `views.tsx`, `globals.css` |
| 회귀 | `MonitoringNormalizerTest`, `MonitoringStorageTest`, `RemoteMonitoringContractTest` |
| 설정·안내 | README의 nginx 수집 경로, admin 가이드, remote-monitoring 가이드, Codex P0 검증 기록 |

새 인프라·패키지·로컬 상주 수집기는 추가하지 않았다. 기존 요청 overview는 새 monitoring 원장과 별개이며 구독 환산액을 기존 API 비용에 합산하지 않는다. checkout의 대부분이 Git에 추적되지 않아 `git diff` 대신 개발 시작 시 파일 복사본·SHA-256과 최종 소스를 비교했다. 관련 없는 기존 작업을 stage/commit하지 않았다.

## 실행한 검증

| 검사 | 결과와 범위 |
| --- | --- |
| 실제 PostgreSQL·Redis 전체 Java suite | 39 suites / 226 tests / 실패·오류·skip 0 |
| 마지막 main scope 보수화 후 관련 suite | 59 tests / 실패·오류·skip 0; parser 11·독립 HTTP/DB contract 15·storage 2 포함 |
| Python 기존 회귀 | 17 tests 통과 |
| 프론트엔드 | `npm run typecheck`, `npm run build` 통과 |
| 서버 패키징 | 최신 `bootJar` 생성 성공 |
| 독립 리뷰 | code-reviewer APPROVE, architect CLEAR; 보고된 결함 수정 후 재검토 |

전체 226건과 마지막 관련 59건은 중복을 포함하는 별도 실행이다. 마지막 변경은 main scope 근거 없는 이벤트를 미귀속으로 유지하는 조건과 회귀 두 건이다. 최종 관련 suite를 새 PostgreSQL·Redis에서 다시 실행했다. 한 번의 임시 harness가 Gradle 실행 전 종료 코드 1로 끝났고 원인은 확정하지 못했으며, 다음 실행은 59건 모두 통과했다. 이를 코드 테스트 실패나 원격 전송 증거로 간주하지 않았다.

독립 HTTP/DB 시험은 계정·tenant spoof 방지, owner 없는 키의 호환/거절, 만료·폐기 401, 본문 권한과 감사 실패, Claude 557/$0.001061, Codex null 합계, 역순·3회 replay·키 회전, transaction rollback 503와 재시도, 삭제 후 late 이벤트, 활성 세션의 오래된 질문, 공급자 원문 미보관을 확인했다. parser 시험은 epoch nanoseconds, 숫자 문자열·overflow, 미지원·malformed, 캐시 포함 관계·원천 범위를 다뤘다. storage 시험은 손상된 파일에도 DB readiness와 retention이 유지되는 것을 확인했다.

## 실제 브라우저와 API smoke

별도 임시 loopback API·Next.js·PostgreSQL을 실행했다. native schema의 **합성 fixture에 기존 실측 토큰 수치를 사용**했으며 공급자에 새 모델 질문을 보내지 않았다. 이 시험은 CLI exporter나 실제 원격 TLS 경로를 검증하지 않는다.

- 계정→세션→질문 탐색, Claude 557토큰과 $0.001061 API 환산액 표시를 확인했다. 등록되지 않은 합성 모델의 비용은 미확인/null이었다.
- HTML 형태의 질문을 plain text로 표시하고 스크립트가 실행되지 않는 것을 확인했다.
- 수신 중인 화면에 새 질문을 전송해 수동 갱신 없이 나타나는 것을 확인했다. 표본은 1건이며 p95 또는 native exporter SLA 근거가 아니다.
- Codex 질문은 집계 미확정이며 완료 이벤트 12,497/0과 29,220/5를 따로 표시한다. 41,717을 확정 합계로 보여 주지 않았다.
- 브라우저에서 계정 등록·owner 필수 발급·발급 직후 JSON 설정을 확인했고, 모달을 닫으면 발급 키가 사라졌다.
- session 응답의 역할을 시뮬레이션해 기존 본문 모달이 제거되는 것을 확인했다. 실제 OIDC 역할 변경 E2E와는 구분한다. 서버 viewer 본문 비노출은 별도 HTTP/DB 시험으로 확인했다.
- document visibility를 시뮬레이션해 polling 중단·재개를 확인했다. 로그아웃 후 본문과 모달이 사라지고 local/session storage가 모두 0인 것을 확인했다.
- 최신 jar로 같은 임시 DB의 API를 정상 종료·재시작한 뒤 ACK한 7개 이벤트와 readiness를 확인했다. 강제 kill 직후 ACK 경계의 crash 시험은 수행하지 않았다.

스크린샷과 비밀 없는 결과 요약은 로컬 `output/playwright/remote-monitoring-{claude,codex}.png`, `.omx/verification/remote-monitoring-run/`에 남겼다. 재시작 중 예상된 연결 오류가 잠시 발생했고 서버 복구 뒤 화면이 다시 갱신됐다. 정상 탐색 중에는 브라우저 error가 없었다. 시험 수집 키 2개를 폐기하고 임시 서버·DB·브라우저를 종료했으며 발급 값을 담은 임시 파일을 제거했다.

## 남은 실제 환경 검증

1. 실제 도메인·인증서·SSO와 배포된 DB에서 공식 CLI→HTTPS→DB→화면 경로.
2. CLI별 20개 이상 표본의 native export→DB 및 DB→화면 p95 측정, 네트워크 차단·flush·강제 종료·서버 포화.
3. Codex의 정확한 질문/턴 identity와 usage 범위 대조. [P0 검증 기록](2026-10-08-native-codex-correlation.md)에서는 native logs만의 정확한 귀속을 보장하지 않는다.

따라서 판정은 **로컬 구현·통합 검증 완료**다. 계획의 실제 원격 관측 MVP 수용 조건과 양쪽 CLI 질문별 정확 사용량 완료 조건은 아직 충족됐다고 보고하지 않는다. 구독의 실제 질문별 청구액·잔여 한도·과거 대화 자동 가져오기는 구현 범위 밖이다.
