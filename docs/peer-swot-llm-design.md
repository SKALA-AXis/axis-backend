# Peer+ SWOT LLM Analysis Design

## 목적

Peer+의 `SWOT 분석` 영역을 기존 SWOT 원문 파싱 방식과 분리해 새로 생성한다. `catch_company_analysis` 안에 이미 들어 있는 `## SWOT 분석` 섹션은 사용하지 않는다.

목표는 DB에 이미 저장된 원문 기반 사업 신호, 재무 지표, 기업 기본 정보를 LLM 입력으로 제공하고, LLM이 SK AX와 비교 가능한 사업/기술 흐름, 리스크, SWOT, 확인 포인트를 새롭게 생성하도록 하는 것이다. 단, LLM은 근거 없는 추측을 만들면 안 되며, 반드시 입력 데이터에 포함된 근거 안에서만 판단해야 한다.

이 설계에서 기존 SWOT 데이터는 seed, fallback, 보완 근거로도 사용하지 않는다.
카드뉴스 데이터는 현재 품질과 커버리지가 안정적이지 않으므로 LLM 입력에서 제외한다.

## 권장 적용 방식

LLM은 페이지 진입 시 실시간으로 호출하지 않는다. Peer+ 로딩 속도를 위해 아래 중 하나로 운영한다.

```text
배치 또는 수동 갱신
  -> 전체 경쟁사와 기업별 LLM 비교 SWOT 생성
  -> 결과를 스냅샷/캐시로 저장
  -> Peer+ 화면은 저장된 결과만 조회
```

초기 구현은 백엔드 메모리 캐시 또는 DB 스냅샷 테이블 중 하나를 사용한다. 운영 안정성까지 고려하면 최종적으로는 DB 스냅샷 테이블을 추천한다.

## 전체 흐름

```text
대상 기업 목록 조회
  -> raw_article_business_signals, raw_articles, raw_article_financial_metrics, peer_companies에서 근거 수집
  -> catch_company_analysis의 기존 SWOT 섹션은 제외
  -> SK AX evidence pack 구성
  -> 전체 경쟁사 비교용 evidence pack 구성
  -> 기업별 1:1 비교 evidence pack 구성
  -> LLM에 비교 포인트와 비교 SWOT 분석 요청
  -> JSON schema 검증
  -> 근거 없는 문장 제거 또는 재요청
  -> 결과 저장
  -> Peer+ 화면에서 전체/기업별 비교 SWOT과 확인 포인트 표시
```

## 비교 범위

### 전체 필터

전체 필터는 삼성SDS, LG CNS, 현대오토에버, 포스코DX 전체를 SK AX와 비교한다.

```text
SK AX
  vs
삼성SDS + LG CNS + 현대오토에버 + 포스코DX
```

이 결과는 특정 기업 하나의 강약점이 아니라, 전체 경쟁사가 SK AX와 비교해 어떤 사업 방향, 기술 방향, 리스크를 보이는지 설명해야 한다.
전체 결과의 `body`, `title`, `check_point`, `overall_check_point`에서는 특정 경쟁사 하나를 대표처럼 콕 집어 말하지 않는다. 특정 기업 하나의 근거만 있는 내용은 전체 경향으로 쓰지 않고 `insufficient_evidence=true`로 처리한다.

### 기업별 필터

기업별 필터는 선택한 기업 1개와 SK AX만 비교한다.

```text
SK AX vs 삼성SDS
SK AX vs LG CNS
SK AX vs 현대오토에버
SK AX vs 포스코DX
```

기업별 결과는 전체 결과를 재사용하지 않는다. 선택한 기업의 원문 기반 사업 신호와 재무 지표를 SK AX의 신호와 직접 비교해 별도로 생성한다.

## 사용할 데이터

### 1. 기업 기본 정보

사용 테이블:

```text
peer_companies
```

사용 필드:

```text
id
name
keywords
core_keywords
description
financial_history
job_posting_history
```

용도:

- 기업 식별자와 표시명을 고정한다.
- 회사별 키워드와 설명이 있으면 기업 식별 보조 맥락으로 사용한다.
- LLM이 기업명을 혼동하지 않도록 `peer_id`, `peer_name`을 명시한다.

### 2. 원문 기사와 분석 문서

사용 테이블:

```text
raw_articles
```

사용 조건:

```text
peer_company_ids 또는 관련 매핑으로 대상 기업과 연결된 원문
content에 실제 사업 활동, 기술 적용, 실적, 리스크 설명이 있는 원문
content NOT LIKE '%입사제안 받기%'
```

사용 필드:

```text
id
title
content
url
published_at
collected_at
created_at
```

용도:

- 원문 제목, 날짜, 링크를 evidence source로 사용한다.
- 원문 본문은 사업 활동, 기술 적용, 고객/시장 방향, 리스크가 드러나는 부분만 짧게 추출해 사용한다.
- 이미 작성된 `## SWOT 분석` 섹션은 사용하지 않는다.

주의:

- 본문 안에는 경쟁사명이 같이 들어갈 수 있으므로 기업 매칭은 `peer_company_ids`, `peer_id`, 또는 신뢰 가능한 매핑 필드를 우선한다.
- `입사제안 받기` 같은 잠긴 안내 문구는 분석 근거로 사용하지 않는다.
- `Strength`, `Weakness`, `Opportunity`, `Threat`, `SWOT 분석`으로 시작하는 기존 SWOT 요약 문단은 evidence pack에서 제외한다.

### 3. 원문 기반 사업 신호

사용 테이블:

```text
raw_article_business_signals
raw_articles
```

조인:

```sql
raw_article_business_signals.raw_article_id = raw_articles.id
```

사용 필드:

```text
raw_article_business_signals.peer_id
raw_article_business_signals.business_area
raw_article_business_signals.signal_type
raw_article_business_signals.sentiment
raw_article_business_signals.summary
raw_article_business_signals.evidence_text
raw_article_business_signals.confidence
raw_article_business_signals.created_at

raw_articles.id
raw_articles.title
raw_articles.url
raw_articles.published_at
raw_articles.collected_at
raw_articles.source_name
```

용도:

- 사업 신호: 어떤 시장/고객/사업 영역에서 실제 활동하고 있으며, 그 방향이 SK AX와 비교해 어디로 향하는지 판단한다.
- 기술 신호: 사업을 가능하게 하는 기술, 제품, 플랫폼, 구현 역량이 무엇이며, SK AX의 기술 방향과 어떻게 같거나 다른지 판단한다.
- Strength: SK AX와 비교했을 때 더 강하게 보이는 사업/기술/운영/실적상의 강점을 근거로 삼는다.
- Weakness: SK AX와 비교했을 때 드러나는 의존도, 수익성 부담, 제한된 범위, 실적 변동성을 근거로 삼는다.
- Opportunity: SK AX와 비교해 잡을 수 있는 시장 확대, 신규 사업, 고객 전환, 기술 수요를 근거로 삼는다.
- Threat: SK AX와 비교할 때 부담이 되는 경쟁 심화, 비용 증가, 특정 고객/사업 의존, 기술 표준 변화, 보안/규제 리스크를 근거로 삼는다.

권장 조회 범위:

```text
최근 180일 우선
부족하면 최근 365일
기업당 기본 8개 신호
```

우선순위:

```text
confidence 높은 순
최근성 높은 순
실행 행동이 포함된 summary/evidence_text 우선
동일 business_area 중복은 2개 이하
```

### 4. 재무 지표

사용 테이블:

```text
raw_article_financial_metrics
```

사용 필드:

```text
peer_id
period
period_year
period_quarter
metric_name
metric_label
metric_scope
business_area
value_numeric
value_krwbn
unit
evidence_text
confidence
```

용도:

- Strength: 매출 성장, 영업이익률 개선, 특정 사업 부문 성장.
- Weakness: 낮은 수익성, 적자, 특정 부문 매출 의존.
- Threat: 실적 변동성, 영업이익률 하락, 부문별 약세.

주의:

- 재무 수치는 SWOT의 단독 근거가 아니라 사업 신호와 함께 사용한다.
- 서로 다른 기업의 공시 범위가 다르면 직접 비교 표현을 피한다.

## Evidence Pack 설계

LLM에는 DB row를 그대로 많이 넣지 않는다. 전체 필터와 기업별 필터가 서로 다른 비교 범위를 갖도록 evidence pack을 분리한다.

전체 필터용 evidence pack:

```json
{
  "peer": {
    "id": "all",
    "name": "전체 경쟁사"
  },
  "comparison_mode": "overall_competitors_vs_sk_ax",
  "comparison_rule": "삼성SDS, LG CNS, 현대오토에버, 포스코DX 전체를 SK AX와 비교한다.",
  "reference_company": {
    "peer": {
      "id": "sk_ax",
      "name": "SK AX"
    },
    "business_signals": [],
    "financial_metrics": []
  },
  "competitors": [
    {
      "peer": {
        "id": "samsung_sds",
        "name": "삼성SDS"
      },
      "business_signals": [],
      "financial_metrics": []
    }
  ]
}
```

기업별 필터용 evidence pack:

```json
{
  "peer": {
    "id": "samsung_sds",
    "name": "삼성 SDS"
  },
  "comparison_mode": "peer_vs_sk_ax",
  "comparison_rule": "삼성SDS와 SK AX만 비교한다.",
  "reference_company": {
    "peer": {
      "id": "sk_ax",
      "name": "SK AX"
    },
    "business_signals": [],
    "financial_metrics": []
  },
  "competitor": {
    "peer": {
      "id": "samsung_sds",
      "name": "삼성SDS"
    },
    "business_signals": [],
    "financial_metrics": []
  }
}
```

## Evidence Pack 구성 규칙

### 공통

```text
기업당 전체 입력은 6,000~8,000 tokens 이내로 제한
동일 기사 중복 제거
summary와 evidence_text가 거의 같으면 summary 우선
URL은 출력 근거 연결용으로 유지
기존 SWOT 섹션은 입력에서 제외
```

### 우선순위

```text
1순위: raw_article_business_signals의 summary/evidence_text
2순위: raw_articles의 원문 제목, 날짜, 링크, 관련 본문 요약
3순위: raw_article_financial_metrics의 최신 재무 지표
4순위: peer_companies의 keywords/core_keywords/description
```

### 부족한 경우

```text
최근 180일 데이터가 부족하면 365일로 확장
그래도 부족하면 기존 기업 프로필과 financial_history만 사용
그래도 근거가 부족한 SWOT 항목은 "insufficient_evidence": true로 표시
```

## 프롬프트 설계

### System Prompt

```text
너는 B2B IT 서비스 기업 비교 분석을 수행하는 전략 분석가다.
반드시 제공된 evidence pack 안의 정보만 사용한다.
제공되지 않은 사실, 시장 점유율, 수주 여부, 기술명, 재무 수치를 추측하지 않는다.
SWOT 항목은 기업 자체의 강점/약점/기회/위협을 분석하되, 화면 사용자가 Peer+에서 비교할 수 있도록 구체적인 사업 활동과 기술 흐름을 함께 설명한다.
원문 문장을 그대로 복사하지 말고 한국어로 자연스럽게 바꿔 설명한다.
근거가 부족하면 억지로 채우지 말고 insufficient_evidence=true로 표시한다.
출력은 반드시 지정된 JSON schema만 따른다.
```

### User Prompt Template

```text
아래 evidence pack을 바탕으로 {peer_name}의 Peer+ 비교 포인트와 SWOT을 분석해줘.
모든 분석은 SK AX와 비교했을 때의 의미가 드러나야 한다.

분석 목표:
- comparison_points에는 사업 신호, 기술 신호, 리스크를 각각 1개씩 도출한다.
- swot에는 Strength, Weakness, Opportunity, Threat를 각각 1개씩 도출한다.
- comparison_mode가 overall_competitors_vs_sk_ax이면 삼성SDS, LG CNS, 현대오토에버, 포스코DX 전체를 SK AX와 비교한다.
- comparison_mode가 peer_vs_sk_ax이면 해당 기업 1개와 SK AX만 비교한다.
- overall_competitors_vs_sk_ax 결과의 body, title, check_point, overall_check_point에서는 특정 경쟁사명을 콕 집어 대표처럼 말하지 않는다.
- overall_competitors_vs_sk_ax 결과에서는 "전체 경쟁사는", "경쟁사 전반은", "여러 경쟁사는"처럼 묶어서 표현한다.
- overall_competitors_vs_sk_ax에서 특정 기업 하나의 근거만 있는 내용은 전체 경향으로 쓰지 말고 insufficient_evidence=true로 표시한다.
- 각 항목은 단순 키워드가 아니라 "왜 그렇게 판단했는지"가 드러나야 한다.
- 각 항목에는 반드시 입력 evidence 중 어떤 근거를 사용했는지 연결한다.
- 화면에는 "SK AX 관점"이라는 표현을 쓰지 않는다.
- 다만 비교 화면에서 사용될 수 있도록 "확인 포인트"에는 이 기업을 비교할 때 무엇을 봐야 하는지 적는다.
- 원문을 그대로 복사하지 말고, 원문에 담긴 사실을 자연어로 바꿔 설명한다.
- 근거가 부족한 항목은 과장하지 말고 insufficient_evidence=true로 표시한다.

분류 기준:
- 사업 신호: 비교 대상이 실제로 진행하는 사업명/활동명/고객 산업을 먼저 쓰고, 그 사업 방향이 SK AX와 비교해 무엇을 보여주는지 설명한다.
- 기술 신호: 해당 사업을 가능하게 하는 기술명/제품명/플랫폼명/구현 역량을 먼저 쓰고, SK AX와 비교해 무엇이 다른지 설명한다.
- 리스크: SK AX와 비교할 때 비교 대상에게 확인되는 사업 의존도, 수익성 부담, 신규 사업 부재, 규제/비용/경쟁 변수 등 구체적 제약.
- Strength: SK AX와 비교했을 때 비교 대상이 더 강하게 보이는 구체적 사업/기술/운영/실적상의 강점.
- Weakness: SK AX와 비교했을 때 비교 대상의 구체적 제약, 의존도, 수익성 부담, 제한된 범위.
- Opportunity: SK AX와 비교해 비교 대상이 잡을 수 있는 구체적 시장, 고객 산업, 기술 수요, 신규 사업 기회.
- Threat: SK AX와 비교할 때 비교 대상에게 부담이 되는 구체적 경쟁, 규제, 비용, 실적 변동, 기술 변화.

출력 규칙:
- title은 18자 이내의 명사형 문구로 작성한다.
- body는 1~2문장, 240자 이내로 작성한다.
- check_point는 1문장, 120자 이내로 작성한다.
- evidence_summary는 원문 복사가 아니라 근거 내용을 풀어쓴 1문장으로 작성한다.
- body와 evidence_summary에는 원문 근거에서 확인되는 구체적 사업/기술/활동명 중 최소 1개를 포함한다.
- evidence_summary는 "원문에는 무엇을 한다고 되어 있고, 그래서 어떻게 판단했다"의 구조로 작성한다.
- overall_competitors_vs_sk_ax의 body는 특정 기업명 없이 작성한다.
- overall_competitors_vs_sk_ax의 evidence_summary에는 어떤 근거들이 같은 방향을 보였는지 설명할 때 기업명을 2개 이상 함께 언급할 수 있다.
- overall_competitors_vs_sk_ax의 evidence_refs는 가능하면 서로 다른 경쟁사 근거 2개 이상을 포함한다.
- evidence_refs는 사용한 evidence id를 1~3개 넣는다.
- confidence는 0.0~1.0 사이 숫자로 작성한다.

Evidence Pack:
{evidence_pack_json}
```

## 출력 JSON Schema

LLM 출력은 프론트에서 바로 쓰기보다 백엔드에서 검증한 뒤 저장한다.

```json
{
  "peer_id": "samsung_sds",
  "peer_name": "삼성 SDS",
  "comparison_mode": "peer_vs_sk_ax",
  "source_mode": "generated_from_evidence",
  "analysis_window_days": 180,
  "comparison_points": [
    {
      "label": "사업 신호",
      "body": "삼성SDS는 기업용 AI 서비스와 클라우드 인프라를 함께 제시해, SK AX와 비교해 엔터프라이즈 AI 운영 영역을 강하게 전개하고 있다.",
      "evidence_summary": "원문에는 삼성SDS가 기업 업무 시스템 자동화와 생성형 AI용 클라우드 인프라를 함께 제공한다는 내용이 담겨 있다.",
      "evidence_refs": ["signal:456"],
      "source_urls": ["https://example.com/article"],
      "confidence": 0.86,
      "insufficient_evidence": false
    }
  ],
  "swot": [
    {
      "label": "Strength",
      "title": "AI·물류 통합 역량",
      "body": "삼성SDS는 IT서비스와 디지털 물류를 함께 운영하며, SK AX와 비교해 산업 운영 데이터와 엔터프라이즈 IT를 연결하는 축이 뚜렷하다.",
      "check_point": "IT서비스와 물류 운영 역량이 함께 제시되는지 확인한다.",
      "evidence_summary": "원문에는 클라우드·생성형 AI 서비스와 Cello 기반 물류 사업이 양대 축으로 설명되어 있다.",
      "evidence_refs": ["article:123", "signal:456"],
      "source_urls": ["https://example.com/article"],
      "confidence": 0.86,
      "insufficient_evidence": false
    },
    {
      "label": "Weakness",
      "title": "물류 의존 수익 구조",
      "body": "물류 부문 비중이 크고 IT서비스 내 클라우드 존재감은 제한적으로 보일 수 있어, 고부가 IT 전환 속도가 핵심 제약으로 남는다.",
      "check_point": "매출 비중과 수익성 개선이 함께 나타나는지 확인한다.",
      "evidence_summary": "기업 분석 원문은 물류 비중이 IT서비스보다 높고 클라우드 존재감이 제한적이라고 정리한다.",
      "evidence_refs": ["article:123"],
      "source_urls": ["https://example.com/article"],
      "confidence": 0.78,
      "insufficient_evidence": false
    }
  ],
  "analysis_trace": [
    {
      "step": "근거 확인",
      "summary": "원문 기반 신호에서 기업용 AI 서비스, 클라우드 인프라, 디지털 물류 운영이 확인되었다.",
      "evidence_refs": ["signal:456", "article:123"]
    },
    {
      "step": "비교 판단",
      "summary": "SK AX가 AI/Digital Transformation과 클라우드 기반 IT서비스를 제시하는 가운데, 비교 대상은 물류 운영 데이터와 엔터프라이즈 IT를 함께 연결하는 차이가 있다.",
      "evidence_refs": ["signal:456"]
    },
    {
      "step": "결론",
      "summary": "사업 신호는 엔터프라이즈 AI와 물류 운영 결합, 기술 신호는 클라우드 인프라와 생성형 AI 서비스, 리스크는 물류 매출 의존성을 중심으로 정리했다.",
      "evidence_refs": ["signal:456", "article:123"]
    }
  ],
  "overall_check_point": "이 기업은 어떤 사업 축이 실제 실행 신호로 이어지는지, 그리고 그 축이 수익성이나 리스크와 함께 움직이는지 확인하는 것이 중요하다."
}
```

## 화면 표시안

### SWOT 카드

각 카드에는 아래만 노출한다.

```text
라벨: Strength / Weakness / Opportunity / Threat
제목: title
본문: body
```

### Info 또는 상세 모달

카드 클릭 또는 info 아이콘에서는 아래를 보여준다.

```text
확인 포인트: check_point
근거 요약: evidence_summary
원문 링크: source_urls[0..2]
신뢰도: 내부 디버그 또는 관리자 화면에서만 표시
```

일반 사용자 화면에는 `confidence`, `evidence_refs`, `source_mode` 같은 내부 필드는 노출하지 않는다.

## 새 SWOT 생성 원칙

```text
기존 SWOT 데이터는 사용하지 않는다.
  -> raw_articles.content 안의 "## SWOT 분석" 섹션 제외
  -> Strength/Weakness/Opportunity/Threat로 이미 정리된 문단 제외
  -> 기존 SWOT 제목이나 문장을 seed/fallback으로 사용하지 않음

새 SWOT은 아래 근거만 사용한다.
  -> 원문 기반 사업 신호
  -> 원문 기사 제목/날짜/본문 요약
  -> 재무 지표
  -> 기업 기본 정보

비교 기준
  -> 전체는 경쟁사 4개사 전체를 SK AX와 비교
  -> 기업별은 선택 기업 1개와 SK AX만 비교
  -> 비교 대상의 사업/기술 진행 방향과 SK AX의 사업/기술 진행 방향이 함께 드러나야 함

근거 부족
  -> 해당 항목 insufficient_evidence=true
  -> 화면에는 "확인 가능한 근거가 부족합니다" 형태의 보수적 문구 표시
```

## 품질 검증 규칙

LLM 응답 저장 전 아래를 검증한다.

```text
swot이 Strength, Weakness, Opportunity, Threat를 각각 1개씩 포함하는지
각 swot item에 evidence_refs가 1개 이상 있는지
evidence_refs가 실제 입력 evidence id 안에 존재하는지
source_urls가 입력 데이터의 URL 안에서만 나왔는지
body/check_point/evidence_summary가 비어 있지 않은지
insufficient_evidence=false인데 evidence_refs가 없는 항목은 실패 처리
analysis_trace가 근거 확인, 비교 판단, 결론 3단계를 포함하는지
analysis_trace의 evidence_refs가 실제 입력 evidence id 안에 존재하는지
overall_competitors_vs_sk_ax의 body/title/check_point/overall_check_point에 특정 경쟁사명이 들어가면 실패 처리
입력에 없는 회사명/수치/사업명을 새로 만들어내면 실패 처리
```

## 재시도 프롬프트

검증 실패 시 아래 프롬프트로 1회 재시도한다.

```text
이전 응답은 검증에 실패했다.
실패 사유:
{validation_errors}

다시 작성하라.
반드시 evidence_refs에 존재하는 근거만 사용하고, 입력에 없는 사실은 쓰지 마라.
근거가 부족한 항목은 insufficient_evidence=true로 표시하라.
JSON schema 외의 텍스트는 출력하지 마라.
```

## 저장 방식

초기에는 별도 테이블 없이 메모리 캐시로 시작할 수 있다. 다만 운영에서는 스냅샷 저장을 권장한다.

권장 스냅샷 구조:

```text
peer_swot_snapshots
- id
- peer_id
- source_mode
- analysis_window_days
- payload jsonb
- evidence_hash
- model_name
- prompt_version
- generated_at
- expires_at
```

`evidence_hash`는 evidence pack의 핵심 입력을 hash한 값이다. 같은 evidence_hash가 있으면 LLM을 다시 호출하지 않고 기존 결과를 재사용한다.

## 추천 prompt version

```text
peer_swot_v1
```

버전 변경 기준:

- SWOT 분류 기준 변경
- 출력 JSON schema 변경
- evidence pack 필드 변경
- 화면 문구 정책 변경

## 비용과 지연 관리

```text
기업별 1회 호출
대상 기업 5개면 최대 5회 호출
페이지 진입 시 호출 금지
배치 또는 관리자 갱신 버튼으로 생성
TTL은 1일 또는 원천 데이터 갱신 시까지 유지
```

원천 데이터가 자주 바뀌지 않으므로 매 요청마다 LLM을 호출할 필요가 없다.

## 최종 권장안

```text
1. 기존 SWOT 섹션은 전부 무시한다.
2. raw_article_business_signals 중심으로 기업별 evidence pack을 생성한다.
3. raw_articles는 원문 제목, 날짜, URL, 관련 본문 요약 근거로 사용한다.
4. financial_metrics는 강점/약점/위협 판단의 보조 근거로 사용한다.
5. 전체 필터는 peer 4개사 그룹과 SK AX를 비교하는 별도 결과로 생성한다.
6. 기업별 필터는 선택 기업 1개와 SK AX를 비교하는 별도 결과로 생성한다.
7. 카드뉴스는 입력 품질이 안정화되기 전까지 사용하지 않는다.
8. LLM 출력은 JSON schema로 강제한다.
9. 검증 통과 결과만 스냅샷/캐시에 저장한다.
10. Peer+ 화면은 저장된 LLM SWOT 결과만 조회한다.
```
