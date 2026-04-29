# axis-backend

AXIS 서비스의 SpringBoot REST API 서버입니다. 프론트 요청 수신, 이슈 카드 CRUD, Python AI 서버 위임, Slack 브리핑 발송을 담당합니다.

> 전체 프로젝트 개요는 [axis-infra](https://github.com/SKALA-AXis/axis-infra)를 참조하세요.

---

## 🚀 실행 방법 (Cloud / Local 두 가지 모드)

DB 프로파일에 따라 두 가지 모드를 지원합니다. **코드는 동일**, 환경 변수(특히 `SPRING_DATASOURCE_URL`)만 바뀝니다.

| 모드 | DB | 환경 파일 | 사용 환경 |
|---|---|---|---|
| **Cloud** (기본) | Supabase Postgres pooler:6543 (sslmode=require) | `.env` | 팀 공용, 데모, PR 검증 |
| **Local** | docker postgres:5432 | `.env.local` (axis-infra 쪽) | 오프라인, 스키마 실험 |

> Spring profile (`local` / `prod`) 은 **로깅·디버그 출력 분기용**입니다. DB 연결은 `SPRING_DATASOURCE_URL` env 가 직접 결정합니다 — profile 과 별개로 동작.

### Mode 1 — Cloud 모드로 호스트에서 실행 (권장)

```bash
# 1. 레포 클론
git clone https://github.com/SKALA-AXis/axis-backend.git
cd axis-backend

# 2. .env 작성 (팀 공용 .env 받아서 axis-backend/.env 로 저장)
#    SPRING_DATASOURCE_URL=jdbc:postgresql://aws-1-...pooler.supabase.com:6543/postgres?sslmode=require&prepareThreshold=0
#    SPRING_DATASOURCE_USERNAME=postgres.<project-ref>
#    SPRING_DATASOURCE_PASSWORD=<percent-encoded-password>
#    AI_SERVER_URL=http://localhost:8001

# 3. SpringBoot 실행
./gradlew bootRun --args='--spring.profiles.active=local'
# 또는 env 만 set 해서 Spring profile 없이도 동일하게 동작

# 4. Swagger UI
open http://localhost:8080/swagger-ui
```

### Mode 2 — Local 모드 (docker postgres 컨테이너)

```bash
# 1. axis-infra 에서 postgres 컨테이너 기동
cd ../axis-infra
docker compose --profile local --env-file .env.local up -d postgres
cd ../axis-backend

# 2. .env 의 SPRING_DATASOURCE_URL 을 로컬 컨테이너용으로 교체
#    SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/axis
#    SPRING_DATASOURCE_USERNAME=axuser
#    SPRING_DATASOURCE_PASSWORD=axpass

# 3. SpringBoot 실행 (동일)
./gradlew bootRun --args='--spring.profiles.active=local'
```

### Mode 3 — Docker 컨테이너 안에서 backend 서비스로 실행

axis-infra 의 docker compose 가 env 를 컨테이너에 주입합니다.

```bash
# Cloud
cd ../axis-infra && docker compose up -d backend

# Local
cd ../axis-infra && docker compose --profile local --env-file .env.local up -d
```

### 환경 설정 체크리스트 (신규 팀원)

- [ ] JDK 17 설치 (sdkman 권장: `sdk install java 17.0.10-tem`)
- [ ] `./gradlew build` 한 번 돌려 의존성 캐시
- [ ] Cloud 용 `.env` 받기 — Supabase pooler DSN 포함, **percent-encoding 필수** (`%` → `%25`, `*` → `%2A`)
- [ ] (옵션) Local 모드 쓸 거면 axis-infra 에서 `--profile local` 컨테이너 기동
- [ ] Flyway 가 시작 시 자동 마이그레이션 실행 — 별도 `psql -f` 불필요

---

## 기술 스택

| 항목 | 내용 |
|---|---|
| 언어 | Java 17 |
| 프레임워크 | Spring Boot 3.x |
| 빌드 | Gradle |
| DB | PostgreSQL 16.x (Spring Data JPA) |
| HTTP 클라이언트 | WebClient (비동기, AI 서버 호출용) |
| 스케줄러 | Spring `@Scheduled` |
| API 문서 | SpringDoc OpenAPI (Swagger UI) |
| 테스트 | JUnit 5 + Mockito |

---

## 프로젝트 구조

```
src/main/java/com/skala/axis/
├── config/
│   ├── SecurityConfig.java       JWT 인증 설정
│   ├── WebClientConfig.java      AI 서버 WebClient 빈
│   └── SchedulerConfig.java      파이프라인 트리거 스케줄
├── controller/
│   ├── IssueCardController.java  이슈 카드 조회
│   ├── SearchController.java     AI 검색
│   ├── PeerController.java       Peer사 정보
│   ├── AlertController.java      알림 설정
│   ├── PipelineController.java   파이프라인 수동 트리거
│   └── HealthController.java     헬스체크 (/health)
├── service/
│   ├── IssueCardService.java     이슈 카드 비즈니스 로직
│   ├── AiClientService.java      Python AI 서버 호출 전담
│   ├── BriefingService.java      브리핑 생성
│   └── SlackService.java         Slack Webhook 발송
├── repository/                   Spring Data JPA Repository
├── domain/                       JPA Entity (IssueCard, RawArticle, PeerCompany)
├── dto/                          Request / Response DTO (ApiResponse, IssueCardResponse, SearchRequest, SearchResponse)
└── exception/                    GlobalExceptionHandler, AiServerException
```

---

## 로컬 개발 세팅

> Cloud / Local 모드 분기는 위 [🚀 실행 방법](#-실행-방법-cloud--local-두-가지-모드) 섹션 참조. 아래는 신규 팀원이 처음 환경을 세팅할 때 따라가는 절차입니다.

```bash
# 1. 레포 클론
git clone https://github.com/SKALA-AXis/axis-backend.git
cd axis-backend

# 2-A. Cloud 모드: 팀 공용 .env 받아서 axis-backend/.env 로 저장
#      (Supabase pooler:6543 DSN, percent-encoded password 포함)

# 2-B. Local 모드: axis-infra 에서 컨테이너 기동 + .env 의 DSN 을 localhost 로 교체
cd ../axis-infra && docker compose --profile local --env-file .env.local up -d postgres && cd ../axis-backend

# 3. SpringBoot 실행
./gradlew bootRun --args='--spring.profiles.active=local'

# 4. Swagger UI 확인
open http://localhost:8080/swagger-ui
```

---

## 주요 API 엔드포인트

> 상세 스펙은 [axis-infra/api/openapi.yaml](https://github.com/skala-ai-13/axis-infra/blob/main/api/openapi.yaml)이 Single Source of Truth입니다.

| Method | Path | 설명 |
|---|---|---|
| `GET` | `/api/issues` | 이슈 카드 목록 (중요도 순) |
| `GET` | `/api/issues/{id}` | 이슈 카드 상세 |
| `GET` | `/api/issues/today` | 오늘의 브리핑 |
| `POST` | `/api/search` | AI 대화형 검색 |
| `GET` | `/api/peers` | Peer사 목록 |
| `GET` | `/api/peers/{peerId}/issues` | Peer사별 이슈 타임라인 |
| `GET` | `/health` | 헬스체크 |
| `POST` | `/api/pipeline/trigger` | 파이프라인 수동 실행 |

---

## 스케줄러

| 스케줄 | 작업 |
|---|---|
| 매시간 정각 | 수집 파이프라인 트리거 (`POST /pipeline/run`) |
| 평일 오전 08:30 | 전달 파이프라인 트리거 → Slack 브리핑 발송 |
| 매주 월요일 09:00 | 약한 신호 감지기 실행 |

---

## Python AI 서버 통신 원칙

Python AI 서버 호출은 **`AiClientService` 단독으로만** 합니다. 다른 Service에서 직접 호출하지 않습니다.

```
SpringBoot → AiClientService → POST http://ai:8001/{endpoint}
```

| 엔드포인트 | 타임아웃 |
|---|---|
| `/search` | 10초 |
| `/gen-search` | 30초 |
| `/pipeline/run` | 5초 (비동기, 결과 안 기다림) |

---

## 환경 변수

`.env` 와 axis-infra 의 `.env` / `.env.local` 은 동일한 키 셋을 공유합니다. backend 입장에서 핵심은 JDBC DSN.

```bash
# DB — Cloud (Supabase Transaction Pooler, prepareThreshold=0 = PgBouncer 호환)
SPRING_DATASOURCE_URL=jdbc:postgresql://aws-1-ap-northeast-2.pooler.supabase.com:6543/postgres?sslmode=require&prepareThreshold=0
SPRING_DATASOURCE_USERNAME=postgres.<project-ref>
SPRING_DATASOURCE_PASSWORD=<percent-encoded-password>

# DB — Local (.env.local 에서 사용)
# SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/axis
# SPRING_DATASOURCE_USERNAME=axuser
# SPRING_DATASOURCE_PASSWORD=axpass

# AI 서버 — 호스트 개발: http://localhost:8001 / 컨테이너: http://ai:8001
AI_SERVER_URL=http://localhost:8001

# 알림 / 인증
SLACK_WEBHOOK_URL=https://hooks.slack.com/...
JWT_SECRET=...

# Spring profile (로깅 분기용 — DB 연결과 무관)
SPRING_PROFILES_ACTIVE=local
```

---

## CI

GitHub Actions (`.github/workflows/ci.yml`) — push / PR 시 자동 실행

```
./gradlew build
./gradlew test
```

> CI 상세 설명 및 실패 대응 방법: [axis-infra/docs/CI.md](https://github.com/SKALA-AXis/axis-infra/blob/develop/docs/CI.md)

---

## 주의사항

- `spring.jpa.hibernate.ddl-auto=create|update` 사용 금지 (운영 데이터 유실)
- Entity를 API 응답으로 직접 반환 금지 — DTO 변환 필수
- Qdrant 직접 접근 금지 — 모든 벡터 검색은 AI 서버를 통해
- `.env` 파일 커밋 금지
