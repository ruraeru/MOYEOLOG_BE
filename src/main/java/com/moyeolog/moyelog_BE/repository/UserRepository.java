package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByKakaoId(String kakaoId);
    Optional<User> findByEmail(String email);
    Optional<User> findByCustomId(String customId);
    boolean existsByCustomId(String customId);
}
