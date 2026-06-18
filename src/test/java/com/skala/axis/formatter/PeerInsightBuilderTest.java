package com.skala.axis.formatter;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class PeerInsightBuilderTest {

    @Test
    void insight_labelAndBody() {
        assertThat(PeerInsightBuilder.insight("강점", "보안 역량"))
                .containsExactly(entry("label", "강점"), entry("body", "보안 역량"));
    }

    @Test
    void traceItem_omitsBlankOptionalFields() {
        assertThat(PeerInsightBuilder.traceItem("L", "B", "  ", null))
                .containsOnlyKeys("label", "body");
        assertThat(PeerInsightBuilder.traceItem("L", "B", "R", "E"))
                .containsKeys("label", "body", "reasoning", "evidence");
    }

    @Test
    void buildRiskInsight_nullsAndValues() {
        String limited = PeerInsightBuilder.buildRiskInsight("LG CNS", null, null, null);
        assertThat(limited).contains("매출 데이터가 제한적").contains("LG CNS는");

        String full = PeerInsightBuilder.buildRiskInsight("삼성SDS", 12345.0, 8.0, 2.0);
        assertThat(full).contains("매출 1.23조원").contains("영업이익률 8.00%").contains("+2.00%p");
    }
}
