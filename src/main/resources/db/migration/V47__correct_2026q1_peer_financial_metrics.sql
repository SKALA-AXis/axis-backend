-- V47: Correct 2026Q1 Peer overview financial baselines.
--
-- Some environments still have `peer_financials`, while the deployed app reads
-- parser facts from `raw_article_financial_metrics`. Patch whichever table
-- exists so the migration is safe across both schema generations.

DO $$
BEGIN
    IF to_regclass('public.peer_financials') IS NULL THEN
        RETURN;
    END IF;

    EXECUTE $sql$
        WITH corrections(peer_id, period, report_date, revenue, op, margin, source, raw_payload) AS (
            VALUES
                ('sk_ax', '2025Q4', DATE '2026-03-31', 8500.0::double precision, 680.0::double precision, 8.0::double precision, 'manual',
                    jsonb_build_object('manual_correction', true, 'migration', 'V47', 'basis', 'SK AX IR', 'operating_margin_pct', 8.0)),
                ('sk_ax', '2026Q1', DATE '2026-04-30', 5300.0::double precision, 310.0::double precision, 5.85::double precision, 'manual',
                    jsonb_build_object('manual_correction', true, 'migration', 'V47', 'basis', 'SK AX IR', 'operating_margin_pct', 5.85)),
                ('lg_cns', '2025Q4', DATE '2026-01-27', 19357.0::double precision, 2160.0::double precision, 11.16::double precision, 'manual',
                    jsonb_build_object('manual_correction', true, 'migration', 'V47', 'basis', 'LG CNS official 2025 results', 'operating_margin_pct', 11.16)),
                ('lg_cns', '2026Q1', DATE '2026-04-30', 13150.0::double precision, 942.0::double precision, 7.16::double precision, 'manual',
                    jsonb_build_object('manual_correction', true, 'migration', 'V47', 'basis', 'LG CNS 2026Q1 IR', 'operating_margin_pct', 7.16)),
                ('samsung_sds', '2025Q4', DATE '2026-03-31', 35368.0::double precision, 2261.0::double precision, 6.39::double precision, 'manual',
                    jsonb_build_object('manual_correction', true, 'migration', 'V47', 'basis', 'Samsung SDS prior quarter baseline', 'operating_margin_pct', 6.39)),
                ('samsung_sds', '2026Q1', DATE '2026-04-30', 33529.0::double precision, 783.0::double precision, 2.3::double precision, 'manual',
                    jsonb_build_object('manual_correction', true, 'migration', 'V47', 'basis', 'Samsung SDS 2026Q1 IR', 'operating_margin_pct', 2.3)),
                ('hyundai_autoever', '2025Q4', DATE '2026-03-31', 13227.0::double precision, 765.0::double precision, 5.78::double precision, 'manual',
                    jsonb_build_object('manual_correction', true, 'migration', 'V47', 'basis', 'Hyundai AutoEver prior quarter baseline', 'operating_margin_pct', 5.78)),
                ('hyundai_autoever', '2026Q1', DATE '2026-04-30', 9357.0::double precision, 212.0::double precision, 2.3::double precision, 'manual',
                    jsonb_build_object('manual_correction', true, 'migration', 'V47', 'basis', 'Hyundai AutoEver 2026Q1 IR', 'operating_margin_pct', 2.3)),
                ('posco_dx', '2025Q4', DATE '2026-04-17', 2608.15::double precision, -12.17::double precision, -0.5::double precision, 'manual',
                    jsonb_build_object('manual_correction', true, 'migration', 'V47', 'basis', 'POSCO DX preliminary disclosure', 'operating_margin_pct', -0.5)),
                ('posco_dx', '2026Q1', DATE '2026-04-27', 2415.1::double precision, 36.58::double precision, 1.5::double precision, 'manual',
                    jsonb_build_object('manual_correction', true, 'migration', 'V47', 'basis', 'POSCO DX preliminary disclosure', 'operating_margin_pct', 1.5))
        )
        INSERT INTO peer_financials (
            peer_id,
            period,
            report_date,
            revenue_total_krwbn,
            operating_profit_krwbn,
            raw_payload,
            source
        )
        SELECT peer_id, period, report_date, revenue, op, raw_payload, source
        FROM corrections
        ON CONFLICT (peer_id, period) DO UPDATE SET
            report_date = EXCLUDED.report_date,
            revenue_total_krwbn = EXCLUDED.revenue_total_krwbn,
            operating_profit_krwbn = EXCLUDED.operating_profit_krwbn,
            raw_payload = COALESCE(peer_financials.raw_payload, '{}'::jsonb) || EXCLUDED.raw_payload,
            source = EXCLUDED.source
    $sql$;
END
$$;

DO $$
BEGIN
    IF to_regclass('public.raw_article_financial_metrics') IS NULL THEN
        RETURN;
    END IF;

    EXECUTE $sql$
        WITH corrections(peer_id, period, metric_name, metric_label, value_numeric, value_krwbn, unit, evidence_text) AS (
            VALUES
                ('sk_ax', '2025Q4', 'revenue_total', '매출', NULL::numeric, 8500.0::double precision, '억원', 'SK AX IR 기준 2025Q4 매출 8,500억원'),
                ('sk_ax', '2025Q4', 'operating_profit', '영업이익', NULL::numeric, 680.0::double precision, '억원', 'SK AX IR 기준 2025Q4 영업이익 680억원'),
                ('sk_ax', '2025Q4', 'operating_margin', '영업이익률', 8.0::numeric, NULL::double precision, '%', 'SK AX IR 기준 2025Q4 영업이익률 8.0%'),
                ('sk_ax', '2026Q1', 'revenue_total', '매출', NULL::numeric, 5300.0::double precision, '억원', 'SK AX IR 기준 2026Q1 매출 5,300억원'),
                ('sk_ax', '2026Q1', 'operating_profit', '영업이익', NULL::numeric, 310.0::double precision, '억원', 'SK AX IR 기준 2026Q1 영업이익 310억원'),
                ('sk_ax', '2026Q1', 'operating_margin', '영업이익률', 5.85::numeric, NULL::double precision, '%', 'SK AX IR 기준 2026Q1 영업이익률 5.85%'),
                ('lg_cns', '2025Q4', 'revenue_total', '매출', NULL::numeric, 19357.0::double precision, '억원', 'LG CNS 공식 2025년 실적 발표 기준 2025Q4 매출 1조9,357억원'),
                ('lg_cns', '2025Q4', 'operating_profit', '영업이익', NULL::numeric, 2160.0::double precision, '억원', 'LG CNS 공식 2025년 실적 발표 기준 2025Q4 영업이익 2,160억원'),
                ('lg_cns', '2025Q4', 'operating_margin', '영업이익률', 11.16::numeric, NULL::double precision, '%', 'LG CNS 2025Q4 매출 1조9,357억원, 영업이익 2,160억원 기준 영업이익률 약 11.16%'),
                ('lg_cns', '2026Q1', 'revenue_total', '매출', NULL::numeric, 13150.0::double precision, '억원', 'LG CNS 2026Q1 매출 1조3,150억원'),
                ('lg_cns', '2026Q1', 'operating_profit', '영업이익', NULL::numeric, 942.0::double precision, '억원', 'LG CNS 2026Q1 영업이익 942억원'),
                ('lg_cns', '2026Q1', 'operating_margin', '영업이익률', 7.16::numeric, NULL::double precision, '%', 'LG CNS 2026Q1 영업이익률 7.16%'),
                ('posco_dx', '2025Q4', 'revenue_total', '매출', NULL::numeric, 2608.15::double precision, '억원', '포스코DX 공시 잠정치 기준 2025Q4 매출 2,608.15억원'),
                ('posco_dx', '2025Q4', 'operating_profit', '영업이익', NULL::numeric, -12.17::double precision, '억원', '포스코DX 공시 잠정치 기준 2025Q4 영업손실 12.17억원'),
                ('posco_dx', '2025Q4', 'operating_margin', '영업이익률', -0.5::numeric, NULL::double precision, '%', '포스코DX 공시 잠정치 기준 2025Q4 영업이익률 약 -0.5%'),
                ('posco_dx', '2026Q1', 'revenue_total', '매출', NULL::numeric, 2415.1::double precision, '억원', '포스코DX 공시 잠정치 기준 2026Q1 매출 2,415.1억원'),
                ('posco_dx', '2026Q1', 'operating_profit', '영업이익', NULL::numeric, 36.58::double precision, '억원', '포스코DX 공시 잠정치 기준 2026Q1 영업이익 36.58억원'),
                ('posco_dx', '2026Q1', 'operating_margin', '영업이익률', 1.5::numeric, NULL::double precision, '%', '포스코DX 2026Q1 영업이익률 1.5%')
        )
        UPDATE raw_article_financial_metrics fm
           SET value_numeric = corrections.value_numeric,
               value_krwbn = corrections.value_krwbn,
               unit = corrections.unit,
               metric_label = corrections.metric_label,
               metric_scope = 'company_total',
               business_area = 'company_total',
               source_type = 'ir',
               confidence = 1.0,
               evidence_text = corrections.evidence_text,
               payload = COALESCE(fm.payload, '{}'::jsonb)
                   || jsonb_build_object('manual_correction', true, 'correction_version', 'V47'),
               updated_at = NOW()
          FROM corrections
         WHERE fm.peer_id = corrections.peer_id
           AND fm.period = corrections.period
           AND fm.metric_name = corrections.metric_name
           AND fm.metric_scope = 'company_total'
           AND fm.source_type = 'ir'
    $sql$;
END
$$;
