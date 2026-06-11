-- V44: Saved baseline reports for the 2026-06-11 review day.
--
-- These rows are not mock agent output. They give empty environments one
-- explicit saved baseline while preserving provenance that the content was
-- manually seeded from the current implementation review.

ALTER TABLE briefing_reports
    DROP CONSTRAINT IF EXISTS briefing_reports_type_check;

ALTER TABLE briefing_reports
    ADD CONSTRAINT briefing_reports_type_check
        CHECK (briefing_type IN ('daily', 'weekly', 'monthly', 'custom'));

INSERT INTO briefing_reports (
    id,
    title,
    briefing_type,
    date_from,
    date_to,
    report_date,
    period_label,
    status,
    progress,
    key_summary,
    sk_implication,
    related_card_ids,
    related_raw_article_ids,
    payload,
    legacy_payload,
    error_message,
    confidence,
    provenance,
    created_at,
    completed_at
)
SELECT
    'BR-DAILY-20260611-BASELINE',
    '2026-06-11 AXIS 백엔드 구현 현황 브리핑',
    'daily',
    DATE '2026-06-11',
    DATE '2026-06-11',
    DATE '2026-06-11',
    '2026. 06. 11 일간',
    'completed',
    1.00,
    '브리핑과 Today Insight는 빈 성공 응답 대신 저장 결과 또는 호출 실패 상태를 명확히 보여야 합니다.',
    '우선순위는 대화 소유권 검증, 공개 API 정책, 브리핑 저장 스키마 정합성, agent 실패 표시입니다.',
    ARRAY[]::text[],
    ARRAY[]::bigint[],
    jsonb_build_object(
        'id', 'BR-DAILY-20260611-BASELINE',
        'agent', 'ManualBaseline',
        'agent_result', false,
        'prompt_version', 'manual-baseline-2026-06-11',
        'title', '2026-06-11 AXIS 백엔드 구현 현황 브리핑',
        'briefing_type', 'daily',
        'date_from', '2026-06-11',
        'date_to', '2026-06-11',
        'report_date', '2026-06-11',
        'period_label', '2026. 06. 11 일간',
        'status', 'completed',
        'progress', 1.0,
        'briefing_lead', '현재 구현은 인증, 카드뉴스, 북마크, mixer, Today Insight 연동까지 갖췄지만 브리핑 조회와 일부 저장 경로는 운영 스키마와 맞지 않았습니다.',
        'key_summary', '빈 브리핑 성공 응답을 제거하고 저장된 결과를 읽도록 전환하는 것이 오늘의 핵심 조치입니다.',
        'executive_summary', '임직원용 MVP로는 충분한 기능 골격이 있으나, 임원 대상 화면에서는 빈 목업처럼 보이는 성공 응답보다 저장 결과와 호출 실패를 명확히 분리해야 합니다.',
        'immediate_trends', jsonb_build_array(
            jsonb_build_object(
                'title', '브리핑 조회 경로를 저장 결과 기반으로 전환',
                'reason', '/api/briefings가 빈 snapshot을 성공으로 반환하면 agent 미생성 상태가 정상 데이터처럼 보입니다.'
            ),
            jsonb_build_object(
                'title', '브리핑 저장 helper의 스키마 drift 수정 필요',
                'reason', 'axis-ai 저장 helper가 제거된 관계 테이블을 참조하면 생성 결과가 briefing_reports에 남지 않을 수 있습니다.'
            ),
            jsonb_build_object(
                'title', 'Today Insight는 호출 실패와 저장 cache fallback을 분리',
                'reason', 'axis-ai 연결 실패는 이미 실패 wrapper로 내려가며, 저장 결과가 있을 때만 cache fallback을 사용할 수 있습니다.'
            )
        ),
        'watch_trends', jsonb_build_array(
            jsonb_build_object(
                'title', 'Assistant conversation 소유권 검증',
                'reason', 'conversation_id가 외부에서 주입될 때 소유자 확인이 먼저 필요합니다.'
            ),
            jsonb_build_object(
                'title', '공개 GET API 범위 재정의',
                'reason', '임직원용 서비스라면 dashboard/card 공개 정책을 명확히 고정해야 합니다.'
            )
        ),
        'evidence_summary', jsonb_build_array(
            'BriefingController가 기존에는 emptyBriefingsData를 성공으로 반환했습니다.',
            'briefing_reports는 payload JSONB와 관련 id 배열을 기준으로 저장되는 현재 스키마입니다.',
            'Today Insight는 today_insight_reports 저장 결과를 cache fallback으로 사용할 수 있습니다.'
        ),
        'sections', jsonb_build_array(
            jsonb_build_object(
                'title', '오늘 반영할 백엔드 조치',
                'items', jsonb_build_array(
                    jsonb_build_object(
                        'headline', '브리핑 조회 API를 저장 결과 기반으로 전환합니다.',
                        'source', 'axis-backend BriefingReportService'
                    ),
                    jsonb_build_object(
                        'headline', '저장 결과가 없으면 빈 성공 응답이 아니라 실패 상태를 반환합니다.',
                        'source', 'axis-backend BriefingController'
                    )
                )
            ),
            jsonb_build_object(
                'title', '후속 검토',
                'items', jsonb_build_array(
                    jsonb_build_object(
                        'headline', '프론트 브리핑 생성 요청의 save=false를 유지할지, 저장형 생성으로 바꿀지 결정해야 합니다.',
                        'source', 'axis-frontend useGeneratedBriefing'
                    )
                )
            )
        ),
        'briefing_basis', jsonb_build_object(
            'comparison_point', jsonb_build_object(
                'finding', '이전 상태는 빈 브리핑 화면이 정상 데이터처럼 보일 수 있었습니다.',
                'rationale', 'API wrapper는 success=true였고 dailySnapshot/weeklySnapshot이 비어 있었습니다.'
            ),
            'strategy_implication', jsonb_build_object(
                'finding', 'MVP에서는 저장 결과와 실패 상태를 분리하는 것이 데모 신뢰도를 높입니다.',
                'rationale', '임원 화면은 데이터 부재를 숨기기보다 원인과 상태를 명확히 보여야 합니다.'
            ),
            'recommended_actions', jsonb_build_array(
                '브리핑 저장 조회 경로를 회귀 테스트에 포함합니다.',
                'Today Insight와 브리핑 모두 agent 실패 시 화면에 호출 실패가 보이는지 확인합니다.',
                '생성 결과를 저장할지 여부를 제품 정책으로 결정합니다.'
            )
        ),
        'interpretation_flow', jsonb_build_object(
            'steps', jsonb_build_array(
                jsonb_build_object(
                    'seq', 1,
                    'label', '관찰',
                    'items', jsonb_build_array('브리핑 조회 API가 빈 성공 응답을 반환했습니다.')
                ),
                jsonb_build_object(
                    'seq', 2,
                    'label', '판단',
                    'items', jsonb_build_array('agent 결과 부재와 실제 저장 결과를 API 상태로 구분해야 합니다.')
                ),
                jsonb_build_object(
                    'seq', 3,
                    'label', '조치',
                    'items', jsonb_build_array('저장 브리핑 조회 서비스와 오늘자 baseline report를 추가합니다.')
                )
            )
        ),
        'provenance', jsonb_build_object(
            'source', 'manual_baseline',
            'agent_result', false,
            'result_kind', 'saved_manual_baseline',
            'created_for', 'empty_saved_briefing_environment',
            'review_date', '2026-06-11'
        )
    ),
    '{}'::jsonb,
    NULL,
    0.72,
    jsonb_build_object(
        'source', 'manual_baseline',
        'agent_result', false,
        'result_kind', 'saved_manual_baseline',
        'review_date', '2026-06-11'
    ),
    TIMESTAMPTZ '2026-06-11 08:10:00+09',
    TIMESTAMPTZ '2026-06-11 08:10:00+09'
WHERE NOT EXISTS (
    SELECT 1
      FROM briefing_reports
     WHERE id = 'BR-DAILY-20260611-BASELINE'
);

INSERT INTO today_insight_reports (
    report_date,
    schema_version,
    prompt_version,
    status,
    headline,
    executive_summary,
    executive_implication,
    source_integrated_issue_ids,
    source_card_ids,
    source_raw_article_ids,
    peer_ids,
    sectors,
    input_snapshot,
    output_payload,
    provenance,
    confidence,
    created_at,
    updated_at
)
SELECT
    DATE '2026-06-11',
    'today_insight_v1',
    'manual-baseline-2026-06-11',
    'active',
    '2026-06-11 기준 AXIS 백엔드 안정화 우선순위',
    '오늘 기준 핵심은 빈 성공 응답을 제거하고, agent 호출 실패와 저장 결과 부재를 사용자에게 명확히 구분해 보여주는 것입니다.',
    '임원 대상 MVP에서는 기능 수보다 신뢰 가능한 상태 표시가 중요합니다. 저장 결과가 없을 때 꾸며낸 데이터가 아니라 호출 실패 또는 미생성 상태가 드러나야 합니다.',
    ARRAY[]::uuid[],
    ARRAY[]::text[],
    ARRAY[]::bigint[],
    ARRAY['sk_ax']::text[],
    ARRAY['backend', 'security', 'operations']::text[],
    jsonb_build_object(
        'source', 'manual_baseline',
        'review_date', '2026-06-11',
        'inputs', jsonb_build_array(
            'backend code review findings',
            'briefing empty response behavior',
            'today_insight cache fallback behavior'
        )
    ),
    jsonb_build_object(
        'report_date', '2026-06-11',
        'headline', '2026-06-11 기준 AXIS 백엔드 안정화 우선순위',
        'executive_summary', '오늘 기준 핵심은 빈 성공 응답을 제거하고, agent 호출 실패와 저장 결과 부재를 사용자에게 명확히 구분해 보여주는 것입니다.',
        'executive_implication', '임원 대상 MVP에서는 기능 수보다 신뢰 가능한 상태 표시가 중요합니다. 저장 결과가 없을 때 꾸며낸 데이터가 아니라 호출 실패 또는 미생성 상태가 드러나야 합니다.',
        'confidence', 0.72,
        'peer_ids', jsonb_build_array('sk_ax'),
        'sectors', jsonb_build_array('backend', 'security', 'operations'),
        'signals', jsonb_build_array(
            jsonb_build_object(
                'id', 'briefing-empty-success',
                'label', '브리핑 상태',
                'value', '빈 성공 응답 제거',
                'reasoning', jsonb_build_array(
                    jsonb_build_object('stage', '관찰', 'detail', '/api/briefings가 저장 결과 없이 빈 snapshot을 성공으로 반환했습니다.'),
                    jsonb_build_object('stage', '판단', 'detail', 'agent 결과가 없으면 빈 목업처럼 보이는 데이터보다 실패 또는 미생성 상태가 맞습니다.')
                ),
                'evidence', jsonb_build_object(
                    'grounds', jsonb_build_array('BriefingController emptyBriefingsData 제거', 'BriefingReportService 저장 조회 추가'),
                    'changes', jsonb_build_array('stored briefing lookup', 'service unavailable on missing report')
                )
            ),
            jsonb_build_object(
                'id', 'briefing-save-schema',
                'label', '저장 경로',
                'value', 'briefing_reports 스키마 정합성 복구',
                'reasoning', jsonb_build_array(
                    jsonb_build_object('stage', '관찰', 'detail', 'axis-ai helper가 제거된 briefing_report_cards/articles 테이블을 참조했습니다.'),
                    jsonb_build_object('stage', '조치', 'detail', '관련 카드와 기사 id를 briefing_reports 배열 컬럼으로 저장하도록 맞춥니다.')
                ),
                'evidence', jsonb_build_object(
                    'grounds', jsonb_build_array('briefing_reports.payload JSONB', 'related_card_ids / related_raw_article_ids arrays'),
                    'changes', jsonb_build_array('schema drift reduced')
                )
            ),
            jsonb_build_object(
                'id', 'security-review',
                'label', '보안 우선순위',
                'value', '대화 소유권과 공개 API 정책 고정',
                'reasoning', jsonb_build_array(
                    jsonb_build_object('stage', '관찰', 'detail', 'Assistant conversation_id 소유권 검증과 dashboard/card 공개 범위가 의사결정 지점입니다.'),
                    jsonb_build_object('stage', '판단', 'detail', '임직원용 서비스는 데이터 노출 범위를 먼저 닫고 필요한 공개만 열어야 합니다.')
                ),
                'evidence', jsonb_build_object(
                    'grounds', jsonb_build_array('SecurityConfig public GET rules', 'AssistantConversationService ownership path'),
                    'changes', jsonb_build_array('review item')
                )
            )
        ),
        'recommended_actions', jsonb_build_array(
            jsonb_build_object(
                'action', 'Assistant conversation 소유권 검증을 chat prepare 경로에도 적용',
                'decision_owner', 'Backend',
                'time_horizon', '즉시',
                'rationale', '다른 사용자의 conversation_id 주입 가능성을 차단해야 합니다.',
                'evidence_refs', jsonb_build_array('AssistantConversationService')
            ),
            jsonb_build_object(
                'action', '브리핑/Today Insight 실패 표시를 QA 시나리오에 추가',
                'decision_owner', 'Backend + Frontend',
                'time_horizon', '이번 스프린트',
                'rationale', 'agent 미응답이 빈 화면이나 꾸며낸 데이터로 보이지 않게 해야 합니다.',
                'evidence_refs', jsonb_build_array('BriefingController', 'DashboardController')
            ),
            jsonb_build_object(
                'action', 'dashboard/cards 공개 GET 정책을 임직원용 기준으로 재검토',
                'decision_owner', 'Backend',
                'time_horizon', '릴리즈 전',
                'rationale', '데이터 민감도에 따라 인증 필수 여부를 결정해야 합니다.',
                'evidence_refs', jsonb_build_array('SecurityConfig')
            )
        ),
        'source_traces', jsonb_build_array(
            jsonb_build_object(
                'id', 'manual-baseline-2026-06-11',
                'title', '2026-06-11 backend review baseline',
                'source_name', 'repository_review',
                'url', ''
            )
        ),
        'change_summary', jsonb_build_array(
            jsonb_build_object('label', '브리핑', 'value', '저장 결과 기반 조회로 전환'),
            jsonb_build_object('label', '상태 표시', 'value', '빈 성공 응답 대신 실패 wrapper 사용'),
            jsonb_build_object('label', '저장 경로', 'value', '현재 briefing_reports 스키마와 정합화')
        ),
        'provenance', jsonb_build_object(
            'source', 'manual_baseline',
            'agent_result', false,
            'result_kind', 'saved_manual_baseline',
            'review_date', '2026-06-11'
        )
    ),
    jsonb_build_object(
        'source', 'manual_baseline',
        'agent_result', false,
        'result_kind', 'saved_manual_baseline',
        'review_date', '2026-06-11'
    ),
    0.72,
    TIMESTAMPTZ '2026-06-11 08:10:00+09',
    TIMESTAMPTZ '2026-06-11 08:10:00+09'
WHERE NOT EXISTS (
    SELECT 1
      FROM today_insight_reports
     WHERE report_date = DATE '2026-06-11'
       AND provenance->>'source' = 'manual_baseline'
);
