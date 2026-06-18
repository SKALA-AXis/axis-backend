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

### B-R2 — formatter/util 순수 헬퍼 추출 (대폭 진행)
- 컨트롤러 중복 제거: `formatter/IssueImportanceClassifier`(importance 버킷팅).
- **PeerOverviewTableService 분해 (2801 → 2576, -225, 5배치, 전부 단위테스트 동반·gradle test green)**:
  - `formatter/PeerOverviewFormat` — 숫자/텍스트 포맷 8종(formatKrwBnText·formatPercentText·formatPercentPointText·nullToDash·topicParticle·firstNonBlank·nullToEmpty·blankToNull)
  - `util/JsonValues` — JSON Object 트리 네비게이터 5종(listValue·objectList·objectMap·stringValue·firstNonBlankObject)
  - `formatter/SwotText` — SWOT 표시 텍스트 정규화 6종
  - `util/MapBuilder` — 순서보존+null허용 mapOf
  - `formatter/PeerInsightBuilder` — insight·traceItem·buildRiskInsight 빌더 3종
  - 전부 정적 import 로 호출부 무변경(move-only) + 현행 동작 단위테스트(28종)로 고정.

---

## ⏸ 남은 작업 (stage 2/3 — 감독/신중 진행)

### B-R3 (stage 2) — PeerOverview query/ 계층 분리 — **감독 하 권장**
> 순수 헬퍼는 위에서 다 빠졌고, 남은 최대 감량은 DB 로더(loadPositioningPoints·
> loadSupplementalRows[~470줄]·loadFinancialRows·loadRows·loadSwotInsights 등)를 query/ 로
> 분리하는 것(목표 2576→~600). **그러나 이 로더들은** ① multi-CTE SQL 을 `ResultSetExtractor`
> 람다로 처리, ② TTL 캐시(cachedPeerOverviewTable), ③ mapper(mapPositioningPoint)·record
> (DisplayPeer)·바인딩 헬퍼와 강결합. → 안전 분리는 **공개메서드 2종 characterization(쿼리별 SQL
> 조각 모킹) 선행** 필요. 무인 + Postgres 전용 SQL(H2 실행불가)이라 characterization 이 fragile
> → **무인 강행 시 데모(2026-06-23) 리스크, 감독 하 진행 권장.** (2026-06-18 무인 세션에서
> 여기까지가 안전한 한계로 판단.)

| 항목 | 상태 |
|---|---|
| **B-R3 query/** | stage 2 — 위 사유로 감독 하. 다음 단위 = 쿼리 로더(characterization 선행). |
| **B-R4** AI fallback AOP | 횡단 관심사·예외흐름 변화 위험 — 통합테스트 선행. |
| **B-R5** @Cacheable | 캐시 무효화 정책 설계 후. |

### 권장 다음 단계 (감독 하)
1. getPeerPositioningChart·getPeerOverviewTable characterization 테스트(쿼리 SQL 조각별 모킹 → 출력 구조 고정).
2. 그 위에서 query/ 로 로더 1개씩 이동(mapper·record 동반) → `./gradlew test` 게이트 → 커밋.
3. 마지막에 조립/캐시 로직만 남겨 3계층 완성.
