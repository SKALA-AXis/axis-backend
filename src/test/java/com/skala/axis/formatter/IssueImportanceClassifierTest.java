package com.skala.axis.formatter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IssueImportanceClassifierTest {

    @Test
    void explicitGradeIsKept() {
        assertThat(IssueImportanceClassifier.classify("urgent", 0.1f)).isEqualTo("urgent");
        assertThat(IssueImportanceClassifier.classify("notable", null)).isEqualTo("notable");
        assertThat(IssueImportanceClassifier.classify("reference", 0.99f)).isEqualTo("reference");
    }

    @Test
    void scoreThresholdsBucketWhenGradeMissing() {
        assertThat(IssueImportanceClassifier.classify(null, 0.85f)).isEqualTo("urgent");
        assertThat(IssueImportanceClassifier.classify(null, 0.84f)).isEqualTo("notable");
        assertThat(IssueImportanceClassifier.classify(null, 0.6f)).isEqualTo("notable");
        assertThat(IssueImportanceClassifier.classify(null, 0.59f)).isEqualTo("reference");
    }

    @Test
    void nullScoreAndUnknownGradeFallsToReference() {
        assertThat(IssueImportanceClassifier.classify(null, null)).isEqualTo("reference");
        assertThat(IssueImportanceClassifier.classify("weird", null)).isEqualTo("reference");
    }
}
