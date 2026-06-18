/*
 * 작성일: 2026-05-21
 * 작성자: 박진
 * 변경이력:
 *   2026-05-21 박진 — 로그인/회원가입 추가 작업에서 사용자 설정 리포지토리 추가
 */
package com.skala.axis.repository;

import com.skala.axis.domain.UserSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserSettingRepository extends JpaRepository<UserSetting, UUID> {
    Optional<UserSetting> findByUserId(UUID userId);
}
