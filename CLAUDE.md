# AXIS — 백엔드 컨텍스트 (axis-backend)

> 이 레포는 AXIS 서비스의 SpringBoot REST API 서버입니다.
> 프론트 요청 수신, PostgreSQL CRUD, Python AI 서버 위임, Slack 발송을 담당합니다.
> 전체 프로젝트 맥락은 axis-infra/CLAUDE.md를 참조하세요.

---

## 이 레포의 책임

```
axis-backend가 하는 일:
├── 프론트 요청 수신 및 JWT 인증
├── 이슈 카드 CRUD (PostgreSQL 직접 조회)
├── Python AI 서버에 작업 위임 (WebClient HTTP 호출)
├── Slack Webhook 발송 (브리핑·긴급 알림)
├── 스케줄러 (AI 파이프라인 트리거)
└── API 명세 자동 문서화 (Swagger UI)

axis-backend가 하지 않는 일:
├── 크롤링 (axis-ai 담당)
├── 임베딩·벡터 검색 (axis-ai 담당)
├── LLM 호출 (axis-ai 담당)
└── Qdrant 직접 접근 금지
```

---

## 프로젝트 구조

```
axis-backend/
├── CLAUDE.md
├── build.gradle
├── settings.gradle
├── .github/
│   └── workflows/
│       └── ci.yml                   ← Java 빌드·테스트 자동화
├── src/
│   └── main/
│       ├── java/
│       │   └── com/skala/axis/
│       │       ├── AxisApplication.java
│       │       ├── config/
│       │       │   ├── SecurityConfig.java
│       │       │   ├── WebClientConfig.java    ← AI 서버 WebClient 설정
│       │       │   └── SchedulerConfig.java    ← 크롤링 트리거 스케줄
│       │       ├── controller/
│       │       │   ├── IssueCardController.java
│       │       │   ├── SearchController.java
│       │       │   ├── PeerController.java
│       │       │   └── AlertController.java
│       │       ├── service/
│       │       │   ├── IssueCardService.java
│       │       │   ├── AiClientService.java    ← Python AI 서버 호출 전담
│       │       │   ├── SlackService.java
│       │       │   └── BriefingService.java
│       │       ├── repository/
│       │       │   ├── IssueCardRepository.java
│       │       │   ├── RawArticleRepository.java
│       │       │   └── PeerCompanyRepository.java
│       │       ├── domain/
│       │       │   ├── IssueCard.java
│       │       │   ├── RawArticle.java
│       │       │   └── PeerCompany.java
│       │       ├── dto/
│       │       │   ├── IssueCardResponse.java
│       │       │   ├── SearchRequest.java
│       │       │   └── SearchResponse.java
│       │       └── exception/
│       │           ├── GlobalExceptionHandler.java
│       │           └── AiServerException.java
│       └── resources/
│           ├── application.yml
│           ├── application-local.yml
│           └── application-prod.yml
└── src/test/
    └── java/com/skala/axis/
        ├── controller/
        └── service/
```

---

## 기술 스택

```
언어             Java 17
프레임워크        Spring Boot 3.x
빌드 도구         Gradle
ORM              Spring Data JPA + Hibernate
DB               PostgreSQL 16.x
인증             Spring Security + JWT
HTTP 클라이언트   Spring WebFlux (WebClient) — AI 서버 비동기 호출
스케줄러          Spring @Scheduled
문서화           SpringDoc OpenAPI (Swagger UI 자동 생성)
테스트           JUnit 5 + Mockito
```

---

## build.gradle 주요 의존성

```groovy
dependencies {
    // 핵심
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-webflux'  // WebClient
    implementation 'org.springframework.boot:spring-boot-starter-validation'

    // DB
    runtimeOnly 'org.postgresql:postgresql'
    implementation 'org.flywaydb:flyway-core'                             // DB 마이그레이션

    // JWT
    implementation 'io.jsonwebtoken:jjwt-api:0.12.x'
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.x'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.x'

    // API 문서
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.x'

    // 유틸
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    implementation 'com.fasterxml.jackson.core:jackson-databind'

    // 테스트
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.security:spring-security-test'
}
```

---

## API 엔드포인트 목록

> 상세 스펙은 axis-infra/api/openapi.yaml이 Single Source of Truth입니다.
> 이 목록은 빠른 참조용입니다.

### 이슈 카드

```
GET    /api/issues                   이슈 카드 목록 조회 (중요도 순 정렬)
GET    /api/issues/{id}              이슈 카드 상세 조회
GET    /api/issues/today             오늘의 브리핑 (당일 이슈)
```

### 검색

```
POST   /api/search                   AI 대화형 검색 (Generative Search)
GET    /api/search/suggestions       검색어 추천
GET    /api/search/history           검색 히스토리
```

### Peer사

```
GET    /api/peers                    모니터링 중인 Peer사 목록
GET    /api/peers/{peerId}/issues    Peer사별 이슈 타임라인
```

### 알림

```
GET    /api/alerts/settings          알림 설정 조회
PUT    /api/alerts/settings          알림 설정 변경
POST   /api/alerts/test              테스트 알림 발송
```

### 관리

```
GET    /health                       헬스체크
GET    /api/pipeline/status          파이프라인 실행 상태
POST   /api/pipeline/trigger         수동 파이프라인 실행 (관리자용)
```

---

## Python AI 서버 통신 — AiClientService

SpringBoot에서 Python AI 서버를 호출하는 모든 로직은 `AiClientService`에 집중합니다.
다른 Service에서 AI 서버를 직접 호출하지 말 것.

```java
@Service
public class AiClientService {

    private final WebClient webClient;

    // AI 서버 기본 URL: http://ai:8001 (Docker 내부 네트워크)
    // 로컬 개발: http://localhost:8001

    // 하이브리드 검색 요청
    public Mono<SearchResponse> search(SearchRequest request) {
        return webClient.post()
            .uri("/search")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(SearchResponse.class)
            .timeout(Duration.ofSeconds(10))  // 10초 타임아웃
            .onErrorMap(TimeoutException.class,
                ex -> new AiServerException("검색 타임아웃"));
    }

    // 수집 파이프라인 트리거
    public Mono<Void> triggerPipeline(List<String> peerIds) {
        return webClient.post()
            .uri("/pipeline/run")
            .bodyValue(Map.of("peer_ids", peerIds, "trigger_type", "scheduled"))
            .retrieve()
            .bodyToMono(Void.class)
            .timeout(Duration.ofSeconds(5));
    }
}
```

### AI 서버 호출 타임아웃 기준

| 엔드포인트 | 타임아웃 | 이유 |
|---|---|---|
| /search | 10초 | Reranker 포함 |
| /gen-search | 30초 | LLM 생성 포함 |
| /pipeline/run | 5초 | 비동기 실행 (결과 안 기다림) |
| /weak-signal/run | 5초 | 비동기 실행 |

---

## 스케줄러 설정

```java
@Component
public class PipelineScheduler {

    // 매시간 수집 파이프라인 트리거
    @Scheduled(cron = "0 0 * * * *")
    public void triggerIngestionPipeline() {
        aiClientService.triggerPipeline(List.of("samsung_sds", "lg_cns"))
            .subscribe();  // 논블로킹, 결과 안 기다림
    }

    // 매일 오전 8:30 브리핑 생성 및 Slack 발송
    @Scheduled(cron = "0 30 8 * * MON-FRI")
    public void sendDailyBriefing() {
        briefingService.generateAndSend();
    }

    // 매주 월요일 오전 9시 약한 신호 감지
    @Scheduled(cron = "0 0 9 * * MON")
    public void triggerWeakSignalDetection() {
        aiClientService.triggerWeakSignal().subscribe();
    }
}
```

---

## DB 엔티티 — 핵심 테이블

> DB 스키마 원본은 axis-infra/db/schema.sql이 Single Source of Truth입니다.
> JPA Entity는 스키마와 반드시 일치해야 합니다.

```java
@Entity
@Table(name = "issue_cards")
public class IssueCard {
    @Id
    private String id;                    // 'IC-20260420-001'

    private String peerId;                // 'samsung_sds' | 'lg_cns'
    private Long clusterId;

    @Column(nullable = false)
    private String title;

    @Type(JsonType.class)
    private List<String> summaryLines;    // JSONB

    private String eventType;            // 6개 taxonomy
    private String importance;           // urgent | notable | reference
    private Float importanceScore;

    @Type(JsonType.class)
    private Map<String, Object> implication;  // JSONB

    @Type(JsonType.class)
    private List<Map<String, Object>> sources;  // JSONB

    private LocalDateTime createdAt;
}
```

---

## DB 마이그레이션 (Flyway)

스키마 변경은 직접 수정하지 않고 마이그레이션 파일로 관리합니다.

```
src/main/resources/db/migration/
├── V1__init_schema.sql           ← 초기 스키마
├── V2__add_weak_signal_table.sql ← 테이블 추가
└── V3__add_index_peer_id.sql     ← 인덱스 추가
```

```bash
# 마이그레이션 실행 (자동 — 애플리케이션 시작 시)
# 수동 실행이 필요하면:
./gradlew flywayMigrate
```

---

## 인증 구조 (JWT)

```
로그인 없이 사용하는 내부 시스템이지만,
API Key 기반 인증으로 외부 노출 방지

헤더: Authorization: Bearer {jwt_token}
토큰 만료: 24시간
갱신: /api/auth/refresh
```

---

## 응답 형식 통일

모든 API 응답은 아래 형식을 따릅니다.

```java
// 성공
{
    "success": true,
    "data": { ... },
    "timestamp": "2026-04-20T09:00:00Z"
}

// 실패
{
    "success": false,
    "error": {
        "code": "AI_SERVER_TIMEOUT",
        "message": "AI 서버 응답 시간 초과",
        "detail": "..."
    },
    "timestamp": "2026-04-20T09:00:00Z"
}
```

---

## 예외 처리 원칙

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    // AI 서버 타임아웃
    @ExceptionHandler(AiServerException.class)
    public ResponseEntity<?> handleAiServerException(AiServerException e) {
        // 503 반환, 재시도 안내
    }

    // 이슈 카드 없음
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<?> handleNotFound(EntityNotFoundException e) {
        // 404 반환
    }
}
```

---

## application.yml 구조

```yaml
spring:
  datasource:
    url: ${DATABASE_URL:jdbc:postgresql://localhost:5432/axis}
    username: ${DB_USERNAME:axuser}
    password: ${DB_PASSWORD:axpass}
  jpa:
    hibernate:
      ddl-auto: validate   # 운영에서는 validate만. create/update 절대 금지.
    show-sql: false        # 운영에서 false

ai:
  server:
    base-url: ${AI_SERVER_URL:http://localhost:8001}
    timeout: 30s

slack:
  webhook-url: ${SLACK_WEBHOOK_URL}
  briefing-time: "08:30"

springdoc:
  api-docs:
    path: /api-docs
  swagger-ui:
    path: /swagger-ui
```

---

## Swagger UI

애플리케이션 실행 후 접근:
```
http://localhost:8080/swagger-ui
```

API 테스트 및 스펙 확인용. 팀원 누구나 SpringBoot 실행 후 브라우저에서 직접 테스트 가능.

---

## GitHub Actions CI

```yaml
# .github/workflows/ci.yml
on: [push, pull_request]
jobs:
  build-and-test:
    steps:
      - ./gradlew build
      - ./gradlew test
      - ./gradlew jacocoTestReport    # 테스트 커버리지
```

---

## 코딩 컨벤션

```
언어:     Java 17
스타일:    Google Java Style Guide
Lombok:   @Data, @Builder, @RequiredArgsConstructor 적극 활용
DTO:      Request/Response 클래스 분리 (Entity 직접 노출 금지)
Service:  비즈니스 로직만. DB 쿼리는 Repository, AI 호출은 AiClientService
테스트:   Service 단위 테스트 필수. Controller는 통합 테스트로.
```

---

## 로컬 개발 환경 세팅

```bash
# 1. DB, Qdrant만 Docker로 실행
cd ../axis-infra
docker compose up -d postgres qdrant

# 2. 환경 변수 설정
cp .env.example .env
# .env 파일에서 DATABASE_URL, AI_SERVER_URL 등 설정

# 3. SpringBoot 실행
./gradlew bootRun --args='--spring.profiles.active=local'

# 4. Swagger UI 확인
open http://localhost:8080/swagger-ui
```

---

## 절대 하지 말 것

- `spring.jpa.hibernate.ddl-auto=create` 또는 `update` 사용 금지 (데이터 날아감)
- Entity를 API 응답으로 직접 반환 금지 (DTO로 변환 필수)
- AiClientService 외의 클래스에서 Python AI 서버 직접 호출 금지
- Qdrant에 직접 접근 금지 (모든 벡터 검색은 Python AI 서버를 통해)
- `System.out.println` 디버깅 금지 (SLF4J Logger 사용)
- `.env` 파일 커밋 금지
- 스키마 변경 시 schema.sql 직접 수정 금지 (Flyway 마이그레이션 파일로)
