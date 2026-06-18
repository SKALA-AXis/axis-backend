package com.skala.axis.formatter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SwotTextTest {

    @Test
    void canonicalSwotLabel_aliasesAndUnknown() {
        assertThat(SwotText.canonicalSwotLabel("강점")).isEqualTo("Strength");
        assertThat(SwotText.canonicalSwotLabel("THREATS")).isEqualTo("Threat");
        assertThat(SwotText.canonicalSwotLabel("opportunity")).isEqualTo("Opportunity");
        assertThat(SwotText.canonicalSwotLabel("뭔가")).isNull();
        assertThat(SwotText.canonicalSwotLabel(null)).isNull();
    }

    @Test
    void defaultSwotFactorType_() {
        assertThat(SwotText.defaultSwotFactorType("Strength")).isEqualTo("internal_controllable");
        assertThat(SwotText.defaultSwotFactorType("Threat")).isEqualTo("external_uncontrollable");
        assertThat(SwotText.defaultSwotFactorType("X")).isEqualTo("");
        assertThat(SwotText.defaultSwotFactorType(null)).isEqualTo("");
    }

    @Test
    void isInsufficientSwotText_() {
        assertThat(SwotText.isInsufficientSwotText("판단 근거 부족합니다")).isTrue();
        assertThat(SwotText.isInsufficientSwotText("정상 텍스트")).isFalse();
        assertThat(SwotText.isInsufficientSwotText(null)).isFalse();
    }

    @Test
    void normalizeDisplayText_stripsTagsBracketsBullets() {
        assertThat(SwotText.normalizeDisplayText(null)).isEqualTo("");
        assertThat(SwotText.normalizeDisplayText("<b>hi</b>  [x]")).isEqualTo("hi x");
        assertThat(SwotText.normalizeDisplayText("- 항목")).isEqualTo("항목");
    }

    @Test
    void sanitizeObjectivePeerFlowText_replacesSubjectivePhrases() {
        assertThat(SwotText.sanitizeObjectivePeerFlowText(null)).isEqualTo("");
        assertThat(SwotText.sanitizeObjectivePeerFlowText("Peer사의 전략")).isEqualTo("해당 기업의 전략");
        assertThat(SwotText.sanitizeObjectivePeerFlowText("SK AX 대비 우위")).isEqualTo("우위");
        assertThat(SwotText.sanitizeObjectivePeerFlowText("자사 역량")).isEqualTo("해당 기업 역량");
    }

    @Test
    void normalizeSwotDisplayText_dedupesSentences() {
        assertThat(SwotText.normalizeSwotDisplayText(null)).isEqualTo("");
        assertThat(SwotText.normalizeSwotDisplayText("같다. 같다.")).isEqualTo("같다.");
    }
}
