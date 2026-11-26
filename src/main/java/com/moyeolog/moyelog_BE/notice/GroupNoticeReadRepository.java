package com.moyeolog.moyelog_BE.notice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface GroupNoticeReadRepository extends JpaRepository<GroupNoticeRead, UUID> {

    boolean existsByNotice_IdAndUser_Id(UUID noticeId, UUID userId);

    /** 여러 공지의 읽음 기록(읽은 사람 함께) */
    @Query("SELECT r FROM GroupNoticeRead r JOIN FETCH r.user WHERE r.notice.id IN :noticeIds")
    List<GroupNoticeRead> findAllByNoticeIds(@Param("noticeIds") Collection<UUID> noticeIds);

    /** 모임을 나가거나 내보내진 사람의 그 모임 공지 읽음 */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM GroupNoticeRead r WHERE r.user.id = :userId"
            + " AND r.notice.id IN (SELECT n.id FROM GroupNotice n WHERE n.group.id = :groupId)")
    int deleteByUserIdAndGroupId(@Param("userId") UUID userId, @Param("groupId") UUID groupId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM GroupNoticeRead r WHERE r.user.id = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
