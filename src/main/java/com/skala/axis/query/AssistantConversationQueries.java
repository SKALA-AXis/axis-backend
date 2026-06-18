/*
 * 작성일: 2026-06-11
 * 작성자: 안가은
 * 변경이력:
 *   2026-06-11 안가은 — backend SQL 쿼리 분리 작업으로 대화 조회 쿼리 작성
 */
package com.skala.axis.query;

public final class AssistantConversationQueries {
    public static final String LIST_BY_USER = """
            SELECT id::text AS conversation_id,
                   title,
                   summary,
                   status,
                   message_count,
                   last_message_at,
                   created_at,
                   updated_at
              FROM assistant_conversations
             WHERE user_id = ?
               AND status <> 'deleted'
             ORDER BY last_message_at DESC NULLS LAST, created_at DESC
             LIMIT ?
            """;

    public static final String LIST_BY_DEVICE_HASH = """
            SELECT id::text AS conversation_id,
                   title,
                   summary,
                   status,
                   message_count,
                   last_message_at,
                   created_at,
                   updated_at
              FROM assistant_conversations
             WHERE device_id_hash = ?
               AND status <> 'deleted'
             ORDER BY last_message_at DESC NULLS LAST, created_at DESC
             LIMIT ?
            """;

    public static final String MESSAGE_DETAIL_BY_CONVERSATION = """
            SELECT id::text AS message_id,
                   turn_idx,
                   role,
                   content,
                   intent,
                   scope,
                   answer_payload,
                   sources,
                   retrieval_trace,
                   safety,
                   confidence,
                   langfuse_trace_id,
                   created_at
              FROM assistant_messages
             WHERE conversation_id = ?
             ORDER BY turn_idx ASC
            """;

    public static final String END_CONVERSATION = """
            UPDATE assistant_conversations
               SET status = 'ended',
                   updated_at = NOW()
             WHERE id = ?
            """;

    public static final String SOFT_DELETE_BY_USER_OR_DEVICE = """
            UPDATE assistant_conversations
               SET status = 'deleted',
                   updated_at = NOW()
             WHERE id = ?
               AND status <> 'deleted'
               AND (user_id = ? OR device_id_hash = ?)
            """;

    public static final String SOFT_DELETE_BY_USER = """
            UPDATE assistant_conversations
               SET status = 'deleted',
                   updated_at = NOW()
             WHERE id = ?
               AND status <> 'deleted'
               AND user_id = ?
            """;

    public static final String SOFT_DELETE_BY_DEVICE = """
            UPDATE assistant_conversations
               SET status = 'deleted',
                   updated_at = NOW()
             WHERE id = ?
               AND status <> 'deleted'
               AND device_id_hash = ?
            """;

    public static final String DELETE_MESSAGES_BY_CONVERSATION = """
            DELETE FROM assistant_messages
             WHERE conversation_id = ?
            """;

    public static final String DELETE_CONVERSATION_IF_DELETED = """
            DELETE FROM assistant_conversations
             WHERE id = ?
               AND status = 'deleted'
            """;

    public static final String UPSERT_CONVERSATION = """
            INSERT INTO assistant_conversations (id, user_id, device_id_hash, title, metadata)
            VALUES (?, ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT (id) DO UPDATE
                SET updated_at = NOW(),
                    status = CASE
                        WHEN assistant_conversations.status = 'ended' THEN 'active'
                        ELSE assistant_conversations.status
                    END
            """;

    public static final String INSERT_USER_MESSAGE = """
            INSERT INTO assistant_messages (
                conversation_id, turn_idx, role, content, answer_payload, retrieval_trace, safety
            )
            SELECT ?, COALESCE(MAX(turn_idx), 0) + 1, 'user', ?, CAST(? AS jsonb),
                   '{}'::jsonb, '{}'::jsonb
              FROM assistant_messages
             WHERE conversation_id = ?
            """;

    public static final String INSERT_ASSISTANT_MESSAGE = """
            INSERT INTO assistant_messages (
                conversation_id, turn_idx, role, content, intent, scope, answer_payload,
                sources, retrieval_trace, handoff, safety, confidence, langfuse_trace_id
            )
            SELECT ?, COALESCE(MAX(turn_idx), 0) + 1, 'assistant', ?, ?, ?, CAST(? AS jsonb),
                   CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?, ?
              FROM assistant_messages
             WHERE conversation_id = ?
            """;

    public static final String RECENT_HISTORY = """
            SELECT role, content
              FROM (
                    SELECT role, content, turn_idx
                      FROM assistant_messages
                     WHERE conversation_id = ?
                     ORDER BY turn_idx DESC
                     LIMIT ?
                   ) recent
             ORDER BY turn_idx ASC
            """;

    public static final String COUNT_ACCESS_BY_USER = """
            SELECT COUNT(*)
              FROM assistant_conversations
             WHERE id = ?
               AND user_id = ?
               AND status <> 'deleted'
            """;

    public static final String COUNT_ACCESS_BY_DEVICE = """
            SELECT COUNT(*)
              FROM assistant_conversations
             WHERE id = ?
               AND device_id_hash = ?
               AND status <> 'deleted'
            """;

    public static final String CONVERSATION_ACCESS_OWNER = """
            SELECT user_id::text AS user_id,
                   device_id_hash,
                   status
              FROM assistant_conversations
             WHERE id = ?
             LIMIT 1
            """;

    private AssistantConversationQueries() {
    }
}
