package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.MemoAiInsight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
public interface MemoAiInsightRepository extends JpaRepository<MemoAiInsight, UUID> {

    /** 회원 탈퇴: 그 사용자가 쓴 메모와, 그 사용자 혼자 남은 모임의 메모에 달린 분석 결과를 지운다. */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("delete from MemoAiInsight i where i.memoId in ("
            + " select m.id from Memo m where m.author.id = :userId"
            + " or m.groupId in (select gm.group.id from GroupMember gm where gm.user.id = :userId"
            + "   and (select count(g2) from GroupMember g2 where g2.group.id = gm.group.id) = 1))")
    int deleteAllForUserDeletion(@Param("userId") UUID userId);
}
