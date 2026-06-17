package com.skala.axis.formatter;

/**
 * Peer 개요 표/포지셔닝 표시용 순수 포맷·텍스트 헬퍼 (refactoring B-R2).
 *
 * <p>{@code PeerOverviewTableService}(2,800+줄)에 흩어져 있던 인스턴스 private 헬퍼 중
 * DB/상태에 의존하지 않는 순수 함수들을 그대로 모은 것이다(동작 변경 없음, 정적 import 로 호출).
 */
public final class PeerOverviewFormat {

    private PeerOverviewFormat() {
    }

    /** 억원 단위 금액 → 한글 표시(1조 이상은 "조원"). null 은 "-". */
    public static String formatKrwBnText(Double value) {
        if (value == null) {
            return "-";
        }
        if (Math.abs(value) >= 10_000) {
            return String.format("%.2f조원", value / 10_000.0);
        }
        return String.format("%.0f억원", value);
    }

    /** 퍼센트 표시. null 은 "-". */
    public static String formatPercentText(Double value) {
        if (value == null) {
            return "-";
        }
        return String.format("%.2f%%", value);
    }

    /** 퍼센트포인트 표시(양수면 + 부호). null 은 "-". */
    public static String formatPercentPointText(Double value) {
        if (value == null) {
            return "-";
        }
        String sign = value > 0 ? "+" : "";
        return sign + String.format("%.2f%%p", value);
    }

    /** 비어있지 않은 문자열이면 그대로, 아니면 "-". */
    public static String nullToDash(Object value) {
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        return "-";
    }

    /** 한글 마지막 글자 받침 유무로 주격/주제 보조사("은/는") 선택. */
    public static String topicParticle(String value) {
        if (value == null || value.isBlank()) {
            return "은";
        }
        char lastChar = value.charAt(value.length() - 1);
        if (lastChar >= 0xAC00 && lastChar <= 0xD7A3) {
            return ((lastChar - 0xAC00) % 28) == 0 ? "는" : "은";
        }
        return "는";
    }

    /** 첫 번째 비어있지 않은 값. 없으면 "". */
    public static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /** null → "". */
    public static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** null/공백 → null, 그 외 그대로. */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
