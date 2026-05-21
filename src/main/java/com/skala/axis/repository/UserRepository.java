package com.skala.axis.repository;

import com.skala.axis.domain.User;
import com.skala.axis.domain.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findTopByStatusOrderByCreatedAtDesc(UserStatus status);
}
