# 섹터별 검색 관심도 변화 그래프

## 1. 기능 목적

홈 대시보드의 `섹터별 검색 관심도 변화` 그래프는 AXIS가 추적하는 주요 섹터가 최근 며칠 동안 검색 시장에서 얼마나 관심을 더 받거나 덜 받았는지 보여주는 기능이다.

현재 화면에 표시하는 섹터는 고정 4개다.

- AX
- 사이버보안
- 인프라
- 수주

이 그래프는 단순히 "오늘 관심도가 높다"를 보여주는 차트가 아니라, 전일 대비 관심도 변화량을 `pt` 단위로 보여준다. 그래서 사용자는 특정 날짜에 어떤 섹터의 검색 관심도가 갑자기 올라갔는지, 또는 내려갔는지를 빠르게 볼 수 있다.

## 2. 관심도와 pt의 차이

이 기능에서 가장 헷갈리기 쉬운 부분은 `관심도`와 `pt`가 서로 다른 값이라는 점이다.

| 구분 | 의미 | 예시 |
| --- | --- | --- |
| 관심도 | 네이버 데이터랩에서 내려온 해당 날짜의 검색 관심도 원값 | 수주 관심도 84.98 |
| pt | 전일 관심도와 비교한 변화량 | 전일 27.31, 오늘 84.98이면 `+57.67pt` |

그래프의 선은 `관심도 원값`이 아니라 `pt 변화량`을 그린다.

상세 패널에는 둘 다 보여준다.

- `+57.67pt`: 전일 대비 변화량
- `관심도 84.98`: 해당 날짜의 검색 관심도 원값

예를 들어 `06.04`에 수주가 `관심도 84.98`, `+57.67pt`로 보인다면, 그래프에는 `+57.67pt` 위치에 점이 찍히고, 상세 패널에는 원값인 `84.98`도 함께 표시된다.

## 3. 데이터 출처

데이터는 백엔드 DB의 PostgreSQL `raw_articles` 테이블에서 가져온다.

검색 관심도 데이터는 일반 기사와 같은 테이블에 저장되지만, `source_type`으로 구분한다.

```sql
source_type = 'search_trend'
```

주요 필드는 다음과 같다.

| 위치 | 필드 | 의미 |
| --- | --- | --- |
| `raw_articles.source_type` | `search_trend` | 검색 관심도 데이터 여부 |
| `raw_articles.metadata ->> 'group_name'` | AX, 사이버보안, 인프라, 수주 | 섹터명 |
| `raw_articles.metadata ->> 'period'` | `2026-06-10` 같은 날짜 | 관심도 기준일 |
| `raw_articles.metadata ->> 'ratio'` | 숫자 | 해당 날짜의 관심도 원값 |
| `raw_articles.metadata ->> 'time_unit'` | `date` | 일별 데이터 여부 |
| `raw_articles.metadata ->> 'source'` | `naver_datalab` | 원천 데이터 출처 |
| `raw_articles.metadata -> 'cause_analysis'` | JSON | 급등/급락 원인 분석 근거 |

`group_name`이 비어 있을 경우에는 예외적으로 `company::jsonb ->> 0` 값을 보조로 사용한다.

## 4. 전체 처리 흐름

전체 흐름은 아래 순서로 동작한다.

1. 네이버 데이터랩 기반 검색 관심도 데이터가 `raw_articles`에 `source_type='search_trend'`로 저장된다.
2. 백엔드 `DashboardKeywordTrendChartService`가 DB에서 최근 검색 관심도 데이터를 읽는다.
3. 같은 섹터와 같은 날짜 데이터가 여러 건 있으면 가장 최신 수집본만 사용한다.
4. 표시 대상 섹터를 AX, 사이버보안, 인프라, 수주 4개로 고정한다.
5. 최근 14일 데이터를 읽어 급등/급락 판정에 사용한다.
6. 화면에는 최근 7일 데이터만 내려준다.
7. 각 섹터별로 전일 관심도와 비교해 `ratio_delta`를 계산한다.
8. `ratio_delta`가 급등/급락 기준을 통과하면 인사이트 문구와 근거를 만든다.
9. 프론트엔드는 `/api/dashboard/keyword-trends`를 호출해 그래프와 상세 패널을 렌더링한다.

관련 백엔드 파일은 다음이다.

- `axis-backend/src/main/java/com/skala/axis/controller/DashboardController.java`
- `axis-backend/src/main/java/com/skala/axis/service/DashboardKeywordTrendChartService.java`

관련 프론트엔드 파일은 다음이다.

- `axis-frontend/src/features/dashboard/api/dashboardRepository.ts`
- `axis-frontend/src/features/dashboard/hooks/useDashboard.ts`
- `axis-frontend/src/features/dashboard/model/dashboard.ts`
- `axis-frontend/src/app/components/pages/home/dashboard/HomeDashboardView.tsx`

## 5. 백엔드 API

프론트엔드는 아래 API를 호출한다.

```http
GET /api/dashboard/keyword-trends
```

응답 구조는 대략 다음과 같다.

```json
{
  "keywordSearchPoints": [
    {
      "date": "06.04",
      "trend1": 11.92,
      "trend1Ratio": 44.04,
      "trend2": 12.24,
      "trend2Ratio": 26.62,
      "trend3": 11.11,
      "trend3Ratio": 72.31,
      "trend4": 57.67,
      "trend4Ratio": 84.98
    }
  ],
  "keywordSeries": [
    {
      "key": "trend1",
      "name": "AX",
      "color": "#EE7501",
      "total": "58"
    }
  ],
  "keywordInsights": [
    {
      "key": "trend4",
      "time": "06.08",
      "title": "수주 관련 검색 관심도 급등",
      "valueLabel": "98 / +67.93pt",
      "reason": "..."
    }
  ],
  "sourceName": "naver_datalab",
  "cachedAt": "2026-06-11T07:12:20Z",
  "stale": false
}
```

필드 의미는 다음과 같다.

| 필드 | 의미 |
| --- | --- |
| `keywordSearchPoints` | 날짜별 차트 데이터 |
| `date` | 화면 표시용 날짜 |
| `trend1`, `trend2`, ... | 그래프에 그리는 전일 대비 변화량, 단위는 pt |
| `trend1Ratio`, `trend2Ratio`, ... | 해당 날짜의 관심도 원값 |
| `keywordSeries` | 선 색상, 이름, key 매핑 정보 |
| `keywordInsights` | 급등/급락 포인트와 원인 분석 |
| `sourceName` | 데이터 출처 |
| `cachedAt` | 백엔드 캐시가 만들어진 시간 |
| `stale` | 캐시 TTL이 지난 상태인지 여부 |

`/api/dashboard/summary`에는 이 그래프 데이터가 들어가지 않는다. 홈 화면에서 주식 차트와 검색 관심도 차트를 분리해서 호출하기 위해 검색 관심도는 `/api/dashboard/keyword-trends`에서 따로 가져온다.

## 6. DB 조회와 중복 제거 기준

백엔드는 `raw_articles`에서 `source_type='search_trend'`인 행만 읽는다.

같은 섹터, 같은 날짜 데이터가 여러 번 수집될 수 있으므로 아래 기준으로 하나만 남긴다.

- `group_name`
- `period`
- 최신 `collected_at`
- 같은 시간이면 더 큰 `id`

즉, 같은 날짜의 같은 섹터 데이터 중 가장 최근에 수집된 값을 화면에 사용한다.

개념적으로는 아래와 같은 조회 흐름이다.

```sql
WITH trend_rows AS (
    SELECT
        id,
        collected_at,
        metadata ->> 'group_name' AS group_name,
        (metadata ->> 'period')::date AS period,
        NULLIF(metadata ->> 'ratio', '')::numeric AS ratio,
        metadata -> 'cause_analysis' AS cause_analysis,
        ROW_NUMBER() OVER (
            PARTITION BY metadata ->> 'group_name', (metadata ->> 'period')::date
            ORDER BY collected_at DESC NULLS LAST, id DESC
        ) AS rn
    FROM raw_articles
    WHERE source_type = 'search_trend'
)
SELECT *
FROM trend_rows
WHERE rn = 1
  AND group_name IN ('AX', '사이버보안', '인프라', '수주');
```

실제 서비스 코드는 `time_unit='date'`, fallback group name, source name 등도 함께 처리한다.

## 7. 급등/급락 포인트 선정 기준

급등/급락 포인트는 단순히 `50pt`를 넘었다고 모두 표시하지 않는다.

먼저 후보 조건을 통과해야 한다.

```text
abs(ratio_delta) >= 50pt
```

예시는 다음과 같다.

| 변화량 | 후보 여부 |
| --- | --- |
| `+57.67pt` | 후보 |
| `-51.81pt` | 후보 |
| `+46.64pt` | 후보 아님 |

후보가 된 뒤에도 추가 조건을 하나 더 통과해야 실제 급등/급락 포인트로 표시된다.

둘 중 하나라도 만족하면 통과한다.

```text
A. 해당 섹터의 lookback 기간 중 절대 변화량이 최대값이다.
B. 해당 섹터의 평균 절대 변화량보다 2.5배 이상 크다.
```

현재 설정값은 다음과 같다.

| 설정 | 기본값 | 의미 |
| --- | --- | --- |
| `axis.dashboard.keyword-spike-delta-threshold` | `50` | 급등/급락 후보 최소 변화량 |
| `axis.dashboard.keyword-spike-average-multiplier` | `2.5` | 평균 변화량 대비 이상치 배수 |
| `SPIKE_LOOKBACK_DAYS` | `14` | 급등/급락 판정에 사용할 최근 기간 |
| `CHART_DAYS` | `7` | 화면에 표시할 최근 기간 |

이렇게 만든 이유는 `50pt 이상`이라는 절대 기준만 쓰면 반복적인 큰 등락이 모두 급등/급락으로 찍힐 수 있기 때문이다.

그래프에서 진짜로 주목해야 하는 포인트는 단순히 큰 숫자가 아니라, 해당 섹터의 최근 흐름 안에서 가장 이례적이거나 평균적인 변동폭보다 뚜렷하게 튀는 변화다. 그래서 1차로 `50pt` 이상인 큰 변화만 후보로 걸러내고, 2차로 `lookback 최대값` 또는 `평균 대비 2.5배` 조건을 적용해 노이즈를 줄인다.

## 8. 원인 분석 생성 방식

급등/급락 포인트가 선정되면 백엔드는 원인 분석 문구와 근거를 만든다.

우선순위는 다음과 같다.

1. 검색 관심도 행의 `metadata.cause_analysis` JSON을 먼저 사용한다.
2. `cause_analysis.keyword_drivers` 안의 근거 기사, 카드뉴스, 통합 이슈, 비즈니스 신호를 화면 표시용 evidence로 변환한다.
3. `cause_analysis`가 없거나 화면에 보여줄 근거가 부족하면 fallback으로 일반 기사 데이터를 조회한다.

fallback 조회는 같은 `raw_articles` 테이블에서 검색 트렌드가 아닌 일반 기사만 대상으로 한다.

```sql
SELECT id, source_name, title, url, published_at
FROM raw_articles
WHERE source_type <> 'search_trend'
  AND COALESCE(published_at::date, collected_at::date)
      BETWEEN (:period::date - INTERVAL '3 days') AND :period::date
  AND (
      title ILIKE '%섹터명%'
      OR content ILIKE '%섹터명%'
  )
ORDER BY COALESCE(published_at, collected_at) DESC NULLS LAST, id DESC
LIMIT 3;
```

이 방식 때문에 급등 포인트는 있는데 원인 분석 근거가 부족한 경우에는 "바로 연결되는 출처가 충분하지 않다"는 식의 보수적인 문구가 나올 수 있다.

## 9. 프론트엔드 표시 방식

프론트엔드의 호출 흐름은 다음과 같다.

1. `dashboardRepository.getKeywordTrends()`가 `/api/dashboard/keyword-trends`를 호출한다.
2. `useDashboardKeywordTrends()` 훅이 데이터를 로딩한다.
3. `HomeDashboardView.tsx`가 `keywordSearchPoints`, `keywordSeries`, `keywordInsights`를 차트에 전달한다.
4. Recharts `LineChart`가 `trend1`, `trend2`, `trend3`, `trend4` 값을 y축에 그린다.
5. 날짜를 클릭하면 해당 날짜의 상세 패널에서 각 섹터의 `pt 변화량`과 `관심도 원값`을 같이 보여준다.
6. `keywordInsights`에 있는 날짜/시리즈는 급등/급락 포인트로 강조 표시된다.

차트의 y축 단위가 `pt`인 이유는 `trendN` 값이 관심도 원값이 아니라 전일 대비 변화량이기 때문이다.

## 10. 실제 데이터 예시

최근 확인한 DB 데이터 예시는 다음과 같다.

| 날짜 | 섹터 | 관심도 | 전일 대비 |
| --- | --- | ---: | ---: |
| 2026-06-04 | 수주 | 84.98 | `+57.67pt` |
| 2026-06-06 | 수주 | 34.75 | `-51.81pt` |
| 2026-06-08 | AX | 70.39 | `+46.64pt` |
| 2026-06-08 | 인프라 | 100.00 | `+41.16pt` |
| 2026-06-08 | 수주 | 98.19 | `+67.93pt` |

여기서 `+57.67pt`, `-51.81pt`, `+67.93pt`는 50pt 이상이므로 후보가 된다.

하지만 실제 급등/급락 포인트로 표시되는 것은 추가 조건까지 통과한 값이다. 예를 들어 수주의 lookback 기간 중 가장 큰 절대 변화량이 `+67.93pt`라면, `+57.67pt`와 `-51.81pt`는 50pt를 넘었더라도 최종 급등/급락 포인트로 선택되지 않을 수 있다.

## 11. 운영 확인 방법

API가 실제 데이터를 내려주는지는 다음으로 확인한다.

```bash
curl -s https://axis-team13.skala25a.project.skala-ai.com/api/dashboard/keyword-trends
```

확인할 포인트는 다음이다.

- HTTP 200인지
- `keywordSeries`가 AX, 사이버보안, 인프라, 수주인지
- `keywordSearchPoints`에 최근 7일 날짜가 있는지
- `trendN` 값과 `trendNRatio` 값이 같이 있는지
- `sourceName`이 `naver_datalab`인지
- `cachedAt`이 너무 오래되지 않았는지
- `stale`이 `false`인지

DB에서 직접 확인할 때는 `raw_articles`의 `search_trend` 데이터를 보면 된다.

```sql
SELECT
    metadata ->> 'group_name' AS group_name,
    (metadata ->> 'period')::date AS period,
    NULLIF(metadata ->> 'ratio', '')::numeric AS ratio,
    metadata ->> 'source' AS source
FROM raw_articles
WHERE source_type = 'search_trend'
  AND metadata ->> 'group_name' IN ('AX', '사이버보안', '인프라', '수주')
ORDER BY period DESC, group_name ASC;
```

## 12. 캐시와 배포 시 주의점

`/api/dashboard/keyword-trends`는 요청마다 DB를 바로 읽지 않고 백엔드 메모리 캐시를 반환한다.

- 서버 시작 시 한 번 캐시를 만든다.
- 이후 기본 15분 간격으로 갱신한다.
- 응답에는 `cachedAt`, `stale`이 포함된다.

DB 데이터가 바뀌었는데 화면이 바로 바뀌지 않는다면 다음을 확인한다.

- 백엔드가 최신 이미지로 배포됐는지
- `/api/dashboard/keyword-trends`의 `cachedAt`이 최신인지
- 캐시 TTL이 지났는데도 `stale=false`인지
- 클러스터 백엔드와 로컬 `18080` 백엔드를 혼동하고 있지 않은지

## 13. 관련 기능과의 차이

이 그래프는 홈 화면의 `Today's Insight`와 별도 기능이다.

| 기능 | API | 목적 |
| --- | --- | --- |
| 섹터별 검색 관심도 변화 그래프 | `/api/dashboard/keyword-trends` | 섹터별 검색 관심도 변화량과 급등/급락 포인트 표시 |
| 홈 대시보드 summary | `/api/dashboard/summary` | 주식 차트 등 홈 기본 요약 데이터 |
| Today's Insight | `/api/dashboard/today-insight` | AI가 생성한 오늘의 주요 인사이트 |

따라서 검색 관심도 그래프가 정상이어도 `Today's Insight` 호출이 실패할 수 있고, 반대로 `Today's Insight`가 정상이어도 검색 관심도 그래프 데이터가 비어 있을 수 있다.

## 14. 한 줄 요약

`섹터별 검색 관심도 변화` 그래프는 PostgreSQL `raw_articles`에 저장된 네이버 데이터랩 일별 검색 관심도 데이터를 읽고, AX/사이버보안/인프라/수주 4개 섹터의 전일 대비 변화량을 계산해 최근 7일 그래프로 보여주는 기능이다. 급등/급락 표시는 `50pt 이상`인 변화 중에서도 최근 14일 흐름에서 가장 이례적인 값만 골라 표시하도록 설계되어 있다.
