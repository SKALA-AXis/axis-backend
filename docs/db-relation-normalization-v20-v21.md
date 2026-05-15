# DB Relation Normalization V20/V21 적용 확인서

작성일: 2026-05-15 KST  
대상 DB: `skala3-finalproj-class3-team13` namespace의 PostgreSQL `axis`  
소스 위치:

- `src/main/resources/db/migration/V20__normalize_article_card_peer_relations.sql`
- `src/main/resources/db/migration/V21__add_briefing_feedback_log_relation_tables.sql`

## 결론

V20/V21 migration 파일은 `axis-backend` repo의 Flyway migration 경로에 반영되어 있고, live DB에도 Flyway 이력 기준으로 성공 적용되어 있다.

이번 변경은 운영 데이터 보존을 우선으로 한 additive migration이다. `raw_articles`, `article_images`, `card_news`는 삭제, truncate, PK 변경, 컬럼 rename/drop 없이 유지했다. 기존 legacy 컬럼도 즉시 제거하지 않고, 새 nullable 컬럼과 매핑 테이블을 추가한 뒤 backfill과 FK를 적용했다.

## Flyway 적용 상태

live DB의 `flyway_schema_history` 확인 결과:

| version | description | success | installed_on |
|---|---|---|---|
| 20 | normalize article card peer relations | true | 2026-05-15 02:14:01.725146 |
| 21 | add briefing feedback log relation tables | true | 2026-05-15 02:15:42.28205 |

`axis-backend`에서 Flyway validation도 21개 migration 기준으로 성공했다.

```text
Successfully validated 21 migrations
Current version of schema "public": 21
Schema "public" is up to date. No migration necessary.
```

## V20 작업 내용

V20은 기사, 카드뉴스, Peer사, 수집 실행 단위의 관계를 정규화한다.

### 1. crawl 상태 테이블 Flyway 관리 편입

기존에 `axis-ai` helper 코드가 만들던 `crawl_cursors`, `crawl_runs`를 Flyway 관리 대상으로 편입했다. 기존 row는 변경하지 않는다.

```text
crawl_runs.id
  └── raw_articles.crawl_run_id
```

### 2. raw_articles와 crawl_runs 연결

추가 컬럼:

- `raw_articles.crawl_run_id UUID NULL`

backfill:

- `raw_articles.metadata->>'crawl_run_id'`가 UUID 형식이고 실제 `crawl_runs.id`에 존재하는 경우만 채웠다.
- 매칭되지 않는 과거 metadata 값은 억지로 채우지 않고 `NULL`로 유지했다.

적용 후 확인:

| metric | value |
|---|---:|
| `raw_articles` row count | 10143 |
| `raw_articles.crawl_run_id IS NOT NULL` | 9588 |
| `raw_articles.crawl_run_id` orphan | 0 |

### 3. article_images legacy alias 추가

기존 컬럼:

- `article_images.article_id`
- `article_images.issue_card_id`

추가 컬럼:

- `article_images.raw_article_id BIGINT NULL`
- `article_images.card_news_id VARCHAR(50) NULL`
- `article_images.image_order INT NULL`
- `article_images.caption TEXT NULL`
- `article_images.updated_at TIMESTAMPTZ NULL`

관계:

```text
article_images.raw_article_id -> raw_articles.id
article_images.card_news_id   -> card_news.id
```

현재 `article_images` row count는 0이므로 데이터 backfill 대상은 없었다. 컬럼과 FK는 정상 생성되어 있다.

### 4. card_news와 peer_companies 연결

기존 컬럼:

- `card_news.company`

추가 컬럼:

- `card_news.peer_company_id VARCHAR(50) NULL`

backfill:

- `card_news.company = peer_companies.id`인 경우 `peer_company_id`로 채웠다.

적용 후 확인:

| metric | value |
|---|---:|
| `card_news` row count | 131 |
| `card_news.peer_company_id IS NOT NULL` | 131 |
| `card_news.peer_company_id` orphan | 0 |

관계:

```text
card_news.peer_company_id -> peer_companies.id
```

### 5. evidence_chain legacy alias 추가

기존 컬럼:

- `evidence_chain.issue_card_id`

추가 컬럼:

- `evidence_chain.card_news_id VARCHAR(50) NULL`

backfill:

- `issue_card_id` 값을 `card_news_id`로 복사했다.

적용 후 확인:

| metric | value |
|---|---:|
| `evidence_chain` row count | 131 |
| `evidence_chain.card_news_id IS NOT NULL` | 131 |
| `evidence_chain.card_news_id` orphan | 0 |

관계:

```text
evidence_chain.card_news_id -> card_news.id
```

### 6. card_news_articles 매핑 테이블 생성

카드뉴스가 여러 기사를 기반으로 만들어질 수 있으므로 N:M 매핑 테이블을 만들었다.

```text
card_news_articles.card_news_id   -> card_news.id
card_news_articles.raw_article_id -> raw_articles.id
UNIQUE(card_news_id, raw_article_id)
```

backfill:

- `evidence_chain.provenance.raw_article_ids` JSON 배열에서 기사 ID를 추출했다.
- 숫자가 아닌 값 또는 존재하지 않는 기사 ID가 있으면 migration을 중단하도록 검증을 넣었다.

적용 후 확인:

| table | row count |
|---|---:|
| `card_news_articles` | 133 |

`card_news`는 131건이고 `card_news_articles`는 133건이다. 따라서 실제 데이터상 일부 카드뉴스는 여러 원천 기사를 기반으로 한다.

### 7. article_peer_companies 매핑 테이블 생성

기사와 Peer사는 N:M 관계로 보고 매핑 테이블을 추가했다.

```text
article_peer_companies.raw_article_id   -> raw_articles.id
article_peer_companies.peer_company_id  -> peer_companies.id
UNIQUE(raw_article_id, peer_company_id)
```

backfill:

- `raw_articles.company` JSONB 배열
- `raw_articles.matched_companies` JSONB 배열
- `peer_companies.id`
- `peer_companies.name`
- `peer_companies.keywords`

위 값을 비교하여 매칭되는 관계를 채웠다.

적용 후 확인:

| table | row count |
|---|---:|
| `article_peer_companies` | 10064 |

orphan 검증:

| check | orphan_count |
|---|---:|
| `article_peer_companies.peer_company_id` | 0 |
| `article_peer_companies.raw_article_id` | 0 |

### 8. Peer 관련 legacy alias 추가

기존 컬럼:

- `peer_financials.peer_id`
- `job_postings.peer_id`
- `weak_signal_cards.peer_id`

추가 컬럼:

- `peer_financials.peer_company_id`
- `job_postings.peer_company_id`
- `weak_signal_cards.peer_company_id`

관계:

```text
peer_financials.peer_company_id   -> peer_companies.id
job_postings.peer_company_id      -> peer_companies.id
weak_signal_cards.peer_company_id -> peer_companies.id
```

## V21 작업 내용

V21은 briefing, feedback, log, weak signal, analysis ledger 쪽의 관계를 보강한다.

### 1. briefing_history와 briefing_reports 연결

추가 컬럼:

- `briefing_history.briefing_report_id VARCHAR(40) NULL`

관계:

```text
briefing_history.briefing_report_id -> briefing_reports.id
```

daily email history가 항상 briefing report에서 나온다는 보장이 없으므로 nullable로 유지한다.

### 2. briefing 매핑 테이블 생성

생성 테이블:

- `briefing_report_cards`
- `briefing_report_articles`
- `briefing_recipients`
- `briefing_history_cards`

관계:

```text
briefing_reports.id
  ├── briefing_report_cards.briefing_report_id
  ├── briefing_report_articles.briefing_report_id
  └── briefing_recipients.briefing_report_id

briefing_history.id
  └── briefing_history_cards.briefing_history_id
```

현재 `briefing_reports`, `briefing_history`, `recipients` row가 없어 매핑 row count는 0이다. 테이블과 FK는 생성되어 있다.

### 3. feedback typed FK 추가

기존 구조:

- `feedback.artifact_type`
- `feedback.artifact_id`

추가 컬럼:

- `feedback.card_news_id`
- `feedback.briefing_report_id`
- `feedback.chat_turn_id`

관계:

```text
feedback.card_news_id       -> card_news.id
feedback.briefing_report_id -> briefing_reports.id
feedback.chat_turn_id       -> chat_turns.id
```

기존 polymorphic 컬럼은 호환성을 위해 유지한다.

### 4. log 관계 추가

추가 컬럼:

- `crawl_logs.crawl_run_id`
- `pipeline_logs.crawl_run_id`

추가 FK:

```text
crawl_logs.crawl_run_id     -> crawl_runs.id
pipeline_logs.crawl_run_id  -> crawl_runs.id
usage_logs.pipeline_log_id  -> pipeline_logs.id
```

### 5. weak_signal_cards typed link 추가

추가 컬럼:

- `weak_signal_cards.source_raw_article_id`
- `weak_signal_cards.card_news_id`

생성 테이블:

- `weak_signal_card_articles`

관계:

```text
weak_signal_cards.peer_company_id       -> peer_companies.id
weak_signal_cards.source_raw_article_id -> raw_articles.id
weak_signal_cards.card_news_id          -> card_news.id
weak_signal_card_articles.raw_article_id -> raw_articles.id
```

현재 `weak_signal_cards` row가 없어 매핑 row count는 0이다.

### 6. analysis_ledger 매핑 테이블 생성

기존 구조:

- `analysis_ledger.peer_ids JSONB`
- `analysis_ledger.source_card_ids JSONB`

생성 테이블:

- `analysis_ledger_card_news`
- `analysis_ledger_peer_companies`

관계:

```text
analysis_ledger.id
  ├── analysis_ledger_card_news.analysis_ledger_id
  └── analysis_ledger_peer_companies.analysis_ledger_id
```

현재 `analysis_ledger` row가 없어 매핑 row count는 0이다. JSONB 컬럼은 호환성을 위해 유지한다.

## live DB 검증 결과

### 주요 row count

| table | row_count |
|---|---:|
| `raw_articles` | 10143 |
| `article_images` | 0 |
| `card_news` | 131 |
| `evidence_chain` | 131 |
| `card_news_articles` | 133 |
| `article_peer_companies` | 10064 |

### 신규 매핑 테이블 존재 확인

live DB에서 다음 9개 테이블이 확인되었다.

```text
analysis_ledger_card_news
analysis_ledger_peer_companies
article_peer_companies
briefing_history_cards
briefing_recipients
briefing_report_articles
briefing_report_cards
card_news_articles
weak_signal_card_articles
```

### 신규 FK 존재 확인

live DB에서 다음 FK가 확인되었다.

| FK | table | references |
|---|---|---|
| `fk_article_images_card_news` | `article_images` | `card_news` |
| `fk_article_images_raw_article` | `article_images` | `raw_articles` |
| `fk_briefing_history_briefing_report` | `briefing_history` | `briefing_reports` |
| `fk_card_news_peer_company` | `card_news` | `peer_companies` |
| `fk_crawl_logs_crawl_run` | `crawl_logs` | `crawl_runs` |
| `fk_evidence_chain_card_news` | `evidence_chain` | `card_news` |
| `fk_feedback_briefing_report` | `feedback` | `briefing_reports` |
| `fk_feedback_card_news` | `feedback` | `card_news` |
| `fk_feedback_chat_turn` | `feedback` | `chat_turns` |
| `fk_job_postings_peer_company` | `job_postings` | `peer_companies` |
| `fk_peer_financials_peer_company` | `peer_financials` | `peer_companies` |
| `fk_pipeline_logs_crawl_run` | `pipeline_logs` | `crawl_runs` |
| `fk_raw_articles_crawl_run` | `raw_articles` | `crawl_runs` |
| `fk_usage_logs_pipeline_log` | `usage_logs` | `pipeline_logs` |
| `fk_weak_signal_cards_card_news` | `weak_signal_cards` | `card_news` |
| `fk_weak_signal_cards_peer_company` | `weak_signal_cards` | `peer_companies` |
| `fk_weak_signal_cards_source_raw_article` | `weak_signal_cards` | `raw_articles` |

### orphan 검증 결과

| check | orphan_count |
|---|---:|
| `raw_articles.crawl_run_id` | 0 |
| `card_news.peer_company_id` | 0 |
| `evidence_chain.card_news_id` | 0 |
| `card_news_articles.raw_article_id` | 0 |
| `article_peer_companies.peer_company_id` | 0 |

## 현재 목표 ERD

현재 DB는 아래 구조를 목표 형태로 갖는다.

```text
raw_articles.id
  ├── article_images.raw_article_id
  ├── card_news_articles.raw_article_id
  ├── article_peer_companies.raw_article_id
  ├── weak_signal_cards.source_raw_article_id
  ├── weak_signal_card_articles.raw_article_id
  └── raw_articles.crawl_run_id -> crawl_runs.id

peer_companies.id
  ├── card_news.peer_company_id
  ├── peer_financials.peer_company_id
  ├── job_postings.peer_company_id
  ├── weak_signal_cards.peer_company_id
  ├── article_peer_companies.peer_company_id
  └── analysis_ledger_peer_companies.peer_company_id

card_news.id
  ├── article_images.card_news_id
  ├── evidence_chain.card_news_id
  ├── card_news_articles.card_news_id
  ├── briefing_report_cards.card_news_id
  ├── briefing_history_cards.card_news_id
  ├── feedback.card_news_id
  ├── weak_signal_cards.card_news_id
  └── analysis_ledger_card_news.card_news_id

briefing_reports.id
  ├── briefing_history.briefing_report_id
  ├── briefing_report_cards.briefing_report_id
  ├── briefing_report_articles.briefing_report_id
  └── briefing_recipients.briefing_report_id

crawl_runs.id
  ├── raw_articles.crawl_run_id
  ├── crawl_logs.crawl_run_id
  └── pipeline_logs.crawl_run_id
```

## 의도한 스키마와 실제 DB 비교

의도한 큰 방향은 실제 DB에 반영되어 있다.

- `raw_articles` 중심으로 기사, 이미지, 카드뉴스, Peer사, crawl run 관계가 생겼다.
- `card_news`가 단일 기사 FK 하나만 갖는 구조가 아니라 `card_news_articles` N:M 구조를 갖게 되었다.
- `peer_companies`가 Peer사 기준 FK의 중심이 되었다.
- `briefing`, `feedback`, `weak_signal`, `analysis_ledger`는 기존 JSON/array/polymorphic 구조를 유지하면서 typed FK와 매핑 테이블을 추가했다.
- 신규 FK의 orphan count는 현재 0이다.

다만 완전 전환은 아직 아니다.

- writer 코드는 아직 legacy 컬럼도 계속 사용한다.
- 새 컬럼은 대부분 nullable이다. 이는 기존 운영 흐름과 과거 데이터를 깨지 않기 위한 의도된 상태다.
- `raw_articles.company`, `card_news.company`, `evidence_chain.issue_card_id`, `feedback.artifact_type/artifact_id`, `analysis_ledger.peer_ids/source_card_ids`는 호환성을 위해 유지된다.
- 이후 `axis-ai` writer가 dual-write를 시작해야 신규 insert도 새 관계 컬럼과 매핑 테이블에 즉시 반영된다.

## 다음 단계

1. `axis-ai/src/db/article_store.py` writer dual-write 적용
   - `raw_articles.crawl_run_id`
   - `card_news.peer_company_id`
   - `evidence_chain.card_news_id`
   - `card_news_articles`
   - `article_peer_companies`

2. `axis-ai/src/middleware/analysis_ledger.py` dual-write 적용
   - `analysis_ledger_card_news`
   - `analysis_ledger_peer_companies`

3. backend read model 전환
   - `CardNews.peerCompanyId` 추가
   - `ArticleImage.rawArticleId`, `ArticleImage.cardNewsId`를 신규 컬럼 기준으로 변경
   - `RawArticle.company JSONB` 매핑 오류 정리

4. 운영 검증 후 후속 migration
   - NOT NULL 가능 여부 판단
   - legacy 컬럼 rename/drop은 마지막 단계에서 별도 migration으로 처리
