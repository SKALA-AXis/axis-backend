package com.skala.axis.formatter;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.skala.axis.formatter.PeerOverviewFormat.formatKrwBnText;
import static com.skala.axis.formatter.PeerOverviewFormat.formatPercentPointText;
import static com.skala.axis.formatter.PeerOverviewFormat.formatPercentText;

/**
 * Peer 개요 insight/trace 표시 아이템 + 리스크 문구 빌더 (refactoring B-R2).
 *
 * <p>{@code PeerOverviewTableService} 에 있던 순수 display 빌더들을 그대로 옮긴 것
 * (DB/상태 무관, 정적 import 로 호출).
 */
public final class PeerInsightBuilder {

    private PeerInsightBuilder() {
    }

    /** {label, body} 형태의 insight 아이템. */
    public static Map<String, String> insight(String label, String body) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("body", body);
        return item;
    }

    /** {label, body, reasoning?, evidence?} 형태의 분석 trace 아이템(빈 값은 생략). */
    public static Map<String, Object> traceItem(String label, String body, String reasoning, String evidence) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("body", body);
        if (reasoning != null && !reasoning.isBlank()) {
            item.put("reasoning", reasoning);
        }
        if (evidence != null && !evidence.isBlank()) {
            item.put("evidence", evidence);
        }
        return item;
    }

    /** 매출/영업이익률/변화 기반 리스크 함께보기 문구. null 값은 "제한적" 표현으로. */
    public static String buildRiskInsight(String peerLabel, Double revenue, Double margin, Double marginDelta) {
        String revenueText = revenue == null ? "매출 데이터가 제한적" : "매출 " + formatKrwBnText(revenue);
        String marginText = margin == null ? "영업이익률 데이터가 제한적" : "영업이익률 " + formatPercentText(margin);
        String deltaText = marginDelta == null ? "전분기 대비 수익성 변화는 확인이 제한적입니다" : "전분기 대비 영업이익률 변화는 " + formatPercentPointText(marginDelta) + "입니다";
        return peerLabel + "는 " + revenueText + ", " + marginText + " 기준으로 함께 봐야 합니다. " + deltaText + ". 따라서 최근 사업·기술 신호가 강하더라도 실적 범위와 수익성 변동은 별도 리스크로 남습니다.";
    }
}
