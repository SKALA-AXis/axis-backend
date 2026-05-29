package com.skala.axis.dto.admin;

public record AdminCardNewsStatusUpdateRequest(
        String status,
        String reason
) {
}
