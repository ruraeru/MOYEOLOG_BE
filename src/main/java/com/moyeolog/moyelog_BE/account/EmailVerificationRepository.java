package com.moyeolog.moyelog_BE.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    /** 같은 토큰을 동시에 두 번 쓰지 못하게 행을 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM EmailVerification v WHERE v.tokenHash = :tokenHash")
    Optional<EmailVerification> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    Optional<EmailVerification> findByTokenHash(String tokenHash);

    /** 비밀번호를 바꾸면 그 사용자의 남은 PASSWORD 링크를 모두 못 쓰게 한다. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE EmailVerification v SET v.usedAt = :now WHERE v.userId = :userId "
            + "AND v.purpose = :purpose AND v.usedAt IS NULL")
    int markAllUsed(@Param("userId") UUID userId, @Param("purpose") EmailVerification.Purpose purpose,
                    @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM EmailVerification v WHERE v.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") LocalDateTime before);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM EmailVerification v WHERE v.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
