package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByToken(String token);
    Optional<RefreshToken> findByUserId(UUID userId);
    void deleteByUserId(UUID userId);
}
