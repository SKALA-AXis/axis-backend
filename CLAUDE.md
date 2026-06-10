# AXIS — 백엔드 컨텍스트 (axis-backend)

이 레포는 AXIS 서비스의 Spring Boot REST API 서버입니다. Frontend 외부 API는 `axis-infra/api/openapi.yaml`을 최우선 기준으로 구현합니다. DB 스키마는 `axis-infra/db/schema.sql`을 참고하되, 이 레포에서 필요한 변경은 Flyway migration으로 관리합니다.

## 책임

```text
axis-backend가 하는 일
├── Frontend REST API 제공
├── OpenAPI 공통 응답 래퍼 유지
├── PostgreSQL 조회/저장
├── Python AI 서버에 작업 위임
├── 이미지 파일 서빙
├── 이메일 발송 — AWS SES V2 SDK + IRSA (ses-mailer-sa)
│   ├ sender: noreply@skala-ai.com (매니저 verified)
│   └ 일일 브리핑: axis-cron-delivery CronJob → /api/pipeline/delivery → axis-ai 본문 데이터 → SES
└── 스케줄러와 파이프라인 트리거

axis-backend가 하지 않는 일
├── axis-infra 문서/스키마 직접 수정
├── 크롤링, 임베딩, LLM 직접 실행
├── Qdrant 직접 접근
└── Frontend 화면 구현
```

## 구현 기준

1. `axis-infra/api/openapi.yaml`이 API 계약의 단일 기준입니다.
2. 정상 응답은 `{ success, data, timestamp }` 구조를 유지합니다.
3. 에러 응답 코드는 OpenAPI의 공통 에러 코드 계열을 사용합니다.
4. 명세를 400줄 단위로 확인하며 작업하고, 단계별 테스트를 유지합니다.
5. 실제 DB 구현 전 도메인은 샘플 데이터를 반환하지 않고, 빈 실제 응답 구조 또는 명시적인 실패/미저장 상태를 반환합니다.
6. 화면에 표시될 수 있는 임시 데이터는 실제 결과처럼 반환하지 않습니다.

## 현재 주요 구조

```text
src/main/java/com/skala/axis/
├── config/
│   ├── SecurityConfig.java
│   ├── WebClientConfig.java
│   └── SchedulerConfig.java
├── controller/
│   ├── AuthController.java
│   ├── CardController.java
│   ├── MonitoringController.java
│   ├── BriefingController.java
│   ├── AlertController.java
│   ├── BookmarkController.java
│   ├── MixerController.java
│   ├── SettingsController.java
│   ├── AdminController.java
│   ├── PipelineController.java
│   ├── HealthController.java
│   ├── ImageController.java
│   ├── IssueCardController.java
│   ├── PeerController.java
│   └── SearchController.java
├── service/
│   ├── AiClientService.java
│   ├── IssueCardService.java
│   ├── ArticleImageService.java
│   ├── BriefingService.java
│   └── SesMailService.java          # AWS SES V2 SDK 발송 (IRSA) — SlackService 폐기 후 신설
├── domain/
├── repository/
├── dto/
└── exception/
```

## 실행

```bash
cd axis-backend
chmod +x ./gradlew
./gradlew test
```

로컬 DB와 함께 실행:

```bash
cd ../axis-infra
docker compose --profile local --env-file .env.local up -d postgres

cd ../axis-backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

`local` profile은 host 실행 기본값으로 `localhost:5432/axis`, `axuser`, `axpass`를 사용합니다. compose 내부 실행은 axis-infra가 주입하는 `SPRING_DATASOURCE_*` env가 우선합니다.

Docker:

```bash
docker build -t axis-backend:local .
docker run --rm -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/axis \
  -e SPRING_DATASOURCE_USERNAME=axuser \
  -e SPRING_DATASOURCE_PASSWORD=axpass \
  -e AI_SERVER_URL=http://host.docker.internal:8001 \
  axis-backend:local
```

## 검증

```bash
./gradlew test
./gradlew test --tests com.skala.axis.OpenApiContractSmokeTests
```

`OpenApiContractSmokeTests`는 OpenAPI 명세의 외부 API 경로를 400줄 구간별로 묶어 정상 응답 래퍼를 확인합니다.

## API 빠른 참조

상세 스펙은 항상 `axis-infra/api/openapi.yaml`을 확인합니다.

```text
GET    /health

POST   /api/auth/signup
POST   /api/auth/verify-email
POST   /api/auth/login
POST   /api/auth/logout
POST   /api/auth/refresh
GET    /api/auth/me

GET    /api/cards
GET    /api/cards/today
GET    /api/cards/{id}
POST   /api/cards/{id}/verify-link
POST   /api/cards/{id}/share

GET    /api/monitoring/overview
GET    /api/monitoring/cards/search
GET    /api/monitoring
GET    /api/monitoring/{peerId}
GET    /api/monitoring/{peerId}/cards
GET    /api/monitoring/{peerId}/financials
GET    /api/monitoring/comparison
GET    /api/monitoring/{peerId}/strategy

GET    /api/briefings/today
GET    /api/briefings
GET    /api/briefings/cards/search
POST   /api/briefings/generate
GET    /api/briefings/{briefingId}/status
GET    /api/briefings/{briefingId}

GET    /api/alerts
POST   /api/alerts/{id}/read

GET    /api/bookmarks
POST   /api/bookmarks
DELETE /api/bookmarks/{cardId}

POST   /api/mixer
POST   /api/mixer/{mixId}/share

GET    /api/settings/alert-rules
PUT    /api/settings/alert-rules
GET    /api/settings/notifications
PUT    /api/settings/notifications
PUT    /api/settings/profile
PUT    /api/settings/password
GET    /api/settings/access-logs

GET    /api/admin/peers
POST   /api/admin/peers
PUT    /api/admin/peers/{peerId}
DELETE /api/admin/peers/{peerId}
GET    /api/admin/sources
PUT    /api/admin/sources/{sourceId}
GET    /api/admin/prompts
PUT    /api/admin/prompts/{promptId}
GET    /api/admin/scheduler
PUT    /api/admin/scheduler/{jobId}
GET    /api/admin/usage
PUT    /api/admin/usage/limits
GET    /api/admin/audit-logs

GET    /api/pipeline/status
POST   /api/pipeline/trigger
```

## 주의

- `axis-backend` 밖의 파일 수정이 필요하면 먼저 요청합니다.
- `bin/`은 빌드 산출물로 취급합니다.
- `.env` 계열 파일은 커밋하지 않습니다.
- 운영/공유 DB에서 `ddl-auto=create|update`를 사용하지 않습니다.
