package com.skala.axis.query;

public final class MixerResultQueries {
    public static final String UPSERT_MIXER_RESULT_SQL = """
            INSERT INTO mixer_results (
                source_analysis_id,
                title,
                input_card_ids,
                input_peer_ids,
                input_keywords,
                ratios,
                generated_implication,
                insight_brief,
                radar_axes,
                connections,
                sk_ax_implication,
                final_one_liner,
                confidence,
                payload
            )
            VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb),
                    CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT (source_analysis_id) WHERE source_analysis_id IS NOT NULL
            DO UPDATE SET
                title = EXCLUDED.title,
                input_card_ids = EXCLUDED.input_card_ids,
                input_peer_ids = EXCLUDED.input_peer_ids,
                input_keywords = EXCLUDED.input_keywords,
                ratios = EXCLUDED.ratios,
                generated_implication = EXCLUDED.generated_implication,
                insight_brief = EXCLUDED.insight_brief,
                radar_axes = EXCLUDED.radar_axes,
                connections = EXCLUDED.connections,
                sk_ax_implication = EXCLUDED.sk_ax_implication,
                final_one_liner = EXCLUDED.final_one_liner,
                confidence = EXCLUDED.confidence,
                payload = EXCLUDED.payload,
                updated_at = NOW()
            """;

    public static final String RECENT_MIXER_RESULTS_SQL = """
            SELECT
                id::text AS id,
                source_analysis_id,
                title,
                final_one_liner,
                sk_ax_implication,
                confidence::double precision AS confidence,
                array_to_json(input_peer_ids)::text AS peer_ids_json,
                array_to_json(input_card_ids)::text AS card_ids_json,
                array_to_json(input_keywords)::text AS keywords_json,
                payload::text AS payload_json,
                created_at::text AS created_at,
                updated_at::text AS updated_at
            FROM mixer_results
            ORDER BY created_at DESC
            LIMIT ?
            """;

    public static final String SHARE_PAYLOAD_SQL = """
            SELECT
                id::text AS id,
                source_analysis_id,
                title,
                final_one_liner,
                sk_ax_implication,
                confidence::double precision AS confidence,
                array_to_json(input_card_ids)::text AS card_ids_json,
                array_to_json(input_peer_ids)::text AS peer_ids_json,
                array_to_json(input_keywords)::text AS keywords_json,
                payload::text AS payload_json,
                created_at::text AS created_at,
                updated_at::text AS updated_at
            FROM mixer_results
            WHERE source_analysis_id = ? OR id::text = ?
            ORDER BY updated_at DESC
            LIMIT 1
            """;

    private MixerResultQueries() {
    }
}
