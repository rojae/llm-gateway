# Enterprise 운영 배포 참고서

`deploy/enterprise/`는 외부 PostgreSQL, Redis, 회사 OIDC 공급자 및 Envoy Gateway의 TLS Gateway API를 사용하는 Kubernetes 참고 구성입니다. 이 파일은 클러스터에 적용된 기록이 아닙니다. 적용 전 운영 환경의 DNS, 인증서, 비밀값, PostgreSQL/Redis 주소, 네트워크 정책과 저장소 클래스를 확정하고 로컬 검증 결과와 실제 스테이징 시험 결과를 분리해 기록하세요. Kubernetes의 기존 community ingress-nginx는 2026년 3월에 지원이 종료되어 이 참고 구성에 사용하지 않습니다. [Kubernetes 공식 공지](https://kubernetes.io/blog/2025/11/11/ingress-nginx-retirement/).

## 배포 전 계약

- 회사 OIDC 공급자(Keycloak, Microsoft Entra ID 또는 Okta)에 전용 클라이언트를 등록합니다. 콜백은 공개 HTTPS 호스트의 `/login/oauth2/code/enterprise`, 로그인 시작은 `/oauth2/authorization/enterprise`, 로그아웃은 `POST /admin/auth/logout`입니다. 등록한 issuer, 웹 client ID token audience, 관리 API access token audience를 각각 실제 토큰과 일치시킵니다. 메일 주소나 브라우저가 보낸 조직 ID를 권한 근거로 사용하지 않습니다. `GW_ALLOWED_TENANTS`는 접근을 허용할 조직 목록이고, `GW_ANALYTICS_TENANT_ID`는 이 배포의 게이트웨이 JSONL에 부여할 **한 조직**입니다. 공급자별 매핑은 [SSO 가이드](sso.md)를 참고합니다.
- PostgreSQL과 Redis는 클러스터 외부의 관리형 서비스나 별도 HA 배포로 준비합니다. JDBC URL에는 TLS 검증(`sslmode=verify-full`)을 적용하고 신뢰할 수 있는 CA를 `llm-gateway-postgres-ca` Secret으로 제공합니다. Redis URL은 인증을 포함한 `rediss://` 형식으로 Secret에서 주입합니다. 각 애플리케이션 복제본은 시작할 때 별도 DB 연결로 Flyway 잠금을 잡고 마이그레이션을 실행합니다. 별도 마이그레이션 Job은 없으므로 앱 DB 역할에는 버전 변경에 필요한 스키마 권한을 부여하고, 첫 배포 시 복제본 시작·DB 연결 한도·락 대기를 확인해야 합니다. 기존 미버전 DB를 채택할 때만 `GW_ANALYTICS_LEGACY_TENANT_ID`를 명시합니다. 마이그레이션 및 기존 데이터 조직 매핑은 스테이징 복원본으로 먼저 검증합니다.
- `llm-gateway-runtime` Secret은 조직의 비밀값 관리 체계에서 생성합니다. 필수 키는 `db-password`, `redis-url`, `oidc-client-secret`, `key-id-secret`입니다. Secret 원문을 Git, 셸 이력, 이미지, ConfigMap에 저장하지 않습니다. TLS 인증서는 별도 `llm-gateway-tls` Secret으로 공급합니다. 예시 매니페스트에는 실제 자격증명이 없습니다. 환경변수로 주입한 Secret은 Pod 재시작 전까지 새 값을 읽지 않으므로 교체 시 순차 재시작을 계획합니다. [Kubernetes Secret](https://kubernetes.io/docs/concepts/configuration/secret/), [Secret 갱신](https://kubernetes.io/docs/tasks/inject-data-application/distribute-credentials-secure/).
- 각 게이트웨이 복제본은 자체 `ReadWriteOnce` PVC에 JSONL을 기록하고 자기 파일만 적재합니다. StatefulSet pod 이름을 안정적인 source ID로 사용합니다. 두 pod가 같은 로그 파일에 기록하거나 서로의 파일을 읽지 않습니다. PVC를 지우면 아직 DB에 적재되지 않은 관측치를 잃을 수 있습니다.
- 본문 파일 수집은 기본값에서 꺼져 있습니다. 필요할 때만 `SPRING_PROFILES_ACTIVE=prod,content-log-file`과 `GW_CONTENT_LOG_LEVEL=DEBUG` 또는 `TRACE`를 함께 설정합니다. `GW_CONTENT_LOG_FILE`과 `GW_ANALYTICS_CONTENT_LOG`는 같은 pod PVC의 `/var/log/llm-gateway/content.log`를 가리킵니다. 파일은 기본 10MB 단위·7일·총 100MB로 회전하고 비동기 큐가 가득 차면 일부 기록을 버릴 수 있어 완전한 원문 보존 수단이 아닙니다. 콘솔/수집기에도 본문이 기록될 수 있으므로 접근 권한과 별도 보관 정책을 확인합니다.
- 예시 StatefulSet은 2개 복제본과 `minAvailable: 1` 중단 예산을 사용하며 서로 다른 노드를 선호합니다. 실제 노드/가용 영역 장애 내성을 요구한다면 두 영역에 스케줄될 자원, CSI 재연결 동작, 관리형 DB/Redis 가용성을 확인하고 스케줄 정책을 강화해야 합니다. PVC 보존은 StatefulSet 삭제/축소에도 유지되도록 지정했습니다. [Kubernetes StatefulSet 저장소](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/).
- 관리자 화면은 별도 `llm-gateway-admin` Next.js Deployment/Service로 배포합니다. 같은 공개 호스트에서 `/`, `/admin`, `/_next`, `/icon.svg`는 화면으로, `/admin/api`, `/admin/auth`, `/oauth2`, `/login/oauth2`는 Spring Boot API로 보냅니다. API의 `GW_ADMIN_UI_URL`은 `https://gateway.example.com/admin`으로 설정해 SSO 로그인 후 화면으로 돌아오게 합니다. 두 서비스의 이미지 태그를 같은 변경 단위로 관리하세요.
- `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/health/dependencies`, `/actuator/prometheus`는 별도 9090 관리 포트에 있습니다. 공개 HTTPRoute에는 이 경로가 없고, NetworkPolicy는 지정한 `monitoring` namespace에서만 9090에 접근하도록 예시를 제공합니다. readiness는 애플리케이션의 준비 상태만 확인하고, DB/Redis 가용성은 dependencies에서 별도로 확인합니다. liveness는 외부 장애 때문에 pod를 재시작하지 않도록 둡니다. 관리 Service도 ClusterIP이며 외부 LoadBalancer가 아닙니다.
- 업스트림 공급자 키는 각 호출자가 전달합니다. 서버 공용 `GW_UPSTREAM_API_KEY`는 운영 구성에 두지 않습니다. 기업 모드에서는 정적 `GW_ADMIN_TOKEN`과 `GW_TELEMETRY_TOKEN`도 설정하지 않습니다. Claude Code 구독 수집은 DB에서 조직별로 발급한 별도 수집 자격증명으로만 받아야 하며, 모델 API 비용 추정액과 구독 사용량 환산액은 합산해 청구액이라고 표시하지 않습니다.

## 빌드와 적용 순서

다음 명령은 예시이며 실제 클러스터에는 자동 실행하지 않습니다. 이미지 태그에는 배포할 Git SHA 등 변경 불가능한 값을 사용합니다. `latest` 태그나 로컬 Docker daemon의 이전 이미지를 재사용하지 마세요.

```bash
docker build -f deploy/enterprise/Dockerfile -t registry.example.com/llm-gateway:<git-sha> .
docker build -f llm-gateway-admin/Dockerfile \
  --build-arg GATEWAY_API_URL=http://llm-gateway.llm-gateway.svc.cluster.local:8080 \
  -t registry.example.com/llm-gateway-admin:<git-sha> .
kubectl kustomize deploy/enterprise
# 대상 클러스터에 적용할 권한과 변경 창을 확보한 운영자가 이후에 실행:
kubectl apply --dry-run=server -k deploy/enterprise
```

적용 전 대상 클러스터는 Kubernetes 1.32 이상을 기준으로 확인하고, 지원 중인 Envoy Gateway와 Gateway API/Envoy Gateway CRD를 설치해야 합니다. 예시 이미지, 공개 호스트, `gatewayClassName`, 저장소 클래스, DB/Redis 주소, OIDC issuer/client ID와 네트워크 범위를 환경에 맞게 바꿉니다. 이미지 플랫폼도 대상 노드와 일치해야 합니다. Next.js rewrite의 `GATEWAY_API_URL`은 빌드할 때 경로 설정에 포함되므로 이미지 빌드 인자와 Deployment 환경변수를 같은 클러스터 내부 API 주소로 맞추세요. 내부 주소를 바꾸면 관리자 이미지를 다시 빌드합니다. `GW_ANALYTICS_REQUEST_RETENTION_DAYS`와 `GW_ANALYTICS_CONTENT_RETENTION_DAYS`는 기본 `0`으로 자동 삭제가 꺼져 있습니다. 실제 보관 정책과 원본 JSONL 재적재 정책을 검토하기 전에는 활성화하지 않습니다. 활성화하면 허용된 조직마다 보관 경계를 적용하고 삭제된 요청의 영속 식별 표식으로 오래된 JSONL 재적재를 막습니다. DB 감사 기록과 원본 파일/PVC 보관은 별도 정책으로 관리해야 합니다. TLS Secret과 애플리케이션 Secret의 키 이름이 StatefulSet의 참조와 일치해야 합니다. `kubectl apply --dry-run=server`는 스키마/권한 검사일 뿐 실제 로그인, 네트워크, 데이터베이스 복구를 검증하지 않습니다. [Envoy Gateway 설치/지원 범위](https://gateway.envoyproxy.io/docs/tasks/traffic/gatewayapi-support/).

두 Dockerfile의 Java 21·Node.js 22 빌드/실행 이미지는 multi-architecture digest로 고정되어 있습니다. 보안 패치가 나오면 새 digest로 재고정하고 이미지 스캔과 빌드를 다시 수행합니다. 이미지 레지스트리 업로드와 실제 클러스터 적용은 운영 변경 절차에서 진행합니다.

배포 후 스테이징에서 확인할 항목은 API·관리자 화면 각 두 복제본의 준비 상태, `/admin` 화면과 `/_next` 자산, `/admin/api/session` 라우팅, 외부 PostgreSQL/Redis 연결, OIDC 로그인과 로그아웃, 다른 조직 데이터 접근 거부, SSE 스트림과 graceful shutdown, telemetry 재시도/중복제거, 한 pod 재시작 후 로그 적재 재개입니다. 예시 Hikari 앱 풀은 API pod당 최대 8개로 두 pod가 최대 16개 앱 연결을 사용하며, Flyway 마이그레이션 연결은 별도입니다. DB 연결 한도에 마이그레이션·운영 여유를 더해 산정합니다. 9090 readiness는 준비 상태만, dependencies는 DB/Redis 연결을 확인합니다. 어느 쪽도 OIDC 공급자와 Anthropic/OpenAI 실제 추론의 가용성은 증명하지 않습니다. `/admin/api/ingestion`의 마지막 성공 시각과 DB 체크포인트, 각 PVC의 남은 공간을 함께 관찰합니다.

## TLS, SSE, 네트워크

예시 Gateway는 HTTPS 443만 열고 TLS를 종료합니다. 호출자가 공급자 API 키를 실수로 평문 HTTP에 보낼 수 있으므로 80 포트 리다이렉트는 두지 않았습니다. 공급자 HTTPRoute는 전체 요청 시간 제한을 해제하고, Envoy Gateway의 양쪽 스트림 유휴 시간을 360초로 설정합니다. 응답 버퍼링 및 자동 재시도 정책은 활성화하지 않습니다. 실제 SSE 청크 지연, 중간 프록시의 유휴 시간 제한, 클라이언트 연결 해제와 최대 요청 본문 크기를 스테이징에서 확인해야 합니다. 타임아웃 정책의 `Accepted=True` 상태를 확인하고 API 호출 중 15초 기본 제한이 남아 있지 않은지 시험합니다. [Gateway API 타임아웃](https://gateway-api.sigs.k8s.io/guides/user-guides/http-timeouts/), [Envoy Gateway 스트림 정책](https://gateway.envoyproxy.io/docs/tasks/traffic/grpc-timeouts/).

NetworkPolicy는 Gateway API 데이터 플레인 pod에서 API의 8080과 관리자 화면의 3000에 접근하도록 제한합니다. 관리자 pod는 내부 API 서비스의 8080과 DNS에만 연결할 수 있습니다. 외부에서 각 Service/Pod에 직접 접근하지 못하게 하고, 9090 관리 포트는 공개 HTTPRoute에 넣지 않습니다. 인증 헤더와 요청/응답 본문을 게이트웨이 프록시, APM 또는 로그 수집기가 기록하지 않도록 설정합니다. 기존 로드 밸런서가 앞에 있으면 거기에도 TLS, 유휴 시간, 본문 크기 제한을 맞춥니다.

표준 Kubernetes NetworkPolicy는 DNS 이름으로 egress를 제한하지 못합니다. 예시 정책의 외부 TCP 443은 Anthropic/OpenAI API, OIDC discovery/JWKS 및 기타 HTTPS 주소에 열립니다. 외부 PostgreSQL/Redis 포트도 예시 범위에서 열리므로, 환경별 방화벽이나 FQDN 정책을 지원하는 CNI로 목적지 범위를 좁혀야 합니다. CoreDNS pod 라벨, Envoy Gateway 데이터 플레인 namespace, monitoring namespace, DB/Redis 포트와 CIDR가 실제 환경과 다르면 정책을 수정합니다. NetworkPolicy는 이를 시행하는 CNI에서만 효과가 있습니다. [Kubernetes NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/).

## PostgreSQL 백업과 복원 훈련

DB는 tenant, 수집 자격증명, 감사 기록, 요청/체크포인트를 함께 담는 단위로 백업합니다. 특히 `request_identities`의 `purged_at`, `content_identities`, `schema_history`, `checkpoints`, `ingestion_status`, 수집 자격증명 및 감사 테이블을 요청/본문 테이블과 함께 보존해야 합니다. 식별 표식은 보관 기간이 지난 원본 JSONL의 재적재를 막기 위해 요청 본문보다 오래 남습니다. 관리형 PostgreSQL의 PITR/WAL 보관을 우선 설정하고, 논리 백업을 별도 저장소에 암호화해 보관합니다. 예시 보관 기간은 30일이며 실제 규정, 로그 보관 기간, 복구 목표에 맞춰 조정합니다. DB 백업과 각 pod의 JSONL PVC 스냅샷을 함께 보존해야 오래된 DB 상태로 복원할 때 누락분을 다시 적재할 수 있습니다. 복원 훈련 없이 RPO/RTO를 보장했다고 보고하지 않습니다.

`pg_dump`는 DB 내 데이터를 덤프하며 역할 같은 클러스터 전역 객체는 포함하지 않습니다. 복구 계정/권한과 관리형 서비스 설정은 별도로 보관하거나 배포 자동화에서 다시 생성할 수 있어야 합니다. 런타임 Flyway 마이그레이션은 이전 버전으로 자동 되돌리는 작업이 아니므로, 앱 이미지와 DB 스냅샷을 한 변경 단위로 기록합니다.

다음은 전용 서비스 연결 설정(`PGSERVICE`)과 별도 테스트 DB를 사용하는 **훈련 명령**입니다. 서비스 설정은 TLS 인증서 검증과 최소 권한을 포함해야 하며, 비밀번호를 명령 인자나 문서에 넣지 않습니다. 복원 대상은 생산 DB가 아닌 새 테스트 DB입니다.

```bash
export PGSERVICE=llm_gateway_backup
export BACKUP_FILE=/secure/backups/llm-gateway-$(date -u +%Y%m%dT%H%M%SZ).dump
pg_dump --dbname=llm_gateway --format=custom --no-owner --no-acl --file="$BACKUP_FILE"
sha256sum "$BACKUP_FILE" > "${BACKUP_FILE}.sha256"
sha256sum --check "${BACKUP_FILE}.sha256"
pg_restore --list "$BACKUP_FILE" >/dev/null

export PGSERVICE=llm_gateway_restore_drill
export DRILL_DB=llm_gateway_restore_drill_$(date -u +%Y%m%d)
createdb "$DRILL_DB"
pg_restore --exit-on-error --single-transaction --no-owner --no-acl --dbname="$DRILL_DB" "$BACKUP_FILE"
psql --dbname="$DRILL_DB" --command='SELECT count(*) FROM llm_gateway_analytics.requests;'
psql --dbname="$DRILL_DB" --command='SELECT tenant_id, count(*) FROM llm_gateway_analytics.requests GROUP BY tenant_id;'
psql --dbname="$DRILL_DB" --command='SELECT count(*) FROM llm_gateway_analytics.checkpoints;'
psql --dbname="$DRILL_DB" --command='SELECT count(*) FROM llm_gateway_analytics.request_identities;'
psql --dbname="$DRILL_DB" --command='SELECT count(*) FROM llm_gateway_analytics.content_identities;'
psql --dbname="$DRILL_DB" --command='SELECT count(*) FROM llm_gateway_analytics.collector_credentials;'
psql --dbname="$DRILL_DB" --command='SELECT count(*) FROM llm_gateway_analytics.audit_events;'
psql --dbname="$DRILL_DB" --command='SELECT count(*) FROM llm_gateway_analytics.ingestion_status;'
psql --dbname="$DRILL_DB" --command='SELECT version, success FROM llm_gateway_analytics.schema_history ORDER BY installed_rank;'
```

백업 파일 해시와 복원된 테이블/조직별 건수, 샘플 격리 조회, 복원 소요 시간, JSONL 재적재 결과를 훈련 기록에 남깁니다. 테스트 DB 정리는 운영 DBA 절차에 따릅니다. 사고 복구에서는 새 DB 인스턴스에 백업/PITR을 복원하고 읽기 전용으로 검증한 다음, 게이트웨이 쓰기를 중지하는 점검 창에서 연결 대상을 전환합니다. 원래 DB와 PVC는 검증 및 롤백이 끝날 때까지 보존합니다. 복원 상태가 최신일 수 없으므로 원본 JSONL을 재생하고 중복 제거·누락·조직 경계를 확인한 뒤 서비스를 재개합니다. `pg_restore --clean`을 생산 DB에 직접 실행하지 않습니다.

## 비밀값과 OIDC 교체

OIDC client secret 교체는 공급자에서 새 값을 발급하고 Secret 관리 시스템에 새 버전을 준비한 뒤 pod를 순차 교체합니다. 새 로그인/세션 복구/로그아웃을 확인하고 이전 secret을 폐기합니다. 공급자의 서명 키 교체 중에는 새 JWKS 키와 이전 토큰 검증 키가 토큰 만료 기간만큼 겹치게 운영합니다. issuer, audience, tenant claim, 역할 매핑을 바꿀 때는 두 조직과 여러 역할의 접근 차단 테스트를 다시 수행합니다. 잘못된 claim 매핑을 임시로 풀어서 인증을 통과시키지 않습니다.

DB/Redis 비밀번호는 서버 측 교체와 Secret 버전 교체의 순서를 맞추고, old/new 자격증명 공존 가능 여부를 먼저 확인합니다. `GW_KEY_ID_SECRET`을 바꾸면 익명화된 키 식별자 연속성이 끊기므로 백업과 변경 기록을 남깁니다. 수집 키는 조직별로 발급·폐기하고 평문 재조회나 로그 출력을 허용하지 않습니다.
