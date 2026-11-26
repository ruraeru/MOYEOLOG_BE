package com.moyeolog.moyelog_BE.datepoll;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DatePollVoteRepository extends JpaRepository<DatePollVote, UUID> {

    @Query("SELECT v FROM DatePollVote v JOIN FETCH v.user WHERE v.poll.id = :pollId")
    List<DatePollVote> findAllByPoll(@Param("pollId") UUID pollId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM DatePollVote v WHERE v.poll.id = :pollId AND v.user.id = :userId")
    int deleteMine(@Param("pollId") UUID pollId, @Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM DatePollVote v WHERE v.poll.id = :pollId")
    int deleteAllByPollId(@Param("pollId") UUID pollId);

    /** 모임을 나가거나 내보내진 사람의 그 모임 선택 */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM DatePollVote v WHERE v.user.id = :userId"
            + " AND v.poll.id IN (SELECT p.id FROM DatePoll p WHERE p.group.id = :groupId)")
    int deleteAllByGroupAndUser(@Param("groupId") UUID groupId, @Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM DatePollVote v WHERE v.poll.id IN (SELECT p.id FROM DatePoll p WHERE p.group.id = :groupId)")
    int deleteAllByGroupId(@Param("groupId") UUID groupId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM DatePollVote v WHERE v.user.id = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
