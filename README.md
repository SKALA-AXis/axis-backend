# axis-backend

AXIS 서비스의 Spring Boot REST API 서버입니다. Frontend가 호출하는 외부 API 계약은 `axis-infra/api/openapi.yaml`이 최우선 기준이며, DB 구조는 `axis-infra/db/schema.sql`을 참조합니다.

현재 백엔드는 OpenAPI v3 명세의 외부 API 경로를 우선 구현합니다. 일부 도메인은 실제 영속화 전 단계이므로 계약 검증용 응답 fixture를 반환하며, 이후 DB/API 서비스 구현으로 점진 교체합니다. 계약 검증용 샘플 데이터는 Java 코드가 아니라 `src/main/resources/contract-fixtures.json`에서 관리합니다.

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

## 실행 전 준비

```bash
cd axis-backend

# 최초 1회 또는 권한이 빠진 경우
chmod +x ./gradlew

# 컴파일 및 테스트
./gradlew test
```

로컬에서 DB 없이 API 계약 스모크 테스트만 확인할 때는 테스트가 H2 인메모리 DB를 사용합니다.

## 로컬 실행

PostgreSQL이 떠 있어야 애플리케이션을 정상 실행할 수 있습니다. 로컬 DB는 `axis-infra`의 docker compose 구성을 사용합니다.

```bash
cd ../axis-infra
docker compose --profile local --env-file .env.local up -d postgres

cd ../axis-backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

`local` profile의 host 실행 기본값은 `jdbc:postgresql://localhost:5432/axis`, `axuser`, `axpass`입니다. 다른 DB나 Cloud DB에 붙을 때만 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`를 환경 변수로 넘깁니다.

확인 URL:

| URL | 설명 |
|---|---|
| `http://localhost:8080/health` | 서버 헬스체크 |
| `http://localhost:8080/swagger-ui` | Swagger UI |
| `http://localhost:8080/api-docs` | SpringDoc JSON |

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
| `IMAGE_STORAGE_PATH` | `/data/images` | 카드 이미지 저장 볼륨 경로 |
| `AXIS_SHARE_BASE_URL` | `https://axis.local` | 카드/믹서기 공유 링크 base URL |
| `AXIS_SCHEDULER_INGESTION_PEER_IDS` | `samsung_sds,lg_cns` | 수집 스케줄러가 트리거할 Peer ID 목록 |
| `SLACK_WEBHOOK_URL` | empty | Slack 브리핑 Webhook |

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
├── controller/      OpenAPI 외부 API 경로
├── domain/          JPA Entity
├── dto/             공통 응답 및 기존 DTO
├── exception/       공통 에러 응답 처리
├── repository/      Spring Data JPA Repository
└── service/         비즈니스 로직, AI 호출, 계약 fixture
```

## 개발 원칙

- API 작업은 `axis-infra/api/openapi.yaml`을 최우선 기준으로 맞춥니다.
- 정상 응답은 `{ success, data, timestamp }` 구조를 유지합니다.
- 에러 응답은 OpenAPI의 공통 에러 코드 계열을 사용합니다.
- Entity를 외부 응답으로 직접 노출하는 구현은 새 API에서 피합니다.
- DB 변경이 필요하면 `axis-infra/db/schema.sql`을 직접 수정하지 않고, `axis-backend/src/main/resources/db/migration`에 Flyway 마이그레이션을 추가합니다.
- AI 호출은 `AiClientService`를 통해서만 수행합니다.
