package com.skala.axis.formatter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeerOverviewFormatTest {

    @Test
    void formatKrwBnText_unitsAndNull() {
        assertThat(PeerOverviewFormat.formatKrwBnText(null)).isEqualTo("-");
        assertThat(PeerOverviewFormat.formatKrwBnText(5000.0)).isEqualTo("5000억원");
        assertThat(PeerOverviewFormat.formatKrwBnText(10000.0)).isEqualTo("1.00조원");
        assertThat(PeerOverviewFormat.formatKrwBnText(12345.0)).isEqualTo("1.23조원");
        assertThat(PeerOverviewFormat.formatKrwBnText(-12345.0)).isEqualTo("-1.23조원");
    }

    @Test
    void formatPercentText_() {
        assertThat(PeerOverviewFormat.formatPercentText(null)).isEqualTo("-");
        assertThat(PeerOverviewFormat.formatPercentText(12.5)).isEqualTo("12.50%");
        assertThat(PeerOverviewFormat.formatPercentText(0.0)).isEqualTo("0.00%");
    }

    @Test
    void formatPercentPointText_sign() {
        assertThat(PeerOverviewFormat.formatPercentPointText(null)).isEqualTo("-");
        assertThat(PeerOverviewFormat.formatPercentPointText(2.5)).isEqualTo("+2.50%p");
        assertThat(PeerOverviewFormat.formatPercentPointText(-1.0)).isEqualTo("-1.00%p");
        assertThat(PeerOverviewFormat.formatPercentPointText(0.0)).isEqualTo("0.00%p");
    }

    @Test
    void nullToDash_() {
        assertThat(PeerOverviewFormat.nullToDash(null)).isEqualTo("-");
        assertThat(PeerOverviewFormat.nullToDash("")).isEqualTo("-");
        assertThat(PeerOverviewFormat.nullToDash("   ")).isEqualTo("-");
        assertThat(PeerOverviewFormat.nullToDash(123)).isEqualTo("-");
        assertThat(PeerOverviewFormat.nullToDash("hi")).isEqualTo("hi");
    }

    @Test
    void topicParticle_koreanFinalConsonant() {
        assertThat(PeerOverviewFormat.topicParticle(null)).isEqualTo("은");
        assertThat(PeerOverviewFormat.topicParticle("")).isEqualTo("은");
        assertThat(PeerOverviewFormat.topicParticle("삼성")).isEqualTo("은"); // 성: 받침 있음
        assertThat(PeerOverviewFormat.topicParticle("포스코")).isEqualTo("는"); // 코: 받침 없음
        assertThat(PeerOverviewFormat.topicParticle("AX")).isEqualTo("는"); // 한글 아님 → 는
    }

    @Test
    void firstNonBlank_() {
        assertThat(PeerOverviewFormat.firstNonBlank("", null, "x", "y")).isEqualTo("x");
        assertThat(PeerOverviewFormat.firstNonBlank(null, "", "   ")).isEqualTo("");
    }

    @Test
    void nullToEmpty_() {
        assertThat(PeerOverviewFormat.nullToEmpty(null)).isEqualTo("");
        assertThat(PeerOverviewFormat.nullToEmpty("a")).isEqualTo("a");
    }

    @Test
    void blankToNull_() {
        assertThat(PeerOverviewFormat.blankToNull(null)).isNull();
        assertThat(PeerOverviewFormat.blankToNull("")).isNull();
        assertThat(PeerOverviewFormat.blankToNull("   ")).isNull();
        assertThat(PeerOverviewFormat.blankToNull("a")).isEqualTo("a");
    }
}
