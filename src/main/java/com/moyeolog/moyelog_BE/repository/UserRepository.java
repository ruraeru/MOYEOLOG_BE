package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByKakaoId(String kakaoId);
    // 이메일은 null 일 수 있으므로(여러 행) 단건 조회 대신 목록으로 조회한다. null/빈 값으로 호출하지 말 것.
    List<User> findAllByEmail(String email);
    Optional<User> findByCustomId(String customId);
    boolean existsByCustomId(String customId);
}
