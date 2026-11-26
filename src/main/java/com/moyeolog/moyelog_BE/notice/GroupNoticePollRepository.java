package com.moyeolog.moyelog_BE.notice;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GroupNoticePollRepository extends JpaRepository<GroupNoticePoll, UUID> {

    Optional<GroupNoticePoll> findByNotice_Id(UUID noticeId);

    /** 고르기·마감을 한 줄로 세운다(투표 행 잠금). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM GroupNoticePoll p WHERE p.notice.id = :noticeId")
    Optional<GroupNoticePoll> findByNoticeIdForUpdate(@Param("noticeId") UUID noticeId);

    @Query("SELECT p FROM GroupNoticePoll p WHERE p.notice.id IN :noticeIds")
    List<GroupNoticePoll> findAllByNoticeIds(@Param("noticeIds") Collection<UUID> noticeIds);
}
