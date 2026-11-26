package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.MemoPin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MemoPinRepository extends JpaRepository<MemoPin, UUID> {

    boolean existsByUser_IdAndMemo_Id(UUID userId, UUID memoId);

    Optional<MemoPin> findFirstByUser_IdAndMemo_Id(UUID userId, UUID memoId);

    /** 보는 사람의 고정 전부. 목록에서 Map<memoId, pinnedAt> 을 만드는 데 쓴다. */
    List<MemoPin> findAllByUser_Id(UUID userId);

    /** 행이 없어도 오류 없이 0 을 돌려준다 (동시 해제 경합에서 500 이 나지 않도록). */
    @Modifying
    @Query("DELETE FROM MemoPin p WHERE p.user.id = :userId AND p.memo.id = :memoId")
    int deleteByUserIdAndMemoId(@Param("userId") UUID userId, @Param("memoId") UUID memoId);

    @Modifying
    @Query("DELETE FROM MemoPin p WHERE p.user.id = :userId AND p.memo.id IN :memoIds")
    int deleteByUserIdAndMemoIds(@Param("userId") UUID userId, @Param("memoIds") Collection<UUID> memoIds);

    @Modifying
    @Query("DELETE FROM MemoPin p WHERE p.user.id = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("DELETE FROM MemoPin p WHERE p.memo.id = :memoId")
    int deleteAllByMemoId(@Param("memoId") UUID memoId);

    /**
     * userId 가 groupId 모임에 공유된(memo_group_shares) 개인 메모 중 자기가 쓰지 않은 것에 한 고정을 지운다(모임을 나갈 때).
     * 친구 공유나 자기가 속한 다른 모임의 공유로 여전히 읽을 수 있는 메모의 고정은 남긴다.
     * 멤버 행을 지우기 전에 불러도 되도록 다른 모임 검사에서 groupId 자체는 뺀다.
     */
    @Modifying
    @Query("DELETE FROM MemoPin p WHERE p.user.id = :userId " +
            "AND p.memo.id IN (SELECT s.memo.id FROM MemoGroupShare s WHERE s.groupId = :groupId AND s.memo.author.id <> :userId) " +
            "AND p.memo.id NOT IN (SELECT ms.memo.id FROM MemoShare ms WHERE ms.sharedTo.id = :userId) " +
            "AND p.memo.id NOT IN (SELECT s2.memo.id FROM MemoGroupShare s2, GroupMember gm " +
            "WHERE gm.group.id = s2.groupId AND gm.user.id = :userId AND s2.groupId <> :groupId)")
    int deleteByUserIdAndGroupSharedMemos(@Param("userId") UUID userId, @Param("groupId") UUID groupId);

    /**
     * userId 가 groupId 모임의 모임 메모(memos.group_id)에 한 내 고정을 지운다(모임을 나가거나 내보내질 때).
     * 자기가 쓴 메모와 친구 공유로 여전히 읽을 수 있는 메모의 고정은 남긴다.
     */
    @Modifying
    @Query("DELETE FROM MemoPin p WHERE p.user.id = :userId " +
            "AND p.memo.id IN (SELECT m.id FROM Memo m WHERE m.groupId = :groupId AND m.author.id <> :userId) " +
            "AND p.memo.id NOT IN (SELECT ms.memo.id FROM MemoShare ms WHERE ms.sharedTo.id = :userId)")
    int deleteByUserIdAndGroupMemos(@Param("userId") UUID userId, @Param("groupId") UUID groupId);

    /**
     * groupId 모임의 모임 메모에 대한 고정 중 작성자 본인 것이 아닌 것을 지운다(모임 삭제로 메모가 개인 메모로 돌아가기 전에).
     * 친구 공유로 여전히 읽을 수 있는 사람의 고정은 남긴다.
     */
    @Modifying
    @Query("DELETE FROM MemoPin p WHERE p.memo.id IN " +
            "(SELECT m.id FROM Memo m WHERE m.groupId = :groupId AND m.author.id <> p.user.id) " +
            "AND p.memo.id NOT IN (SELECT ms.memo.id FROM MemoShare ms WHERE ms.sharedTo.id = p.user.id)")
    int deleteNonAuthorPinsByGroupId(@Param("groupId") UUID groupId);
}
