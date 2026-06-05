package com.skala.axis.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skala.axis.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private final AuthProperties authProperties;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;
    private final boolean agentTestEnabled;

    public SecurityConfig(
            AuthProperties authProperties,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ObjectMapper objectMapper,
            @Value("${axis.agent-test.enabled:false}") boolean agentTestEnabled
    ) {
        this.authProperties = authProperties;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = objectMapper;
        this.agentTestEnabled = agentTestEnabled;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults());

        if (!authProperties.isEnforce()) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exception -> exception
                    .authenticationEntryPoint((request, response, authException) ->
                            writeError(response, 401, "UNAUTHORIZED", "로그인이 필요합니다."))
                    .accessDeniedHandler((request, response, accessDeniedException) ->
                            writeError(response, 403, "FORBIDDEN", "접근 권한이 없습니다."))
            )
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/health", "/actuator/health", "/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/password-reset/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/email-verifications/confirm").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/email-verifications/resend", "/api/auth/verify-email").permitAll()
                        // CronJob entrypoints — Bearer ${CRON_INTERNAL_TOKEN} 검증은 PipelineController.isCronAuthorized 에서 수행.
                        // 이 경로를 화이트리스트하지 않으면 axis-cron-ingestion-{a,b,c,d}, axis-cron-delivery 가 401 로 실패함.
                        .requestMatchers(HttpMethod.POST, "/api/pipeline/trigger", "/api/pipeline/delivery").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/pipeline/status").permitAll();
                if (agentTestEnabled) {
                    auth.requestMatchers("/api/dev/agents/**").permitAll();
                }
                auth.requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated();
            })
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${axis.cors.allowed-origins:http://localhost:3000,http://localhost:3100}") String allowedOrigins
    ) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(splitCsv(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private List<String> splitCsv(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse response, int status, String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message));
    }
}
