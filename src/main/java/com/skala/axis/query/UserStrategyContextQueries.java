package com.skala.axis.query;

public final class UserStrategyContextQueries {
    public static final String LIST_BY_USER_SQL = """
            SELECT id,
                   raw_text,
                   overlay_json,
                   source_type,
                   file_name,
                   file_size,
                   metadata,
                   created_at,
                   updated_at
              FROM user_strategy_contexts
             WHERE user_id = ?
             ORDER BY updated_at DESC
            """;

    public static final String INSERT_CONTEXT_SQL = """
            INSERT INTO user_strategy_contexts (
                user_id, raw_text, overlay_json, source_type,
                file_name, file_size, metadata
            )
            VALUES (?, ?, CAST(? AS jsonb), ?, ?, ?, CAST(? AS jsonb))
            RETURNING id,
                      raw_text,
                      overlay_json,
                      source_type,
                      file_name,
                      file_size,
                      metadata,
                      created_at,
                      updated_at
            """;

    public static final String UPDATE_CONTEXT_SQL = """
            UPDATE user_strategy_contexts
               SET raw_text = ?,
                   overlay_json = CAST(? AS jsonb),
                   source_type = ?,
                   file_name = ?,
                   file_size = ?,
                   metadata = CAST(? AS jsonb)
             WHERE id = ?
               AND user_id = ?
            RETURNING id,
                      raw_text,
                      overlay_json,
                      source_type,
                      file_name,
                      file_size,
                      metadata,
                      created_at,
                      updated_at
            """;

    public static final String DELETE_CONTEXT_SQL = """
            DELETE FROM user_strategy_contexts
             WHERE id = ?
               AND user_id = ?
            """;

    public static final String COUNT_CONTEXTS_BY_USER_SQL = """
            SELECT COUNT(*)
              FROM user_strategy_contexts
             WHERE user_id = ?
            """;

    public static final String DEACTIVATE_STRATEGY_PROJECTIONS_SQL = """
            UPDATE card_news_strategy_context_projections
               SET is_applied = FALSE,
                   reverted_at = CAST(? AS timestamptz)
             WHERE user_id = ?
               AND is_applied = TRUE
            """;

    private UserStrategyContextQueries() {
    }
}
