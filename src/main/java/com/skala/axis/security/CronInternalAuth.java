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
