package com.skala.axis.formatter;

import java.util.ArrayList;
import java.util.List;

/**
 * Peer SWOT/비교 표시 텍스트 정규화 순수 헬퍼 (refactoring B-R2).
 *
 * <p>{@code PeerOverviewTableService} 에 흩어져 있던 SWOT 라벨/문구 정규화·치환·정제 헬퍼 중
 * DB/상태 무관 순수 함수들을 그대로 모았다(동작 변경 없음, 정적 import 로 호출).
 */
public final class SwotText {

    private SwotText() {
    }

    /** SWOT 라벨을 표준형(Strength/Weakness/Opportunity/Threat)으로, 미상이면 null. */
    public static String canonicalSwotLabel(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return switch (normalized) {
            case "strength", "strengths", "강점" -> "Strength";
            case "weakness", "weaknesses", "약점" -> "Weakness";
            case "opportunity", "opportunities", "기회" -> "Opportunity";
            case "threat", "threats", "위협" -> "Threat";
            default -> null;
        };
    }

    /** 표시용 텍스트 정제(태그/괄호/불릿 제거 + 공백 정리 + 260자 문장경계 컷). */
    public static String normalizeDisplayText(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value
                .replaceAll("(?is)<[^>]+>", " ")
                .replaceAll("[\\[\\]\\{\\}\"]", " ")
                .replaceAll("(?m)^\\s*[-*•]\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.length() > 260) {
            int sentenceEnd = Math.max(cleaned.lastIndexOf(". ", 220), cleaned.lastIndexOf("다. ", 220));
            int cutIndex = sentenceEnd > 80 ? sentenceEnd + 1 : 260;
            cleaned = cleaned.substring(0, Math.min(cutIndex, cleaned.length())).trim();
        }
        return cleaned;
    }

    /** 근거 부족 류 문구인지. */
    public static boolean isInsufficientSwotText(String value) {
        return value != null && (
                value.contains("현재 입력 근거만으로 해당 축을 정의하기 어렵다")
                        || value.contains("판단 근거 부족")
        );
    }

    /** SWOT 라벨 → factor type(내부/외부). */
    public static String defaultSwotFactorType(String label) {
        if (label == null) {
            return "";
        }
        return switch (label) {
            case "Strength", "Weakness" -> "internal_controllable";
            case "Opportunity", "Threat" -> "external_uncontrollable";
            default -> "";
        };
    }

    /** "Peer사/자사/SK AX 대비" 등 주관·비교 표현을 객관 표현으로 치환. */
    public static String sanitizeObjectivePeerFlowText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replace("최근 공개 원문에서는", "")
                .replace("최근 공개 원문에서", "최근 신호에서")
                .replace("최근 공개 원문 신호", "최근 신호")
                .replace("이 Peer사는", "해당 기업은")
                .replace("이 Peer사가", "해당 기업이")
                .replace("이 Peer사를", "해당 기업을")
                .replace("이 Peer사의", "해당 기업의")
                .replace("이 peer사는", "해당 기업은")
                .replace("이 peer사가", "해당 기업이")
                .replace("이 peer사를", "해당 기업을")
                .replace("이 peer사의", "해당 기업의")
                .replace("Peer사는", "해당 기업은")
                .replace("Peer사가", "해당 기업이")
                .replace("Peer사를", "해당 기업을")
                .replace("Peer사의", "해당 기업의")
                .replace("Peer사에", "해당 기업에")
                .replace("Peer사", "대상 기업")
                .replace("peer사는", "해당 기업은")
                .replace("peer사가", "해당 기업이")
                .replace("peer사를", "해당 기업을")
                .replace("peer사의", "해당 기업의")
                .replace("peer사에", "해당 기업에")
                .replace("peer사", "대상 기업")
                .replace("현재 입력 근거만으로 해당 축을 정의하기 어렵다", "판단 근거가 부족합니다.")
                .replace("SK AX와 비교했을 때", "")
                .replace("SK AX와 비교해", "")
                .replace("SK AX와 비교하면", "")
                .replace("SK AX 대비", "")
                .replace("SK AX 기준", "")
                .replace("SK AX 관점에서", "")
                .replace("SK AX는", "해당 기업은")
                .replace("SK AX의", "해당 기업의")
                .replace("자사", "해당 기업")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** SWOT 표시 텍스트 정규화(객관화 + 정제 + 문장 중복 제거). */
    public static String normalizeSwotDisplayText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = sanitizeObjectivePeerFlowText(value)
                .replaceAll("[\\[\\]\\{\\}\"]", " ")
                .replaceAll("(?m)^\\s*[-*•]\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isBlank()) {
            return "";
        }

        List<String> uniqueSentences = new ArrayList<>();
        for (String sentence : cleaned.split("(?<=[.!?。])\\s+")) {
            String normalized = sentence.replaceAll("\\s+", " ").trim();
            if (!normalized.isBlank() && !uniqueSentences.contains(normalized)) {
                uniqueSentences.add(normalized);
            }
        }
        return String.join(" ", uniqueSentences);
    }
}
