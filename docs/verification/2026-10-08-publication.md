# 개인 GitHub 공개 준비 검증

검증일: 2026-10-08

외부 사용자가 저장소를 복제해 실행할 수 있도록 실행 안내, MIT 라이선스, GitHub Actions를 추가했다. 기존 비용 화면의 세밀 검증은 [비용 산정 검증 보고서](2026-10-08-costs-adversarial.md)를 참고한다.

## 공개 범위

- Java API와 Gradle Wrapper, Next.js 관리자 앱과 npm lockfile, 실행 스크립트, 운영 문서 및 검증 소스를 포함한다.
- 환경파일, 실제 토큰, DB·대화 데이터, 실행 로그, 브라우저 검증 결과물, IDE 설정과 개인 CLI 설정 보고서는 제외한다.
- 공개 후보 파일에서 로컬 환경파일의 실제 비밀값과 개인 절대 경로를 검색해 일치 항목이 없는 것을 확인했다. 정적 점검의 결과이며 모든 형태의 비밀정보 부재를 보장하는 것은 아니다.
- 공개 커밋에는 GitHub noreply 이메일을 사용한다. 기존 로컬의 빈 초기 커밋은 별도 로컬 브랜치에 보존하며 게시하지 않는다.

## 깨끗한 소스 사본 검증

게시 대상으로 스테이징한 파일만 새 디렉터리로 내보냈다. 기존 `node_modules`, `.next`, `.gradle`, 환경파일을 복사하지 않았다.

| 검사 | 결과 |
|---|---|
| PostgreSQL 17·Redis 7.2를 새로 생성한 Java 전체 테스트 | 275개 통과, 실패·오류·건너뜀 0 |
| Python 오프라인 테스트 | 17개 통과 |
| `npm ci` | lockfile에서 새 설치 성공 |
| 관리자 타입 검사 | 통과 |
| Next.js 16.3.8 production 빌드 | 통과 |
| `npm audit` | 권고 0건 |
| production 관리자 브라우저 비용 회귀 검사 | 6개 통과 |
| 실행 스크립트와 브라우저 회귀 스크립트 문법 | 통과 |
| 스테이징 diff 공백 검사 | 통과 |

브라우저 검사는 별도 루프백 포트에서 production 빌드를 실행하고 기존 로컬 API에 인증해 진행했다. 모니터링 응답은 합성 fixture로 대체했으며 실제 질문 본문 열람이나 구독료 저장은 하지 않았다. 늦게 도착한 토큰 반영, 일시정지 중 상세 정보의 최신성, 외부 설정 변경과 세션 전환 시 작성 중인 입력 보존, 비용 조회 실패 표시, 숨겨진 화면의 지연 조회 재개를 검사했다. 실제 설정 변경 시도는 0회였고 검사 후 메타데이터 조회는 HTTP 200이었다.

로컬 관리자 검증 환경은 Node.js 25.2.1이다. [CI 구성](../../.github/workflows/ci.yml)은 Java 21과 Node.js 22의 Ubuntu 새 체크아웃에서 DB 테스트, Python 테스트, 의존성 보안 검사, 타입 검사 및 빌드를 실행한다. 실제 CI 결과는 저장소의 Actions에서 확인한다.

## 공개 전 의존성 보안 패치

첫 새 설치에서 기존 Next.js 16.3.6, sharp 0.35.4, source-map-js 1.2.1에 보안 권고가 보고됐다. 신규 직접 의존성을 추가하지 않고 Next.js를 16.3.8로 고정하고, 기존 허용 범위 안에서 sharp 0.35.5와 source-map-js 1.2.2 및 관련 native 패키지의 lockfile을 갱신했다. React와 React DOM은 19.3.0을 유지했다. 갱신한 lockfile의 새 설치와 audit, 타입 검사, 빌드 및 브라우저 회귀 검사를 모두 다시 수행했다.

- [Next.js 공식 16.3.8 보안 패치 릴리스](https://github.com/vercel/next.js/releases/tag/v16.3.8)
- [sharp 관리자의 librsvg 보안 권고](https://github.com/lovell/sharp/security/advisories/GHSA-wq5f-xc86-pv6w)
- [source-map-js 공식 1.2.2 수정 릴리스](https://github.com/7rulnik/source-map-js/releases/tag/v1.2.2)

GitHub Actions도 설치 후 `npm audit --audit-level=high`를 수행한다. audit 결과는 검사 시점의 공개 권고에 의존하며 이후 새로운 권고가 추가될 수 있다.

## 재현과 범위

```bash
bash scripts/test-analytics.sh
python3 -m unittest discover -s scripts -p 'test_*.py'
npm --prefix llm-gateway-admin ci
npm --prefix llm-gateway-admin audit --audit-level=high
npm --prefix llm-gateway-admin run typecheck
npm --prefix llm-gateway-admin run build -- --webpack
```

브라우저 검사는 [합성 비용 회귀 스크립트](../../llm-gateway-admin/scripts/monitoring-costs-adversarial.browser.js)를 Playwright CLI `run-code`에 전달한다. 인증된 관리자 페이지와 `usage:read`, `content:read`, `collectors:manage` 권한이 필요하다.

공개 저장소는 소스 배포다. 외부 서버, 도메인, TLS와 실제 조직 SSO를 배포·검증한 결과는 아니다. 기존 로컬 서비스와 개인 CLI 설정은 유지하며, 별도 검증 서버와 임시 테스트 DB만 종료한다.
