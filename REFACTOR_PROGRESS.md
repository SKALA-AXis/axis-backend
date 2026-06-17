# Backend 리팩토링 진행 (refactor/backend-layering)

기준 계획: `axis-infra/docs/structure-tasks/refactoring-architecture.md` §3 (Backend)
브랜치: `refactor/backend-layering` (base: `origin/develop` @ edf3bc7)
게이트: `JAVA_HOME=...openjdk@17... ./gradlew test` — **매 커밋 전 전체 150 테스트 통과 확인** (CI 동일).
원칙: develop 직접 머지/ PR 없음. 커밋+푸시만. 동작 보존 우선.

---

## ✅ 완료 (게이트 green, 커밋됨)

### B-R0 — `@Transactional(readOnly = true)` (순수 read 서비스 10개)
- 대상(write 0, JdbcTemplate 조회 전용): DashboardStockChartService, ArticleImageService,
  AgentDiagnosticsService, GlobalTrendsService, BriefingReportService,
  DashboardKeywordTrendChartService, RawArticleQueryService, TodayInsightReportService,
  PeerOverviewTableService, KeywordGraphService.
- 효과: 다중 쿼리 읽기 일관성 + 커넥션 readOnly 힌트(드라이버/풀 최적화).
- **제외(write 보유 → per-method 필요)**: AssistantConversationService(11), UserStrategyContextService(6),
  MixerResultService(3). readOnly 를 클래스에 걸면 write 가 깨지므로 의도적으로 손대지 않음.

### B-R1 — peer_id→회사명 매핑 일원화 (`PeerCompanyProvider`)
- IssueCardController·FrontendCompatibilityController 에 **동일하게 복제**된
  `private static String peerName(peerId)` if-체인 제거.
- `service/PeerCompanyProvider`: `peer_companies`(DB)를 단일 출처로 `displayName(peerId)` 제공.
  DB 적재 결과 캐시. DB 공백/조회실패 시 기존 하드코딩과 동일한 정적 폴백(캐시 안 함 → 다음 호출 재시도).
- 단위테스트 6종: DB값 우선 / 캐시(findAll 1회) / 미상→peerId / null·blank→"Peer사" / DB공백→폴백 / DB예외→폴백.

### B-R2(착수) — `formatter/` 패키지 + `IssueImportanceClassifier`
- 두 컨트롤러에 복제된 importance 등급 버킷팅(명시 등급 우선 + score 임계 0.85/0.6)을
  `formatter/IssueImportanceClassifier.classify(importance, score)` 순수 함수로 추출. 임계값 상수화.
- 단위테스트 3종(명시등급 보존 / 임계 경계 / null·미상→reference).

---

## ⏸ 이월 (무인 자동작업 부적합 — 감독 하 진행 권장)

> **이월 사유(공통)**: 아래 대상 핵심 서비스들은 **단위테스트가 없다**
> (PeerOverviewTableService·DashboardKeywordTrendChartService·KeywordGraphService 모두 무테스트).
> 구조 분해는 compile-green 만으로 동작 보존을 보장할 수 없고, 데모(2026-06-23) 직전 핵심 기능
> (peer 비교표 등)을 무인 환경에서 리스크에 노출하는 것은 "완벽한지 확인" 원칙에 어긋난다.
> → **characterization 테스트(현행 동작 골든 캡처) 선행 후, 소단위로 분해**하는 것을 권장.

| 항목 | 계획 | 이월 사유 / 선행조건 |
|---|---|---|
| **B-R2 잔여** | formatter/ 로 더 많은 DTO 매핑 이동 | 안전하나 가치/리스크 대비 후순위. 매핑별 소단위로 가능. |
| **B-R3** | query/ 패키지로 SQL/쿼리빌더 추출 | 테스트 보유 서비스(GlobalSearch·CardNews)부터는 가능. 무테스트 서비스는 characterization 선행. |
| **B-R4** | AI fallback 패턴 AOP 화 | 횡단 관심사 — 동작 변화 위험(예외 흐름). 통합 테스트 선행 필요. |
| **B-R5** | `@Cacheable` 도입 | 캐시 무효화 정책 설계 필요(peer/financial 갱신 주기). 설계 후 진행. |
| **PeerOverviewTableService 분해** | 2801줄 → 3계층(≤500/class) | **무테스트 + 핵심 기능**. characterization 테스트 → 소단위(섹션별) 분해. AI repo `strategic_insight` 분해계획과 동일 방식 권장. |

### 권장 다음 단계 (감독 하)
1. PeerOverviewTableService 의 **순수 계산/포맷 메서드**부터 formatter/ 로 소단위 추출(각 추출에 단위테스트 동반).
2. 그 다음 query/ 로 SQL 추출 → 마지막에 조립 로직만 남겨 3계층 완성.
3. 각 단계 `./gradlew test` 게이트 + 별도 커밋(맥락 단위).
