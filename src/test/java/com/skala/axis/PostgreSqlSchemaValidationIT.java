/*
 * 작성일: 2026-06-08
 * 작성자: 최종민
 * 변경이력:
 *   2026-06-08 최종민 — gitleaks CI·풀 SHA 이미지 태그와 함께 Hibernate validate 테스트 추가, Postgres 스키마 IT는 환경변수 미설정 시 skip 처리
 */
package com.skala.axis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * CI 전용 — Flyway 마이그레이션 적용 후 Hibernate ddl-auto=validate 가 통과하는지 검증.
 * 운영(skala ConfigMap) 과 동일한 validate posture 를 Postgres 서비스에서 확인한다.
 *
 * <p>Postgres 서비스가 없는 기본 {@code ./gradlew test} 에서는 skip (AXIS_CI_POSTGRES_VALIDATE 미설정).
 */
@EnabledIfEnvironmentVariable(named = "AXIS_CI_POSTGRES_VALIDATE", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:postgresql://localhost:5432/axis_test",
            "spring.datasource.username=axuser",
            "spring.datasource.password=axpass",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.flyway.enabled=true",
            "ai.server.base-url=http://localhost:9999",
            "axis.scheduler.enabled=false",
            "AXIS_AUTH_JWT_SECRET=ci-schema-validate-jwt-secret-32bytes-min"
        })
class PostgreSqlSchemaValidationIT {
    @Test
    void contextLoadsWithHibernateValidate() {}
}
