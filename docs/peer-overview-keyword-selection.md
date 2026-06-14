# Peer Overview Keyword Selection

## 결론

Peer+ `Peer 한눈 보기`의 `사업 키워드`와 `기술 키워드`는 LLM 스냅샷으로 생성한다.

기존 DB를 확인한 결과, `raw_article_business_signals.business_area`는 `cloud`, `ai_ax`, `company_total` 같은 파서용 분류값이고 `signal_type`도 `growth`, `risk`, `forecast` 같은 이벤트 타입이다. 이 값들을 표에 직접 올리면 사용자용 키워드가 아니라 내부 태그가 노출된다.

따라서 최종 키워드는 아래처럼 만든다.

```text
분기별 raw_article_business_signals evidence pack
  -> LLM이 실제 원문 문맥에서 사업/기술 표현 추출
  -> 근거 부적합 신호 제거
  -> 사업 키워드 1개 + 기술 키워드 1개 선택
  -> peer_llm_analysis_snapshots에 저장
  -> PeerOverviewTableService는 최신 active 스냅샷을 우선 조회
  -> 스냅샷이 없을 때만 deterministic fallback 사용
```

## DB 확인 결과

확인 시점: 2026-06-09

### 테이블 규모

| table | count |
|---|---:|
| `peer_companies` | 5 |
| `raw_articles` | 19,961 |
| `raw_article_business_signals` | 5,662 |
| `raw_article_financial_metrics` | 2,591 |
| `peer_llm_analysis_snapshots` | 5 |

### 최신 공통 business signal 분기

`raw_article_business_signals` 기준 최신 공통 분기는 `2026Q1`이다.

| period | peer coverage | signal count |
|---|---:|---:|
| `2026Q1` | 5 | 431 |
| `2025Q4` | 4 | 300 |
| `2025Q3` | 5 | 353 |
| `2025Q2` | 5 | 347 |
| `2025Q1` | 5 | 351 |

`2026Q1` 기업별 신호 수:

| peer | signals | articles | avg confidence |
|---|---:|---:|---:|
| `hyundai_autoever` | 94 | 7 | 0.719 |
| `lg_cns` | 104 | 6 | 0.715 |
| `posco_dx` | 68 | 2 | 0.829 |
| `samsung_sds` | 109 | 7 | 0.734 |
| `sk_ax` | 56 | 3 | 0.799 |

### 필드 품질 관찰

`business_area`는 최종 노출 키워드로 쓰기 어렵다.

예시:

```text
company_total
cloud
ai_ax
enterprise_it
vehicle_sw
logistics
smart_factory
cloud|ai_ax|enterprise_it
company_total|cloud|ai_ax|enterprise_it|orders_pipeline|rd|risk
```

`signal_type`도 최종 노출 키워드가 아니라 신호 성격이다.

예시:

```text
growth
risk
forecast
strategy
orders_pipeline
investment
product_service
business_overview
```

원천별 분포도 편차가 있다.

| peer | 주요 원천 |
|---|---|
| `samsung_sds` | securities report 75, IR 29, DART 5 |
| `lg_cns` | securities report 93, IR 11 |
| `hyundai_autoever` | securities report 92, IR 2 |
| `posco_dx` | DART 64, IR 4 |
| `sk_ax` | DART 51, IR 4, securities report 1 |

특히 `sk_ax`는 DART에서 SK 주식회사 전체 사업/타 사업 문맥이 섞일 수 있다. 따라서 LLM 단계는 키워드 생성뿐 아니라 `근거 적합성 필터` 역할도 해야 한다.

## 저장 위치

기존 `peer_llm_analysis_snapshots`를 재사용한다.

새 analysis type:

```text
analysis_type    = peer_overview_keywords
schema_version   = peer_overview_keywords_v1
comparison_mode  = quarterly_keyword_selection
scope            = company
peer_id          = sk_ax | samsung_sds | lg_cns | hyundai_autoever | posco_dx
reference_peer_id = sk_ax
```

이 테이블은 이미 아래 요구를 만족한다.

- 최신 active 조회 인덱스 있음
- evidence hash 기반 중복 upsert 가능
- `input_snapshot`, `output_payload`, `analysis_trace` JSONB 저장 가능
- source signal/article id 배열 저장 가능
- 기존 SWOT LLM 결과와 같은 운영 패턴 사용 가능

## Output Schema

`output_payload`는 프론트/백엔드 행 필드에 바로 매핑 가능해야 한다.

```json
{
  "peer_id": "samsung_sds",
  "peer_name": "삼성 SDS",
  "period": "2026Q1",
  "business_keyword": {
    "label": "AI 인프라",
    "reason": "국가 AI 컴퓨팅센터와 GPUaaS 제공 확대가 같은 분기 원문 신호에서 확인되어 사업 방향으로 판단했다.",
    "confidence": 0.82,
    "evidence_refs": ["signal:123", "signal:456"],
    "source_urls": ["https://..."],
    "evidence_summary": "국가 AI 컴퓨팅센터 우선협상대상자 선정, GPUaaS 제공 확대"
  },
  "technology_keyword": {
    "label": "FabriX",
    "reason": "생성형 AI 플랫폼과 Brity/FabriX 솔루션 라인업 확대가 제품·기술 축으로 확인된다.",
    "confidence": 0.78,
    "evidence_refs": ["signal:789"],
    "source_urls": ["https://..."],
    "evidence_summary": "FabriX, Brity Copilot 기반 생성형 AI 사업 강화"
  },
  "top_keyword": "AI 인프라\nFabriX",
  "top_keyword_reason": "2026Q1 원문 기반 사업 신호에서 사업 방향과 기술 구현 축을 분리해 선택했다.",
  "top_keyword_basis": "LLM grounded selection · signals 12 · articles 3 · confidence 0.80",
  "top_keyword_evidence": [
    "삼성 SDS 사업 키워드 기준: AI 인프라. 근거 내용: ... 판단 이유: ...",
    "삼성 SDS 기술 키워드 기준: FabriX. 근거 내용: ... 판단 이유: ..."
  ],
  "top_keyword_evidence_urls": ["https://..."],
  "rejected_candidates": [
    {
      "label": "growth",
      "reason": "signal_type 이벤트 분류값이라 키워드로 부적합"
    }
  ],
  "analysis_trace": [
    {
      "step": "근거 수집",
      "summary": "2026Q1 신호 109건 중 원문 근거가 있는 18건을 후보로 압축"
    },
    {
      "step": "후보 정제",
      "summary": "company_total, risk, forecast 등 내부 분류·시장 전망 신호 제외"
    },
    {
      "step": "최종 선택",
      "summary": "사업은 AI 인프라, 기술은 FabriX가 가장 직접적인 실행 근거와 연결됨"
    }
  ]
}
```

백엔드 매핑:

| API field | source |
|---|---|
| `topKeyword` | `output_payload.top_keyword` |
| `businessKeyword` | `output_payload.business_keyword.label` |
| `technologyKeyword` | `output_payload.technology_keyword.label` |
| `topKeywordReason` | `output_payload.top_keyword_reason` |
| `topKeywordBasis` | `output_payload.top_keyword_basis` |
| `topKeywordScore` | 평균 confidence 또는 null |
| `topKeywordEvidence` | `output_payload.top_keyword_evidence` |
| `topKeywordEvidenceUrls` | `output_payload.top_keyword_evidence_urls` |

## Evidence Pack 설계

LLM에 전체 431개 신호를 그대로 넣지 않는다. 기업별로 압축 pack을 만든다.

### 1. 기준 분기 선택

우선순위:

```text
1. PeerOverviewTableService의 재무 공통 분기 period
2. 해당 period에 business signal이 3개 이상 있는 경우 사용
3. 부족하면 raw_article_business_signals의 최신 5-peer 공통 분기 사용
4. 그래도 부족하면 peer별 최신 분기를 쓰되 output에 mixed_period=true 표시
```

현재 DB에서는 `2026Q1` 사용이 타당하다.

### 2. 후보 신호 1차 필터

포함:

```text
period = 기준 분기
peer_id = 대상 기업
raw_articles 조인 성공
summary 또는 evidence_text 존재
confidence >= 0.68
```

제외 또는 감점:

```text
business_area = company_total 이면서 원문에 구체 사업/기술 표현이 없음
signal_type IN ('risk', 'forecast', 'valuation') 이면서 실행/제품/수주 근거가 없음
business_area/signal_type 값 자체가 최종 키워드 후보인 경우
회사명 자체
AI, AX, DX 단독 표현
증권사 투자의견/주가 전망 문장
SK AX의 경우 SK㈜ 비IT 계열 사업 문맥
```

### 3. 후보 신호 압축

기업별 최대 20개 신호만 LLM에 전달한다.

추천 배분:

```text
official / IR / DART product_service, strategy, orders_pipeline 우선 8개
securities_report growth / strategy / orders_pipeline 8개
source 다양성 보정 4개
```

각 신호 payload:

```json
{
  "signal_id": 279570,
  "raw_article_id": 12345,
  "source_type": "ir",
  "source_name": "ir_pdf",
  "title": "Samsung SDS 2026년 1분기 실적발표 자료",
  "business_area": "ai_ax",
  "signal_type": "growth",
  "summary": "국가 AI컴퓨팅센터 우선협상대상자 선정...",
  "evidence_text": "국가 AI컴퓨팅센터 우선협상대상자 선정...",
  "confidence": 0.78,
  "url": "https://..."
}
```

### 4. Candidate Hints

LLM이 원문에서 키워드를 더 안정적으로 뽑도록 deterministic hint를 같이 준다. 단, 이 hint는 최종 후보 사전이 아니라 참고 통계다.

```json
{
  "business_area_counts": {"ai_ax": 19, "cloud": 26},
  "signal_type_counts": {"growth": 50, "orders_pipeline": 8},
  "source_counts": {"ir": 29, "dart": 5, "securities_report": 75},
  "frequent_terms_from_evidence": ["국가 AI컴퓨팅센터", "GPUaaS", "FabriX", "Brity"]
}
```

`frequent_terms_from_evidence`는 회사별 고정 사전이 아니라 해당 분기 근거 문장에서 뽑은 동적 후보만 사용한다.

## LLM Prompt 규칙

System 요지:

```text
당신은 Peer+ 분기 키워드 선별기다.
반드시 제공된 evidence 안에서만 판단한다.
business_area/signal_type 내부 태그를 그대로 최종 키워드로 쓰지 않는다.
사업 키워드는 고객/시장에 제공되는 사업, 서비스, 오퍼링, 수주/확장 방향이다.
기술 키워드는 그 사업을 가능하게 하는 제품, 플랫폼, 기술, 자동화/AI/클라우드 구현 축이다.
근거가 부족하면 null을 반환한다.
```

핵심 금지:

```text
AI, AX, DX 단독 금지
growth, risk, forecast 같은 signal_type 금지
company_total, cloud, ai_ax 같은 내부 카테고리 값 그대로 사용 금지
근거에 없는 브랜드/제품명 추정 금지
증권사 주가 전망만 근거로 선택 금지
```

필수 출력:

```text
business_keyword.label
technology_keyword.label
각각 evidence_refs 최소 1개
각각 reason
rejected_candidates
analysis_trace
```

## Validation 규칙

LLM 결과 저장 전 검증한다.

Hard fail:

```text
JSON parse 실패
label이 비어 있음
label이 금지어와 정확히 일치
evidence_refs가 없음
evidence_refs가 input_snapshot에 없는 signal id
reason이 evidence 없이 일반론만 설명
```

Soft fail 또는 review:

```text
business_keyword와 technology_keyword가 동일
confidence < 0.55
source가 전부 securities_report이고 공식/IR/DART 근거가 없음
SK AX 결과가 비IT/비AX 계열 사업 근거에만 의존
```

Review 상태는 `peer_llm_analysis_snapshots.status = 'review'`로 저장하고, Peer+ 표에는 deterministic fallback 또는 null을 표시한다.

## Backend 조회 설계

`PeerOverviewTableService.loadSupplementalRows(period)`는 아래 순서로 동작한다.

```text
1. peer_llm_analysis_snapshots에서 analysis_type='peer_overview_keywords' 최신 active 조회
2. period가 output_payload.period와 일치하는 결과를 우선 사용
3. 없으면 같은 analysis_type의 최신 active 중 expires_at 미만 결과 사용 가능
4. 그래도 없으면 deterministic fallback 사용
```

조회 SQL 개념:

```sql
SELECT DISTINCT ON (peer_id)
  peer_id,
  output_payload,
  confidence,
  source_signal_ids,
  source_raw_article_ids
FROM peer_llm_analysis_snapshots
WHERE analysis_type = 'peer_overview_keywords'
  AND scope = 'company'
  AND comparison_mode = 'quarterly_keyword_selection'
  AND status = 'active'
  AND peer_id IN ('sk_ax', 'samsung_sds', 'lg_cns', 'hyundai_autoever', 'posco_dx')
  AND output_payload->>'period' = :period
  AND (expires_at IS NULL OR expires_at > NOW())
ORDER BY peer_id, generated_at DESC, created_at DESC;
```

## 생성 Job 설계

위치는 axis-ai가 적합하다.

```text
axis-ai/scripts/generate_peer_overview_keywords.py
```

실행:

```bash
uv run python scripts/generate_peer_overview_keywords.py --period 2026Q1 --save-db
```

운영 주기:

```text
분기 실적/보고서 수집 완료 후 1회
필요 시 수동 재생성
evidence_hash가 같으면 upsert만 수행하고 불필요한 재호출 방지
```

저장 방식은 `scripts/generate_peer_swot_llm_preview.py`의 `save_result_to_db()` 패턴을 따른다.

## API/프론트 영향

프론트 계약은 유지한다.

```text
businessKeyword
technologyKeyword
topKeywordReason
topKeywordBasis
topKeywordEvidence
topKeywordEvidenceUrls
```

프론트는 그대로 표시하고, info 팝오버도 현재처럼 `사업 키워드 기준:` / `기술 키워드 기준:` marker로 필터링한다.

## 현재 코드와의 차이

현재 deterministic fallback은 `business_area`, `signal_type`, 일반 기술 신호를 SQL로 점수화한다. DB 확인 결과 이 방식은 다음 문제가 있다.

```text
business_area가 내부 카테고리라 표 표시 품질이 낮음
signal_type이 이벤트 타입이라 기술 키워드로 부적합
SK AX DART 근거에 타 사업 문맥이 섞일 수 있음
증권사 forecast/risk 문장이 빈도상 과대표집될 수 있음
```

따라서 SQL 기반 로직은 fallback으로만 남기고, 정상 경로는 LLM 스냅샷을 사용한다.

## 구현 순서

1. `axis-ai/scripts/generate_peer_overview_keywords.py` 추가
2. evidence pack builder 작성
3. LLM prompt + JSON schema validation 추가
4. `peer_llm_analysis_snapshots`에 `analysis_type='peer_overview_keywords'` 저장
5. `PeerOverviewTableService`가 해당 스냅샷을 우선 읽도록 변경
6. 스냅샷이 없거나 review 상태면 fallback/null 처리
7. 2026Q1 샘플 생성 후 화면 검수

