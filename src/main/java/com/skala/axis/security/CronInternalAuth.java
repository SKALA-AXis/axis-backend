/*
 * 작성일: 2026-06-09
 * 작성자: 최종민
 * 변경이력:
 *   2026-06-09 최종민 — K8s CronJob의 today-insight cron 생성 지원을 위한 크론 내부 인증 추가
 */
package com.skala.axis.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CronInternalAuth {
    @Value("${axis.scheduler.cron-internal-token:}")
    private String cronInternalToken;

    @Value("${axis.scheduler.cron-auth-required:false}")
    private boolean cronAuthRequired;

    public boolean isAuthorized(String authorization) {
        if (cronInternalToken == null || cronInternalToken.isBlank()) {
            // local: 토큰 없이 CronJob 테스트 허용. prod(cron-auth-required=true): fail-closed.
            return !cronAuthRequired;
        }
        return ("Bearer " + cronInternalToken).equals(authorization);
    }
}
