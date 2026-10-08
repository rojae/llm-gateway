# 기업 SSO 설정

LLM Gateway Console은 기업 OIDC 계정으로 로그인하고, 서명 검증된 토큰의 조직과 역할로 접근을 제한합니다. Anthropic·OpenAI 공급자 키와 사용량 전송용 수집 키는 콘솔 로그인 자격 증명이 아닙니다.

이 문서는 현재 LLM Gateway 구현과 공식 IdP 문서를 기준으로 한 설정 가이드입니다. Keycloak·Microsoft Entra ID·Okta 고객 환경과 실제로 연동 완료했다는 의미는 아닙니다. 고객의 클레임 정책, MFA, 프록시, 세션 정책은 마지막 검증 절차에서 확인해야 합니다.

## 먼저 정할 값

| 항목 | 예시 / 규칙 |
| --- | --- |
| API·콘솔 외부 주소 | `https://llm-gateway.example.com` — HTTPS 루트 주소. `/admin`은 Next.js로, 인증·관리 API 경로는 Spring Boot로 연결 |
| 로그인 시작 | `/oauth2/authorization/enterprise` |
| 등록할 Redirect URI | **`https://llm-gateway.example.com/login/oauth2/code/enterprise`** — 정확히 일치, 와일드카드 사용하지 않음 |
| 로그인 성공 / 실패 | `https://llm-gateway.example.com/admin` / `https://llm-gateway.example.com/admin?login=failed` (`GW_ADMIN_UI_URL` 기준) |
| Issuer | 조직이 관리하는 단일 OIDC issuer의 정확한 URL |
| 웹 클라이언트 | 서버에서 client secret을 보관하는 confidential web client |
| API audience | 자동화가 관리 API를 호출할 때 필요한 별도 대상 식별자 |
| 조직 | 허용 목록에 등록한 한 개의 문자열 클레임 |
| 역할 | `viewer`, `operator`, `admin` 값의 비어 있지 않은 배열 |

LLM Gateway는 Authorization Code + PKCE를 사용합니다. 브라우저 로그인은 **서명된 ID token**의 `sub`, 조직, 역할을 읽으며 UserInfo 값으로 권한을 높이지 않습니다. ID token audience는 웹 client ID에 맞아야 합니다. 관리 API Bearer JWT는 별도로 `GW_OIDC_AUDIENCE`를 만족해야 하므로 두 audience를 구분해 설정하세요.

기본 클레임 형태는 다음과 같습니다. 아래는 토큰이 아닌 설명용 JSON입니다.

```json
{"sub":"employee-123","tenant_id":"engineering","roles":["viewer"]}
```

조직은 문자열이어야 하며 `GW_ALLOWED_TENANTS`에 정확히 포함되어야 합니다. 역할 배열에 알 수 없는 값이 하나라도 있으면 거부합니다. `realm_access.roles` 같은 중첩 경로는 지원하지 않으므로 최상위 클레임으로 매핑하세요. `sub`는 비어 있지 않고 제어 문자가 없는 최대 512자 문자열이어야 합니다.

| 역할 | 권한 |
| --- | --- |
| `viewer` | 사용량, 요청 메타데이터 조회 |
| `operator` | viewer + 캡처된 본문, 적재 상태 조회 |
| `admin` | operator + 수집 키 발급·폐기, 감사 기록 조회 |

조직과 역할의 원본은 **IdP 관리자가 통제하는 사용자 속성·역할 할당**이어야 합니다. 사용자가 편집할 수 있는 프로필, 브라우저 입력, 임의의 요청 헤더나 텔레메트리 `organization.id`를 권한에 매핑하면 안 됩니다. 현재 세션은 한 조직에 고정되며 콘솔에 임의 조직 전환 기능은 없습니다.

## 서버 설정

다음은 비밀을 제외한 예시입니다. IdP에 맞게 issuer, client ID, API audience와 조직 값을 바꾸세요.

```dotenv
GW_SECURITY_MODE=enterprise
GW_EXTERNAL_BASE_URL=https://llm-gateway.example.com
GW_ADMIN_UI_URL=https://llm-gateway.example.com/admin
GW_OIDC_ISSUER_URI=https://id.example.com/realms/company
GW_OIDC_CLIENT_ID=llm-gateway-console
GW_OIDC_AUDIENCE=llm-gateway-admin-api
GW_OIDC_TENANT_CLAIM=tenant_id
GW_OIDC_ROLES_CLAIM=roles
GW_ALLOWED_TENANTS=engineering,operations
GW_ANALYTICS_TENANT_ID=engineering
SPRING_SESSION_TIMEOUT=30m
```

`GW_OIDC_CLIENT_SECRET`과 `SPRING_DATA_REDIS_URL`은 서버의 Secret 저장소나 비공개 환경 설정으로 주입합니다. 브라우저·소스 저장소·예시 URL에 실제 비밀을 넣지 마세요. 모든 복제본은 동일한 Redis와 인증 설정을 사용합니다. 세션은 Redis에 저장되며 브라우저에는 HttpOnly·Secure·SameSite=Lax의 `LLMGATEWAYSESSION` 쿠키를 사용합니다. 세션 유휴 시간은 기본 30분이며 현재 설정 범위는 0보다 크고 최대 24시간입니다.

`GW_ADMIN_UI_URL`은 로그인 성공·실패 후 이동할 Next.js 콘솔의 외부 주소입니다. 운영 환경에서는 HTTPS URL을 사용하세요. 위 구성은 하나의 공개 호스트에서 `/admin`을 Next.js로 보내고 `/admin/api/*`, `/admin/auth/*`, `/oauth2/*`, `/login/oauth2/*`를 API로 보내는 예시입니다. Next.js의 `GATEWAY_API_URL`에는 브라우저가 아닌 Next.js 서버에서 접근할 수 있는 API 내부 주소를 설정합니다. 로컬 `./scripts/admin.sh`는 화면 `http://localhost:3000/admin`과 API `http://127.0.0.1:8080`을 사용합니다.

기업 모드에서는 기존 `GW_ADMIN_TOKEN`과 `GW_TELEMETRY_TOKEN`을 설정하지 않습니다. 공유 토큰이 남아 있으면 시작을 거부합니다. `GW_ANALYTICS_TENANT_ID`는 해당 배포의 신뢰된 Gateway 파일 로그를 귀속할 조직이며 허용 목록에 포함되어야 합니다. 수집 키는 서버가 각 키에 연결한 조직으로 텔레메트리를 귀속합니다.

## Keycloak

1. 회사 realm에 OIDC client를 만듭니다. Client authentication을 켜고 Standard flow를 사용하며 위의 정확한 Redirect URI를 등록합니다. client secret은 LLM Gateway 서버에만 전달합니다.
2. 사용자나 관리 그룹에 LLM Gateway 전용 `viewer`·`operator`·`admin` client role을 할당합니다. LLM Gateway용 client-role mapper로 **최상위 `roles` 배열**을 생성하고 ID token에 포함합니다. 전체 realm role을 그대로 넣어 기본 역할까지 섞이지 않도록 합니다.
3. 관리자가 관리하는 조직 속성을 `tenant_id` 문자열로 매핑하고 ID token에 포함합니다. 사용자 자신의 계정 화면에서 해당 속성을 변경하지 못하도록 권한을 제한합니다.
4. LLM Gateway가 요청하는 scope는 `openid`입니다. 조직·역할 mapper를 전용 client scope 또는 기본 client scope에 연결하여 별도 optional scope 없이 ID token에 나오도록 합니다.
5. 관리 API 자동화가 필요하면 access token에도 같은 조직·역할 클레임과 API audience를 포함시킵니다. audience mapper 설정은 웹 client ID와 구분합니다.

Keycloak은 protocol mapper로 클레임과 ID/access token 포함 여부, audience를 설정합니다. 위 매핑은 LLM Gateway가 요구하는 평면 클레임 구조에 맞추기 위한 구성입니다. [공식 서버 관리 문서](https://www.keycloak.org/docs/latest/server_admin/), [공식 Protocol Mappers 문서](https://www.keycloak.org/admin-api/protocol-mappers).

## Microsoft Entra ID

1. 조직 디렉터리에 웹 앱을 등록하고 Authentication의 **Web** Redirect URI에 위 콜백을 등록합니다. client ID와 서버용 client secret을 설정합니다. issuer는 특정 디렉터리의 `https://login.microsoftonline.com/<directory-id>/v2.0`을 사용합니다. 현재 LLM Gateway는 단일 issuer를 엄격히 검증하므로 `common`을 통한 임의 디렉터리 수용을 전제로 하지 않습니다. [OIDC 공식 문서](https://learn.microsoft.com/en-us/entra/identity-platform/v2-protocols-oidc), [Redirect URI 규칙](https://learn.microsoft.com/en-us/entra/identity-platform/reply-url).
2. App roles의 **Value**를 정확히 `viewer`, `operator`, `admin`으로 정의하고 Enterprise applications에서 사용자·그룹에 할당합니다. 로그인 앱의 할당 역할이 ID token의 `roles`에 포함되도록 합니다. API용 앱 등록은 access token의 역할 설정이 별도로 필요합니다. [App roles 공식 문서](https://learn.microsoft.com/en-us/entra/identity-platform/howto-add-app-roles-in-apps).
3. **Entra 디렉터리 전체를 하나의 LLM Gateway 조직으로 취급하는 경우**, `GW_OIDC_TENANT_CLAIM=tid`, `GW_ALLOWED_TENANTS=<directory-id>`, `GW_ANALYTICS_TENANT_ID=<directory-id>`로 설정할 수 있습니다. `tid`는 로그인한 디렉터리 ID이며 부서 ID가 아닙니다. 같은 디렉터리의 여러 부서를 격리하려면 관리자 통제의 별도 조직 클레임을 설계·발급하고 해당 최상위 클레임 이름을 설정해야 합니다. 클라이언트가 부서를 선택해 권한을 바꾸게 하지 마세요. [ID token 클레임 참조](https://learn.microsoft.com/en-us/entra/identity-platform/id-token-claims-reference).
4. 관리 API용 JWT는 LLM Gateway API를 대상으로 발급합니다. `GW_OIDC_AUDIENCE`에는 해당 토큰에 실제 발급되는 audience를 설정하며, 토큰 버전에 따라 audience 형식이 다를 수 있으므로 App ID URI라고 가정하지 않습니다. Microsoft Graph용 토큰은 사용하지 않습니다. [공식 클레임 검증 안내](https://learn.microsoft.com/en-us/entra/identity-platform/claims-validation).

## Okta

1. OIDC **Web Application**을 등록하여 Authorization Code 흐름과 서버용 client secret을 사용합니다. Sign-in redirect URI에 위 콜백을 등록하고 접근할 사용자·그룹을 할당합니다. [웹 앱 Redirect 로그인 공식 문서](https://developer.okta.com/docs/guides/sign-into-web-app-redirect/main/).
2. LLM Gateway용 custom authorization server에서 issuer와 API audience를 정합니다. 관리자 통제 속성을 사용해 `tenant_id` 문자열과 LLM Gateway 역할만 포함하는 `roles` 배열을 발급합니다. ID token 클레임은 **Always** 포함되도록 구성합니다. LLM Gateway는 `openid`만 요청하므로 추가 scope 요청이나 UserInfo 조회에만 의존하는 클레임은 사용할 수 없습니다.
3. API 자동화용 access token에도 조직·역할을 포함합니다. 일반 회사 그룹 이름 전체를 `roles`에 넣지 말고 정확한 LLM Gateway 역할 값으로 제한·매핑합니다. ID token 및 access token의 결과는 각각 확인해야 합니다. [Custom claims 공식 문서](https://developer.okta.com/docs/guides/customize-tokens-returned-from-okta/main/), [Groups claim 공식 문서](https://developer.okta.com/docs/guides/customize-tokens-groups-claim/main/).

Okta custom authorization server의 운영 사용에는 API Access Management 상품 조건이 적용될 수 있습니다. 개발용 조직에서 제공되는 기능이 고객의 운영 계약에 포함되어 있다고 가정하지 마세요. 이 조건은 [Okta 공식 custom claims 안내](https://developer.okta.com/docs/guides/customize-tokens-returned-from-okta/main/)에 명시되어 있습니다.

## 로그아웃과 관리 API

콘솔은 `/admin/api/session`으로 조직·역할·권한과 CSRF 토큰을 확인합니다. 변경 요청과 `POST /admin/auth/logout`에는 `X-CSRF-TOKEN`이 필요합니다. 로그아웃 성공은 `204`이며 LLM Gateway 세션을 종료합니다. **IdP 세션 종료나 다른 회사 앱의 로그아웃은 수행하지 않습니다.** 따라서 로그인 버튼을 다시 누르면 기존 IdP 세션으로 바로 로그인될 수 있습니다. 현재 IdP의 sign-out callback 등록이 LLM Gateway 로그아웃을 연동시키지는 않습니다.

관리 API용 Bearer JWT도 issuer·서명·만료·audience·조직·역할 검증을 받습니다. 쓰기 작업은 Bearer 헤더가 있어도 CSRF 검증을 생략하지 않습니다. 자동화 구현 시 `/admin/api/session`의 CSRF 토큰과 해당 세션 쿠키를 유지하세요. ID token을 API 토큰 대신 전달하지 마세요.

## 연결 검증

1. 각 역할 계정으로 `/admin`에 로그인하고 사이드바 조직·역할을 확인합니다. viewer는 본문·적재·키 관리·감사 기록에 접근하지 못해야 합니다.
2. 두 조직에 같은 요청 ID를 준비하고 목록·상세·본문·키·감사 기록이 서로 보이지 않는지 확인합니다. 브라우저 요청의 조직 값을 바꾸어도 권한이 바뀌면 안 됩니다.
3. 누락·미허용 조직, 알 수 없는 역할, 잘못된 issuer/audience 및 만료 토큰을 거부하는지 확인합니다. 역할·조직의 실제 값은 비공개 검증 도구로 확인하고 토큰 원문을 로그나 이슈에 붙이지 않습니다.
4. 수집 키 발급 시 평문이 한 번만 표시되고, 폐기 후 다음 전송이 거부되며 감사 기록에 평문 없이 남는지 확인합니다.
5. 복제본을 바꿔도 세션이 유지되는지, 로그아웃 후 기존 LLM Gateway 세션이 거부되는지 확인합니다. IdP 세션 유지와 혼동하지 않습니다.

이 가이드에서 확인한 것은 소스의 인증 계약과 공식 설정 문서입니다. 고객 IdP의 실제 서명 토큰·계정 정책 및 고객 클러스터에서 위 절차를 수행하기 전에는 해당 환경 연동 완료로 표시하지 않습니다. 배포와 복구 절차는 [운영 가이드](operations.md)를 함께 확인하세요.
