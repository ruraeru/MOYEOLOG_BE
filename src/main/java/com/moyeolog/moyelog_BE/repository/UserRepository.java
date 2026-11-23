package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /** 이메일 대소문자를 무시하고 찾는다 (MySQL 콜레이션과 같은 결과를 H2 에서도 얻기 위해 명시). */
    @Query("SELECT u FROM User u WHERE LOWER(u.email) = LOWER(:email)")
    List<User> findAllByEmailIgnoreCase(@Param("email") String email);

    @Query("SELECT u FROM User u WHERE LOWER(u.customId) = LOWER(:customId)")
    List<User> findAllByCustomIdIgnoreCase(@Param("customId") String customId);

    /** 같은 사용자의 동시 수정(프로필 사진 교체 등)을 차례로 처리하기 위한 행 잠금 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);
}
