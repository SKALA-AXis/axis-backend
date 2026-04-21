package com.skala.axis.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SearchRequest {
    @NotBlank
    private String query;
    private String peerId;
    private String eventType;
    private Integer topK = 10;
}
