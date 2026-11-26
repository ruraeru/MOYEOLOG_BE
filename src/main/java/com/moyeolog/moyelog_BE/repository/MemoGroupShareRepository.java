package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Memo;
import com.moyeolog.moyelog_BE.entity.MemoGroupShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MemoGroupShareRepository extends JpaRepository<MemoGroupShare, UUID> {

    boolean existsByMemo_IdAndGroupId(UUID memoId, UUID groupId);

    @Query("SELECT s.groupId FROM MemoGroupShare s WHERE s.memo.id = :memoId")
    List<UUID> findGroupIdsByMemoId(@Param("memoId") UUID memoId);

    /** 이 사용자가 지금 멤버인 모임에 공유된 메모인가 */
    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM MemoGroupShare s, GroupMember gm " +
            "WHERE s.memo.id = :memoId AND gm.group.id = s.groupId AND gm.user.id = :userId")
    boolean existsSharedToMember(@Param("memoId") UUID memoId, @Param("userId") UUID userId);

    /** 모임에 공유된 메모, 최근에 공유한 순 */
    @Query("SELECT s.memo FROM MemoGroupShare s WHERE s.groupId = :groupId ORDER BY s.sharedAt DESC")
    List<Memo> findMemosByGroupId(@Param("groupId") UUID groupId);

    @Modifying
    @Query("DELETE FROM MemoGroupShare s WHERE s.memo.id = :memoId AND s.groupId = :groupId")
    int deleteByMemoIdAndGroupId(@Param("memoId") UUID memoId, @Param("groupId") UUID groupId);

    @Modifying
    @Query("DELETE FROM MemoGroupShare s WHERE s.memo.id = :memoId")
    int deleteAllByMemoId(@Param("memoId") UUID memoId);

    @Modifying
    @Query("DELETE FROM MemoGroupShare s WHERE s.groupId = :groupId")
    int deleteAllByGroupId(@Param("groupId") UUID groupId);

    /** 작성자가 모임을 나가거나 내보내지면 그 사람이 그 모임에 공유한 메모를 거둔다 */
    @Modifying
    @Query("DELETE FROM MemoGroupShare s WHERE s.groupId = :groupId " +
            "AND s.memo.id IN (SELECT m.id FROM Memo m WHERE m.author.id = :authorId)")
    int deleteAllByGroupIdAndAuthorId(@Param("groupId") UUID groupId, @Param("authorId") UUID authorId);
}
