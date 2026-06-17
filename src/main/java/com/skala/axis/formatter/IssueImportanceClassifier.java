package com.skala.axis.formatter;

/**
 * 카드 importance 등급(urgent/notable/reference) 분류 (refactoring B-R2).
 *
 * <p>명시 등급이 있으면 그대로 신뢰하고, 없으면 importance_score 임계값으로 버킷팅한다.
 * 기존에 {@code IssueCardController}/{@code FrontendCompatibilityController} 에 동일하게
 * 복제돼 있던 {@code issueImportance(...)} 로직을 단일 출처로 모은 것이다.
 */
public final class IssueImportanceClassifier {

    private static final float URGENT_THRESHOLD = 0.85f;
    private static final float NOTABLE_THRESHOLD = 0.6f;

    private IssueImportanceClassifier() {
    }

    /** 명시 등급 우선, 없으면 점수 임계(≥0.85 urgent, ≥0.6 notable, 그 외 reference). */
    public static String classify(String importance, Float score) {
        if ("urgent".equals(importance) || "notable".equals(importance) || "reference".equals(importance)) {
            return importance;
        }
        if (score != null && score >= URGENT_THRESHOLD) {
            return "urgent";
        }
        if (score != null && score >= NOTABLE_THRESHOLD) {
            return "notable";
        }
        return "reference";
    }
}
