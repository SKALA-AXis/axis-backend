# axis-backend

AXIS 서비스의 SpringBoot REST API 서버입니다. 프론트 요청 수신, 이슈 카드 CRUD, Python AI 서버 위임, Slack 브리핑 발송을 담당합니다.

> 전체 프로젝트 개요는 [axis-infra](https://github.com/skala-ai-13/axis-infra)를 참조하세요.

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

```bash
# 1. 레포 클론
git clone https://github.com/skala-ai-13/axis-backend.git
cd axis-backend

# 2. 환경변수 설정
cp .env.example .env
# .env에서 DATABASE_URL, AI_SERVER_URL, SLACK_WEBHOOK_URL 등 입력

# 3. DB·Qdrant만 Docker로 먼저 실행 (axis-infra 레포 필요)
cd ../axis-infra && docker compose up -d postgres qdrant && cd ../axis-backend

# 4. SpringBoot 실행
./gradlew bootRun --args='--spring.profiles.active=local'

# 5. Swagger UI 확인
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

```bash
DATABASE_URL=postgresql://axuser:axpass@localhost:5432/axis
AI_SERVER_URL=http://localhost:8001
SLACK_WEBHOOK_URL=https://hooks.slack.com/...
JWT_SECRET=...
```

---

## CI

GitHub Actions (`.github/workflows/ci.yml`) — push / PR 시 자동 실행

```
./gradlew build
./gradlew test
```

---

## 주의사항

- `spring.jpa.hibernate.ddl-auto=create|update` 사용 금지 (운영 데이터 유실)
- Entity를 API 응답으로 직접 반환 금지 — DTO 변환 필수
- Qdrant 직접 접근 금지 — 모든 벡터 검색은 AI 서버를 통해
- `.env` 파일 커밋 금지
