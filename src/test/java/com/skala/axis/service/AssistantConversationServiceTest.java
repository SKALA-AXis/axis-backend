package com.skala.axis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantConversationServiceTest {
    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void deleteConversationSoftDeletesAndIgnoresPurgeFailure() {
        UUID conversationId = UUID.randomUUID();
        AssistantConversationService service = new AssistantConversationService(
                jdbcTemplate,
                new ObjectMapper()
        );

        when(jdbcTemplate.update(
                argThat(sql -> sql != null && sql.contains("UPDATE assistant_conversations")),
                eq(conversationId),
                any()
        )).thenReturn(1);
        when(jdbcTemplate.update(
                argThat(sql -> sql != null && sql.contains("DELETE FROM assistant_messages")),
                eq(conversationId)
        )).thenThrow(new RuntimeException("purge skipped"));

        Map<String, Object> result = service.deleteConversation(
                conversationId.toString(),
                null,
                "device-1"
        );

        assertThat(result)
                .containsEntry("conversation_id", conversationId.toString())
                .containsEntry("status", "deleted")
                .containsEntry("deleted", true)
                .containsEntry("delete_mode", "soft");
    }

    @Test
    void deleteConversationReturnsNotFoundWhenOwnerDoesNotMatch() {
        UUID conversationId = UUID.randomUUID();
        AssistantConversationService service = new AssistantConversationService(
                jdbcTemplate,
                new ObjectMapper()
        );

        when(jdbcTemplate.update(
                argThat(sql -> sql != null && sql.contains("UPDATE assistant_conversations")),
                eq(conversationId),
                any()
        )).thenReturn(0);

        Map<String, Object> result = service.deleteConversation(
                conversationId.toString(),
                null,
                "device-1"
        );

        assertThat(result)
                .containsEntry("conversation_id", conversationId.toString())
                .containsEntry("status", "not_found")
                .containsEntry("deleted", false)
                .containsEntry("error_code", "ASSISTANT_CONVERSATION_NOT_FOUND");
    }
}
