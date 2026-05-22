package com.skala.axis;

import com.skala.axis.dto.auth.PasswordResetConfirmRequest;
import com.skala.axis.dto.auth.PasswordResetConfirmResponse;
import com.skala.axis.dto.auth.PasswordResetRequest;
import com.skala.axis.dto.auth.PasswordResetRequestResponse;
import com.skala.axis.service.AuthService;
import com.skala.axis.service.RequestMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:authpublicdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false",
        "axis.auth.enforce=true",
        "ai.server.base-url=http://localhost:9999"
})
class AuthPublicSecurityTests {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @Test
    void passwordResetRequestIsPublicWhenAuthIsEnforced() throws Exception {
        when(authService.requestPasswordReset(any(PasswordResetRequest.class), any(RequestMetadata.class)))
                .thenReturn(new PasswordResetRequestResponse(true, "ok", 30));

        mockMvc.perform(post("/api/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@sk.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accepted").value(true));

        verify(authService).requestPasswordReset(any(PasswordResetRequest.class), any(RequestMetadata.class));
    }

    @Test
    void passwordResetConfirmIsPublicWhenAuthIsEnforced() throws Exception {
        when(authService.confirmPasswordReset(any(PasswordResetConfirmRequest.class), any(RequestMetadata.class)))
                .thenReturn(new PasswordResetConfirmResponse(true));

        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"reset-token\",\"new_password\":\"password456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.password_reset").value(true));

        verify(authService).confirmPasswordReset(any(PasswordResetConfirmRequest.class), any(RequestMetadata.class));
    }
}
