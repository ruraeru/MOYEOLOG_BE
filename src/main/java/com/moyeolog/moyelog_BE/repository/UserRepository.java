package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByKakaoId(String kakaoId);
    Optional<User> findByEmail(String email);
}
