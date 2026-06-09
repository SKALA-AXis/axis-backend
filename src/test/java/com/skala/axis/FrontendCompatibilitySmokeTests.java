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
                .andExpect(jsonPath("$.data.trends[0].title").exists())
                .andExpect(jsonPath("$.data.keywordSeries").isArray())
                .andExpect(jsonPath("$.data.keywordSearchPoints").isArray())
                .andExpect(jsonPath("$.data.stockRatePoints[0].date").exists())
                .andExpect(jsonPath("$.data.stockSource.basis").value("day_over_day_pct"));

        mockMvc.perform(get("/briefings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dailySnapshot.title").exists())
                .andExpect(jsonPath("$.data.history[0].status").value("delivered"));

        mockMvc.perform(get("/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rules[0].id").exists())
                .andExpect(jsonPath("$.data.conditionOptions[0]").exists());

        mockMvc.perform(get("/peers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.peers[0].id").exists())
                .andExpect(jsonPath("$.data.analyses.samsung_sds.title").exists());

        mockMvc.perform(get("/raw-articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sourceName").exists())
                .andExpect(jsonPath("$.data[0].importanceLevel").exists());

        mockMvc.perform(get("/issues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].peerName").exists())
                .andExpect(jsonPath("$.data[0].summaryLines[0]").exists());
    }

    @Test
    void cardListContainsFieldsRequiredByCurrentCardNewsUi() throws Exception {
        mockMvc.perform(get("/api/cards?limit=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].coverImageUrl").exists())
                .andExpect(jsonPath("$.data.items[0].summary[0]").exists())
                .andExpect(jsonPath("$.data.items[0].insights[0]").exists())
                .andExpect(jsonPath("$.data.items[0].actionItems[0]").exists())
                .andExpect(jsonPath("$.data.items[0].sourceUrl").exists())
                .andExpect(jsonPath("$.data.items[0].displayEntries[0].id").exists());
    }

    @Test
    void canonicalFrontendApiEndpointsReturnCurrentUiShapes() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trends[0].peer").exists())
                .andExpect(jsonPath("$.data.stockRatePoints[0].date").exists());

        mockMvc.perform(get("/api/dashboard/today-insight"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signals[0].label").value("주요 신호"))
                .andExpect(jsonPath("$.data.response_direction[0].action").exists())
                .andExpect(jsonPath("$.data.sources[0].title").exists());

        mockMvc.perform(post("/api/dashboard/today-insight/warmup"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("accepted"))
                .andExpect(jsonPath("$.data.cache_only").value(true))
                .andExpect(jsonPath("$.data.refresh_policy").value("cache_first"))
                .andExpect(jsonPath("$.data.update_policy").value("daily_0810_kst"));

        mockMvc.perform(post("/api/dashboard/today-insight/cron-generate"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.data.status").value("failed"));

        mockMvc.perform(get("/api/briefings/summary?briefing_type=daily"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.briefingLead").exists())
                .andExpect(jsonPath("$.data.signalCards[0].label").exists());

        mockMvc.perform(get("/api/mixer/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaults.peers[0]").exists())
                .andExpect(jsonPath("$.data.connectionKeywords[0]").exists());

        mockMvc.perform(get("/api/raw-articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].processingStatus").value("EMBEDDED"));

        mockMvc.perform(post("/api/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"오늘 인사이트 요약해줘\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message.content").exists());
    }
}
