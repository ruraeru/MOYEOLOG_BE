package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** 동시에 들어온 갱신 요청이 같은 행을 두 번 교체하지 못하도록 행 잠금을 건다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshToken r WHERE r.token IN :tokens")
    List<RefreshToken> findAllByTokenInForUpdate(@Param("tokens") Collection<String> tokens);

    List<RefreshToken> findAllByPreviousToken(String previousToken);

    boolean existsByTokenIn(Collection<String> tokens);

    void deleteAllByUserIdAndExpiryDateBefore(UUID userId, LocalDateTime dateTime);

    /** 로그아웃: 현재 토큰이거나 방금 교체된 직전 토큰인 행 */
    @Query("SELECT r FROM RefreshToken r WHERE r.token IN :tokens OR r.previousToken = :hash")
    List<RefreshToken> findAllForLogout(@Param("tokens") Collection<String> tokens, @Param("hash") String hash);

    @Query("SELECT r FROM RefreshToken r WHERE r.userId = :userId AND r.expiryDate > :now ORDER BY r.id DESC")
    List<RefreshToken> findActiveByUserId(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
