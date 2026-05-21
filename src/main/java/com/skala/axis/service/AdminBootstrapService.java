package com.skala.axis.service;

import com.skala.axis.config.AuthProperties;
import com.skala.axis.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrapService implements ApplicationRunner {
    private final AuthProperties authProperties;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (String email : authProperties.bootstrapAdminEmailSet()) {
            userRepository.findByEmail(email).ifPresentOrElse(user -> {
                if (user.isEmailVerified()) {
                    user.promoteToAdmin();
                    log.info("Bootstrap admin granted: {}", email);
                } else {
                    log.warn("Bootstrap admin skipped because email is not verified: {}", email);
                }
            }, () -> log.warn("Bootstrap admin skipped because user does not exist: {}", email));
        }
    }
}
