# axis-backend

AXIS 서비스의 Spring Boot REST API 서버입니다. Frontend가 호출하는 외부 API 계약은 `axis-infra/api/openapi.yaml`이 최우선 기준이며, DB 구조는 `axis-infra/db/schema.sql`을 참조합니다.

현재 백엔드는 OpenAPI v3 명세의 외부 API 경로를 우선 구현합니다. 실제 영속화가 없는 도메인은 샘플 데이터를 반환하지 않고, 빈 실제 응답 구조 또는 명시적인 실패/미저장 상태를 반환합니다.

## 기준 문서

| 우선순위 | 문서 | 용도 |
|---|---|---|
| 1 | `axis-infra/api/openapi.yaml` | Frontend ↔ Backend REST API 계약의 단일 기준 |
| 2 | `axis-infra/db/schema.sql` | PostgreSQL 테이블/컬럼 기준 |
| 3 | `axis-infra/api/ai-internal-api.yaml` | Backend ↔ AI 내부 연계 참고 |

다른 문서와 충돌하면 OpenAPI 명세를 우선합니다.

## 기술 스택

| 항목 | 내용 |
|---|---|
| Runtime | Java 17 |
| Framework | Spring Boot 3.3.4 |
| Build | Gradle 8.8 Wrapper |
| DB | PostgreSQL, Spring Data JPA, Flyway |
| HTTP Client | WebClient |
| API Docs | SpringDoc OpenAPI |
| Test | JUnit 5, MockMvc, H2 |

## 사전 요구사항

| 항목 | 값 |
|---|---|
| JDK | **17** — Gradle toolchain 이 17 을 사용/자동 프로비저닝합니다. 미설치 시 JDK17 설치 또는 `JAVA_HOME` 을 17 로 지정 |
| Gradle | Wrapper 포함(`./gradlew`) — 별도 설치 불필요 |
| PostgreSQL | 앱 실행 시에만 필요. **테스트는 H2 인메모리라 DB 불필요** |

> macOS 에 여러 JDK 가 있으면 toolchain 이 17 을 못 찾을 수 있습니다. JDK17 설치 후 지정하세요:
> `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` (Homebrew `openjdk@17` 등).

## 빠른 검증 (DB·클러스터 불필요)

```bash
cd axis-backend
chmod +x ./gradlew     # 최초 1회 권한
./gradlew test         # H2 인메모리로 전체 테스트 — 외부 DB 없이 동작
```

계약 스모크만: `./gradlew test --tests com.skala.axis.OpenApiContractSmokeTests`

## 로컬 실행 (호스트, 클러스터 불필요)

backend 를 호스트에서 직접 띄워 빠르게 iterate(HMR/디버거). DB 는 로컬 docker 컨테이너로:

```bash
# 1. 로컬 docker DB (axis-infra 의 postgres 컨테이너만)
cd ../axis-infra
cp .env.local.example .env 2>/dev/null || true
docker compose --profile local up -d postgres

# 2. backend 호스트 실행 (:8080)
cd ../axis-backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

`bootRun` 은 `axis-backend/.env` → `.env.local` 을 자동으로 읽어 환경변수로 주입합니다(빌드 스크립트 기능, 시스템 환경변수가 우선). 로컬 전용 설정은 `axis-backend/.env.local` 에 두면 됩니다(커밋 금지).

- **전체 스택**(backend+ai+frontend+DB)을 한 번에: [`axis-infra/README.md`](../axis-infra/README.md) 의 *빠른 시작* → `docker compose --profile local up -d --build`
- **팀 개발자**(SKALA EKS 접근 시): `cd ../axis-infra && make up-cluster` (공용 클러스터 DB 연결)

`local` profile 의 host 실행 기본값은 `jdbc:postgresql://localhost:5432/axis`, `axuser`, `axpass`. 다른 DB 에 붙을 때만 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` 환경 변수로 override.

`local` profile 은 JPA `ddl-auto=none` 으로 동작한다. cluster DB / port-forward DB 처럼 스키마가 코드보다 한두 migration 뒤처진 환경에서도 boot 자체는 가능하게 두고, 스키마 검증은 아래의 격리 docker DB + Flyway enable 경로에서 수행한다.

### Flyway 자동 차단 (PR #20 부터)

`local` profile 로 실행 시 Flyway 가 **자동 비활성** — backend 시작 시 schema migrate 안 함. cluster DB 를 port-forward 로 보면서 dev 하는 중 새 migration 파일이 silent 적용되는 사고 방지.

| Profile | Flyway | 사용 |
|---|---|---|
| `local` (host bootRun, default) | **비활성** | 일상 dev — schema 안 건드림 |
| `prod` (cluster pod) | 활성 | 정상 CI/CD 경로 |

**새 migration 검증 워크플로** (반드시 Mode B = 로컬 docker DB 에서):

```bash
# 1. 격리 docker postgres 띄움
cd ../axis-infra
docker compose --profile local --env-file .env.local up -d postgres

# 2. backend 를 명시 Flyway enable 로 실행 (local profile 의 차단 우회)
cd ../axis-backend
./gradlew bootRun --args='--spring.profiles.active=local --spring.flyway.enabled=true --spring.jpa.hibernate.ddl-auto=validate'

# 3. 검증 후 PR → 머지 → ArgoCD 자동 sync → cluster pod 가 prod profile 로 자동 migrate
```

**절대 금지**: cluster DB 가 port-forward 로 연결된 상태에서 위의 `--spring.flyway.enabled=true` override 실행. cluster DB 에 silent migrate → PR 없이 schema 변경 → 다음 정상 deploy 시 checksum mismatch 사고 (`010226e` 의 V1/V11 충돌 사례 참조).

확인 URL:

| URL | 설명 |
|---|---|
| `http://localhost:8080/health` | 서버 헬스체크 |
| `http://localhost:8080/swagger-ui` | Swagger UI |
| `http://localhost:8080/api-docs` | SpringDoc JSON |

### 에이전트 진단용 Swagger API

`local` profile 에서는 `axis.agent-test.enabled=true`가 기본값이라 Swagger UI의
`Agent Diagnostics` 태그에서 에이전트 동작을 직접 확인할 수 있습니다.
`prod` profile 에서는 항상 비활성입니다.

| URL | 용도 |
|---|---|
| `GET /api/dev/agents/health` | backend → axis-ai 연결 상태 확인 |
| `POST /api/dev/agents/run` | `agent_type`에 따라 axis-ai 내부 endpoint 직접 호출 |
| `POST /api/dev/agents/{agentType}/run` | path로 agent type 지정 후 직접 호출 |
| `GET /api/dev/agents/results/types` | 조회 가능한 에이전트 결과 저장소 목록 |
| `GET /api/dev/agents/results?type=all` | 최근 mixer/insight/global/briefing/integrated issue 결과 조회 |
| `GET /api/dev/agents/results/{type}/{id}` | 저장된 결과 row 상세 조회 |

지원 `agent_type`: `insight`, `mixer`, `global_trends`, `briefing`,
`link_verify`, `pipeline`, `peer`, `chat`, `weak_signal`.

요청 body에 `payload`를 넣으면 해당 JSON이 axis-ai 요청 body로 그대로 전달됩니다.
`payload`가 없으면 Swagger schema의 `card_ids`, `integrated_issue_ids`,
`company_ids`, `message` 같은 편의 필드로 axis-ai 요청 body를 구성합니다.

## Docker 실행

이미지 빌드:

```bash
docker build -t axis-backend:local .
```

컨테이너 실행:

```bash
docker run --rm -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/axis \
  -e SPRING_DATASOURCE_USERNAME=axuser \
  -e SPRING_DATASOURCE_PASSWORD=axpass \
  -e AI_SERVER_URL=http://host.docker.internal:8001 \
  axis-backend:local
```

통합 실행은 `axis-infra`에서 수행합니다.

```bash
cd ../axis-infra
docker compose --profile local --env-file .env.local up -d backend
```

## 환경 변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | Spring profile |
| `SPRING_DATASOURCE_URL` | local profile: `jdbc:postgresql://localhost:5432/axis` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | local profile: `axuser` | DB 사용자 |
| `SPRING_DATASOURCE_PASSWORD` | local profile: `axpass` | DB 비밀번호 |
| `AI_SERVER_URL` | `http://localhost:8001` | axis-ai API 주소 |
| `AXIS_CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:3100` | 브라우저 프론트 개발 서버 허용 origin |
| `IMAGE_STORAGE_PATH` | `/data/images` | 카드 이미지 저장 볼륨 경로 |
| `AXIS_SHARE_BASE_URL` | `https://axis.local` | 카드/믹서기 공유 링크 base URL |
| `AXIS_SCHEDULER_INGESTION_PEER_IDS` | `samsung_sds,lg_cns` | 수집 스케줄러가 트리거할 Peer ID 목록 |
| `AWS_REGION` | `ap-northeast-2` | AWS SES 발송 region (운영 — IRSA 통한 자동 주입) |
| `MAIL_FROM` | `noreply@skala-ai.com` | SES 발신자 (운영 — verified domain) |
| `BRIEFING_RECIPIENTS` | empty (운영 시 axis-config 박음) | 일일 브리핑 수신자 comma-separated |

> 운영 배포 시: backend pod 의 `serviceAccountName: ses-mailer-sa` 가 IRSA 통해 SES 권한 받음. SMTP credentials 불필요. 자세한 spec: [axis-infra/docs/SES_INTEGRATION.md](../axis-infra/docs/SES_INTEGRATION.md).

`.env`, `.env.local`은 커밋하지 않습니다.

## API 구현 범위

OpenAPI v3 명세의 외부 API 경로를 400줄 단위로 확인하고, 각 구간에 대한 스모크 테스트를 추가했습니다.

| 명세 구간 | 주요 경로 |
|---|---|
| 1-400 | `/health`, `/api/auth/signup`, `/api/auth/verify-email`, `/api/auth/login` |
| 401-800 | `/api/auth/logout`, `/api/auth/refresh`, `/api/auth/me`, `/api/cards/**`, `/api/monitoring/overview` |
| 801-1200 | `/api/monitoring/**`, `/api/briefings/today` |
| 1201-1600 | `/api/briefings/**`, `/api/alerts/**` |
| 1601-2000 | `/api/bookmarks/**`, `/api/cards/{id}/share`, `/api/mixer/**`, `/api/settings/alert-rules`, `/api/settings/notifications`, `/api/settings/profile` |
| 2001-2400 | `/api/settings/password`, `/api/settings/access-logs`, `/api/admin/peers`, `/api/admin/sources`, `/api/admin/prompts`, `/api/admin/scheduler` |
| 2401-2665 | `/api/admin/usage`, `/api/admin/audit-logs`, `/api/pipeline/**` |
| 2660-4305 | 공통 응답, 에러, enum, 도메인 schema 검증 기준 |

대표 컨트롤러:

| Controller | 책임 |
|---|---|
| `AuthController` | 인증 API |
| `CardController` | 카드 뉴스, 링크 검증, 공유 |
| `MonitoringController` | 모니터링 개요, Peer 상세, 재무/전략 |
| `BriefingController` | 브리핑 목록, 생성, 상태, 상세 |
| `AlertController` | 알림 목록, 읽음 처리 |
| `BookmarkController` | 북마크 |
| `MixerController` | 믹서기 실행, 공유 |
| `SettingsController` | 사용자 설정 |
| `AdminController` | 관리자 콘솔 |
| `PipelineController` | 파이프라인 상태/트리거 |

## 검증

전체 테스트:

```bash
./gradlew test
```

계약 스모크 테스트만 실행:

```bash
./gradlew test --tests com.skala.axis.OpenApiContractSmokeTests
```

검증 내용:

| 검증 | 내용 |
|---|---|
| 단계별 API 검증 | OpenAPI 400줄 구간별 대표 경로가 정상 HTTP status 반환 |
| 공통 래퍼 검증 | 정상 응답이 `success`, `data`, `timestamp` 포함 |
| Spring context 검증 | JPA/H2 기반 애플리케이션 컨텍스트 로딩 |

## 프로젝트 구조

```text
src/main/java/com/skala/axis/
├── config/          Security, WebClient, Scheduler 설정
├── controller/      OpenAPI 외부 API 경로 (25개) + dev/내부용 (OpenAPI 비대상)
├── domain/          JPA Entity
├── dto/             공통 응답 및 기존 DTO
├── exception/       공통 에러 응답 처리
├── query/           JDBC 기반 read-model 조회
├── repository/      Spring Data JPA Repository
├── security/        인증/인가
└── service/         비즈니스 로직, AI 호출, SES 발송, 실제 저장소 조회
```

## 개발 원칙

- API 작업은 `axis-infra/api/openapi.yaml`을 최우선 기준으로 맞춥니다.
- 정상 응답은 `{ success, data, timestamp }` 구조를 유지합니다.
- 에러 응답은 OpenAPI의 공통 에러 코드 계열을 사용합니다.
- Entity를 외부 응답으로 직접 노출하는 구현은 새 API에서 피합니다.
- DB 변경이 필요하면 `axis-infra/db/schema.sql`을 직접 수정하지 않고, `axis-backend/src/main/resources/db/migration`에 Flyway 마이그레이션을 추가합니다 (V41+ 진실은 Flyway, schema.sql 은 스냅샷+동기화).
- **공유 클러스터 DB에 flyway migrate 는 배포 경로로만.** 로컬 스키마 실험은 docker postgres 에서 — 운영 DB는 `beforeMigrate__prod_guard.sql` 가드가 배포 경로 밖 migrate 를 차단합니다 (infra CONVENTION §15).
- 이미 적용된 마이그레이션 파일은 수정하지 않습니다 — 변경은 새 V번호로.
- AI 호출은 `AiClientService`를 통해서만 수행합니다.
