package com.skala.axis;

import com.skala.axis.service.AiClientService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:agentdiagnosticsdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false",
        "axis.auth.enforce=true",
        "axis.agent-test.enabled=true",
        "ai.server.base-url=http://localhost:9999"
})
class AgentDiagnosticsControllerTests {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiClientService aiClientService;

    @Test
    void runAgentEndpointIsPublicForLocalDiagnostics() throws Exception {
        when(aiClientService.postRaw(eq("/insight/generate"), any(), ArgumentMatchers.any(Duration.class)))
                .thenReturn(Mono.just(Map.of("confidence", 0.82, "final_one_liner", "테스트 인사이트")));

        mockMvc.perform(post("/api/dev/agents/insight/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"card_ids\":[\"CN-20260502-001\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.axis_ai_path").value("/insight/generate"))
                .andExpect(jsonPath("$.data.response.confidence").value(0.82));

        verify(aiClientService).postRaw(eq("/insight/generate"), any(), ArgumentMatchers.any(Duration.class));
    }

    @Test
    void resultListReturnsDiagnosticUnavailableInsteadOfFailingWhenTablesAreMissing() throws Exception {
        mockMvc.perform(get("/api/dev/agents/results?type=mixer")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.type").value("mixer"))
                .andExpect(jsonPath("$.data.available").value(false));
    }

    @Test
    void swaggerDocsExposeAgentDiagnosticsPaths() throws Exception {
        mockMvc.perform(get("/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/dev/agents/run']").exists())
                .andExpect(jsonPath("$.paths['/api/dev/agents/results']").exists());
    }

    @Test
    void swaggerDocsExposeLoginRequestBodyFields() throws Exception {
        mockMvc.perform(get("/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.requestBody.content['application/json'].schema['$ref']")
                        .value("#/components/schemas/LoginRequest"))
                .andExpect(jsonPath("$.components.schemas.LoginRequest.properties.email").exists())
                .andExpect(jsonPath("$.components.schemas.LoginRequest.properties.password").exists())
                .andExpect(jsonPath("$.components.schemas.LoginRequest.properties.remember_me").exists());
    }
}
