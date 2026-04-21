package com.skala.axis.dto;

import lombok.Builder;
import lombok.Getter;
import java.util.List;

@Getter
@Builder
public class SearchResponse {
    private String answer;
    private List<SourceItem> sources;
    private Boolean scPassed;
    private Float scScore;

    @Getter
    @Builder
    public static class SourceItem {
        private Integer index;
        private String title;
        private String sourceName;
        private String url;
        private Float credibilityScore;
    }
}
