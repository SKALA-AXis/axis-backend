package com.skala.axis.dto;

import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;

@Getter
@Builder
public class IssueCardResponse {
    private String id;
    private String peerId;
    private Long clusterId;
    private String title;
    private String eventType;
    private String importance;
    private Float importanceScore;
    private LocalDateTime createdAt;
}
