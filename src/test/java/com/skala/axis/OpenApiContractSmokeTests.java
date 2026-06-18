/*
 * 작성일: 2026-05-06
 * 작성자: 박진
 * 변경이력:
 *   2026-05-06 박진 — Backend 초안에서 시작해 프론트 기반 대량 수정·로그인/회원가입·비밀번호 찾기·투데이 인사이트·챗봇 고도화까지 반영
 *   2026-06-09 최종민 — K8s CronJob용 today-insight cron-generate 추가 반영
 */
package com.skala.axis;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import java.util.List;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:contractdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false",
        "axis.auth.enforce=false",
        "axis.fixtures.enabled=false",
        "ai.server.base-url=http://localhost:9999"
})
class OpenApiContractSmokeTests {
    private static final String JSON = "{}";

    @Autowired
    private MockMvc mockMvc;

    @TestFactory
    Stream<DynamicTest> openApiV3EndpointsReturnCommonSuccessWrapper() {
        return endpoints().stream().map(endpoint -> DynamicTest.dynamicTest(
                endpoint.chunk + " " + endpoint.method + " " + endpoint.path,
                () -> perform(endpoint)
                        .andExpect(endpoint.status)
                        .andExpect(jsonPath("$.success").value(endpoint.success))
                        .andExpect(endpoint.success
                                ? jsonPath("$.data").exists()
                                : jsonPath("$.error.code").exists())
                        .andExpect(jsonPath("$.timestamp").exists())
        ));
    }

    private org.springframework.test.web.servlet.ResultActions perform(Endpoint endpoint) throws Exception {
        return switch (endpoint.method) {
            case "GET" -> mockMvc.perform(get(endpoint.path).accept(MediaType.APPLICATION_JSON));
            case "POST" -> mockMvc.perform(post(endpoint.path).contentType(MediaType.APPLICATION_JSON).content(endpoint.body).accept(MediaType.APPLICATION_JSON));
            case "PUT" -> mockMvc.perform(put(endpoint.path).contentType(MediaType.APPLICATION_JSON).content(endpoint.body).accept(MediaType.APPLICATION_JSON));
            case "DELETE" -> mockMvc.perform(delete(endpoint.path).accept(MediaType.APPLICATION_JSON));
            default -> throw new IllegalArgumentException("Unsupported method: " + endpoint.method);
        };
    }

    private List<Endpoint> endpoints() {
        return List.of(
                e("001-400", "GET", "/health", status().isOk()),
                e("001-400", "POST", "/api/auth/signup", status().isCreated(), "{\"email\":\"axis.user@sk.com\",\"password\":\"password123\",\"name\":\"AXIS 사용자\",\"department\":\"사업전략팀\",\"role\":\"strategist\"}"),
                e("001-400", "POST", "/api/auth/verify-email", status().isOk(), "{\"token\":\"verify-token\"}"),
                e("001-400", "POST", "/api/auth/password-reset/request", status().isOk(), "{\"email\":\"axis.user@sk.com\"}"),
                e("001-400", "POST", "/api/auth/password-reset/confirm", status().isOk(), "{\"token\":\"reset-token\",\"new_password\":\"password456\"}"),
                e("001-400", "POST", "/api/auth/login", status().isOk(), "{\"email\":\"axis.user@sk.com\",\"password\":\"password123\"}"),
                e("401-800", "POST", "/api/auth/logout", status().isOk()),
                e("401-800", "POST", "/api/auth/refresh", status().isOk(), "{\"refresh_token\":\"axis-refresh-token\"}"),
                e("401-800", "GET", "/api/auth/me", status().isOk()),
                e("401-800", "GET", "/api/dashboard/summary", status().isOk()),
                fail("401-800", "GET", "/api/dashboard/today-insight", status().isBadGateway()),
                e("401-800", "POST", "/api/dashboard/today-insight/warmup", status().isAccepted()),
                fail("401-800", "POST", "/api/dashboard/today-insight/cron-generate", status().isBadGateway()),
                fail("401-800", "POST", "/api/search", status().isInternalServerError(), "{\"query\":\"AX\",\"scopes\":[\"cards\",\"peers\"],\"limit\":8}"),
                fail("401-800", "GET", "/api/search/suggestions?q=AX&limit=6", status().isInternalServerError()),
                e("401-800", "GET", "/api/peers", status().isOk()),
                e("401-800", "GET", "/api/peers/samsung_sds/profile", status().isOk()),
                e("401-800", "GET", "/api/issues", status().isOk()),
                e("401-800", "GET", "/api/issues/ISSUE-20260502-001", status().isOk()),
                e("401-800", "GET", "/api/cards?q=AX&limit=2", status().isOk()),
                e("401-800", "GET", "/api/cards/today?limit=2", status().isOk()),
                e("401-800", "GET", "/api/cards/CN-20260502-001", status().isOk()),
                fail("401-800", "POST", "/api/cards/CN-20260502-001/verify-link", status().isBadGateway()),
                e("401-800", "GET", "/api/monitoring/overview?period_unit=quarterly&period_value=2026Q2", status().isOk()),
                e("801-1200", "GET", "/api/monitoring/cards/search?q=AX", status().isOk()),
                e("801-1200", "GET", "/api/monitoring", status().isOk()),
                e("801-1200", "GET", "/api/monitoring/samsung_sds", status().isOk()),
                e("801-1200", "GET", "/api/monitoring/samsung_sds/cards", status().isOk()),
                e("801-1200", "GET", "/api/monitoring/samsung_sds/financials?quarters=4", status().isOk()),
                e("801-1200", "GET", "/api/monitoring/comparison?metric=revenue", status().isOk()),
                fail("801-1200", "GET", "/api/monitoring/samsung_sds/strategy", status().isBadGateway()),
                fail("801-1200", "GET", "/api/briefings/today", status().isServiceUnavailable()),
                fail("1201-1600", "GET", "/api/briefings", status().isServiceUnavailable()),
                fail("1201-1600", "GET", "/api/briefings/summary?briefing_type=daily", status().isServiceUnavailable()),
                e("1201-1600", "GET", "/api/briefings/cards/search?q=AX", status().isOk()),
                fail("1201-1600", "POST", "/api/briefings/generate", status().isBadGateway(), "{\"briefing_type\":\"daily\"}"),
                fail("1201-1600", "GET", "/api/briefings/BR-20260502-001/status", status().isNotFound()),
                fail("1201-1600", "GET", "/api/briefings/BR-20260502-001", status().isNotFound()),
                e("1201-1600", "POST", "/api/briefings/BR-20260502-001/share", status().isOk(), "{\"expires_in_hours\":168}"),
                e("1201-1600", "GET", "/api/alerts", status().isOk()),
                e("1201-1600", "POST", "/api/alerts/rules", status().isCreated(), "{\"name\":\"Agentic AI 알림\",\"enabled\":true,\"channels\":[\"email\"]}"),
                e("1201-1600", "PUT", "/api/alerts/rules/RULE-001", status().isOk(), "{\"name\":\"우선 검토 동향 즉시 알림\",\"enabled\":false,\"channels\":[\"email\"]}"),
                e("1201-1600", "DELETE", "/api/alerts/rules/RULE-001", status().isOk()),
                e("1201-1600", "POST", "/api/alerts/AL-20260504-001/read", status().isOk()),
                e("1201-1600", "GET", "/api/notifications", status().isOk()),
                e("1201-1600", "POST", "/api/notifications/notice-lg-cardnews/read", status().isOk()),
                e("1201-1600", "DELETE", "/api/notifications", status().isOk()),
                e("1601-2000", "GET", "/api/bookmarks", status().isOk()),
                e("1601-2000", "POST", "/api/bookmarks", status().isCreated(), "{\"card_id\":\"CN-20260502-001\"}"),
                e("1601-2000", "DELETE", "/api/bookmarks/CN-20260502-001", status().isOk()),
                e("1601-2000", "POST", "/api/cards/CN-20260502-001/share", status().isOk(), "{\"expires_in_hours\":24}"),
                e("1601-2000", "GET", "/api/insights/latest", status().isOk()),
                fail("1601-2000", "POST", "/api/insights/generate", status().isBadGateway(), "{\"card_ids\":[\"CN-20260502-001\"]}"),
                e("1601-2000", "GET", "/api/keyword-graph", status().isOk()),
                e("1601-2000", "GET", "/api/keyword-graph/agentic-ai/cards", status().isOk()),
                e("1601-2000", "GET", "/api/mixer/options", status().isOk()),
                e("1601-2000", "GET", "/api/mixer/recent", status().isOk()),
                fail("1601-2000", "POST", "/api/mixer", status().isBadGateway(), "{\"card_ids\":[\"CN-20260502-001\",\"CN-20260502-002\"]}"),
                fail("1601-2000", "POST", "/api/mixer/MX-20260504-001/share", status().isServiceUnavailable()),
                e("1601-2000", "GET", "/api/raw-articles", status().isOk()),
                e("1601-2000", "GET", "/api/raw-articles/1", status().isOk()),
                e("1601-2000", "POST", "/api/assistant/chat", status().isOk(), "{\"conversation_id\":\"conv_20260511_001\",\"message\":\"오늘 인사이트 요약해줘\"}"),
                e("1601-2000", "GET", "/api/settings/alert-rules", status().isOk()),
                e("1601-2000", "PUT", "/api/settings/alert-rules", status().isOk(), "{\"keywords\":[\"AX\"]}"),
                e("1601-2000", "GET", "/api/settings/notifications", status().isOk()),
                e("1601-2000", "PUT", "/api/settings/notifications", status().isOk(), "{\"channels\":{\"email\":true}}"),
                e("1601-2000", "GET", "/api/settings/profile", status().isOk()),
                e("1601-2000", "PUT", "/api/settings/profile", status().isOk(), "{\"name\":\"AXIS 사용자\"}"),
                e("1601-2000", "GET", "/api/settings/view-preferences", status().isOk()),
                e("1601-2000", "PUT", "/api/settings/view-preferences", status().isOk(), "{\"contentViewMode\":\"visual\"}"),
                e("2001-2400", "PUT", "/api/settings/password", status().isOk(), "{\"current_password\":\"password123\",\"new_password\":\"password456\"}"),
                e("2001-2400", "GET", "/api/settings/access-logs", status().isOk()),
                e("2001-2400", "GET", "/api/admin/peers", status().isOk()),
                e("2001-2400", "POST", "/api/admin/peers", status().isCreated(), "{\"id\":\"new_peer\",\"name\":\"신규 Peer\"}"),
                e("2001-2400", "PUT", "/api/admin/peers/samsung_sds", status().isOk(), "{\"id\":\"samsung_sds\",\"name\":\"삼성SDS\"}"),
                e("2001-2400", "DELETE", "/api/admin/peers/samsung_sds", status().isOk()),
                e("2001-2400", "GET", "/api/admin/sources", status().isOk()),
                e("2001-2400", "PUT", "/api/admin/sources/rss-news", status().isOk(), "{\"is_active\":true}"),
                e("2001-2400", "GET", "/api/admin/prompts", status().isOk()),
                e("2001-2400", "PUT", "/api/admin/prompts/prompt-summary-v1", status().isOk(), "{\"content\":\"updated\"}"),
                e("2001-2400", "GET", "/api/admin/scheduler", status().isOk()),
                e("2001-2400", "PUT", "/api/admin/scheduler/delivery-daily", status().isOk(), "{\"cron_expression\":\"0 30 8 * * MON-FRI\"}"),
                e("2401-2665", "GET", "/api/admin/usage?period=today", status().isOk()),
                e("2401-2665", "PUT", "/api/admin/usage/limits", status().isOk(), "{\"daily_token_limit\":100000}"),
                e("2401-2665", "GET", "/api/admin/audit-logs", status().isOk()),
                e("2401-2665", "GET", "/api/pipeline/status", status().isOk()),
                e("2401-2665", "POST", "/api/pipeline/trigger", status().isAccepted(), "{\"pipeline_type\":\"collection\"}")
        );
    }

    private Endpoint e(String chunk, String method, String path, ResultMatcher status) {
        return e(chunk, method, path, status, JSON);
    }

    private Endpoint e(String chunk, String method, String path, ResultMatcher status, String body) {
        return new Endpoint(chunk, method, path, status, body, true);
    }

    private Endpoint fail(String chunk, String method, String path, ResultMatcher status) {
        return fail(chunk, method, path, status, JSON);
    }

    private Endpoint fail(String chunk, String method, String path, ResultMatcher status, String body) {
        return new Endpoint(chunk, method, path, status, body, false);
    }

    private record Endpoint(
            String chunk,
            String method,
            String path,
            ResultMatcher status,
            String body,
            boolean success
    ) {
    }
}
