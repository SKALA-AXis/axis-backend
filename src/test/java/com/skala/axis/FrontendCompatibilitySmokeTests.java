package com.skala.axis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:frontendcompatdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false",
        "axis.auth.enforce=false",
        "axis.fixtures.enabled=false",
        "ai.server.base-url=http://localhost:9999"
})
class FrontendCompatibilitySmokeTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void frontendCompatibilityEndpointsReturnCurrentUiShapes() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.trends").isArray())
                .andExpect(jsonPath("$.data.keywordSeries").isArray())
                .andExpect(jsonPath("$.data.keywordSearchPoints").isArray())
                .andExpect(jsonPath("$.data.stockRatePoints").isArray());

        mockMvc.perform(get("/briefings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dailySnapshot.title").exists())
                .andExpect(jsonPath("$.data.history").isArray());

        mockMvc.perform(get("/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rules").isArray())
                .andExpect(jsonPath("$.data.conditionOptions").isArray());

        mockMvc.perform(get("/peers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.peers").isArray())
                .andExpect(jsonPath("$.data.analyses").isMap());

        mockMvc.perform(get("/raw-articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get("/issues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void cardListContainsFieldsRequiredByCurrentCardNewsUi() throws Exception {
        mockMvc.perform(get("/api/cards?limit=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.limit").value(2))
                .andExpect(jsonPath("$.data.offset").value(0));
    }

    @Test
    void canonicalFrontendApiEndpointsReturnCurrentUiShapes() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trends").isArray())
                .andExpect(jsonPath("$.data.stockRatePoints").isArray());

        mockMvc.perform(get("/api/dashboard/today-insight"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("TODAY_INSIGHT_AI_CONNECTION_FAILED"))
                .andExpect(jsonPath("$.error.message").value("호출에 실패했다"));

        mockMvc.perform(post("/api/dashboard/today-insight/warmup"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("accepted"))
                .andExpect(jsonPath("$.data.cache_only").value(true))
                .andExpect(jsonPath("$.data.refresh_policy").value("cache_first"))
                .andExpect(jsonPath("$.data.update_policy").value("daily_0810_kst"));

        mockMvc.perform(post("/api/dashboard/today-insight/cron-generate"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("TODAY_INSIGHT_AI_CONNECTION_FAILED"))
                .andExpect(jsonPath("$.error.message").value("호출에 실패했다"));

        mockMvc.perform(get("/api/briefings/summary?briefing_type=daily"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dailySnapshot.title").exists())
                .andExpect(jsonPath("$.data.history").isArray());

        mockMvc.perform(get("/api/mixer/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysis_modes[0]").value("quick"))
                .andExpect(jsonPath("$.data.analysis_modes[1]").value("deep"));

        mockMvc.perform(get("/api/raw-articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(post("/api/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"오늘 인사이트 요약해줘\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message.content").exists())
                .andExpect(jsonPath("$.data.intent").value("assistant_error"))
                .andExpect(jsonPath("$.data.error_code").value("CHAT_AI_CONNECTION_FAILED"))
                .andExpect(jsonPath("$.data.message.content").value(org.hamcrest.Matchers.containsString(
                        "호출에 실패했다"
                )))
                .andExpect(jsonPath("$.data.provenance.is_fixture").value(false))
                .andExpect(jsonPath("$.data.handoff").doesNotExist())
                .andExpect(jsonPath("$.data.message.content").value(org.hamcrest.Matchers.not(
                        "오늘 인사이트, 근거 카드뉴스, SK AX 대응 방향을 묶어 보고서 초안을 만들었습니다."
                )));
    }
}
