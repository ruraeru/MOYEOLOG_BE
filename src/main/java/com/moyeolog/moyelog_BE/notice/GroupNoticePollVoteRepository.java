package com.moyeolog.moyelog_BE.notice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface GroupNoticePollVoteRepository extends JpaRepository<GroupNoticePollVote, UUID> {

    /** 여러 투표의 표(고른 사람 함께). 선택지는 v.getOption().getId() 로만 본다. */
    @Query("SELECT v FROM GroupNoticePollVote v JOIN FETCH v.user WHERE v.poll.id IN :pollIds")
    List<GroupNoticePollVote> findAllByPollIds(@Param("pollIds") Collection<UUID> pollIds);

    /** 내 선택을 통째로 바꾸기 전에 지운다. */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM GroupNoticePollVote v WHERE v.poll.id = :pollId AND v.user.id = :userId")
    int deleteByPollIdAndUserId(@Param("pollId") UUID pollId, @Param("userId") UUID userId);

    /** 모임을 나가거나 내보내진 사람의 그 모임 투표 */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM GroupNoticePollVote v WHERE v.user.id = :userId AND v.poll.id IN"
            + " (SELECT p.id FROM GroupNoticePoll p WHERE p.notice.group.id = :groupId)")
    int deleteByUserIdAndGroupId(@Param("userId") UUID userId, @Param("groupId") UUID groupId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM GroupNoticePollVote v WHERE v.user.id = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
