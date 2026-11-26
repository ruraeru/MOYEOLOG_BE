package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Memo;
import com.moyeolog.moyelog_BE.entity.MemoShare;
import com.moyeolog.moyelog_BE.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface MemoShareRepository extends JpaRepository<MemoShare, UUID> {
    List<MemoShare> findBySharedToOrderBySharedAtDesc(User user);
    boolean existsByMemo_IdAndSharedTo_Id(UUID memoId, UUID userId);
    boolean existsByMemo_IdAndSharedTo_IdAndCanEditTrue(UUID memoId, UUID userId);

    List<MemoShare> findAllByMemo_IdAndSharedTo_Id(UUID memoId, UUID userId);

    List<MemoShare> findAllByMemo(Memo memo);

    // author 가 작성해서 recipient 에게 공유한 메모 공유 목록 (친구 삭제 시 정리용)
    @Query("SELECT s FROM MemoShare s WHERE s.memo.author.id = :authorId AND s.sharedTo.id = :recipientId")
    List<MemoShare> findAllByAuthorAndRecipient(@Param("authorId") UUID authorId, @Param("recipientId") UUID recipientId);

    /** 두 사람 사이(양방향)의 공유 ID. 잠금 없이 읽고, 지울 때는 ID 로만 지운다. */
    @Query("SELECT s.id FROM MemoShare s WHERE (s.memo.author.id = :a AND s.sharedTo.id = :b) " +
            "OR (s.memo.author.id = :b AND s.sharedTo.id = :a)")
    List<UUID> findIdsBetween(@Param("a") UUID a, @Param("b") UUID b);

    @Query("SELECT s.memo.id FROM MemoShare s WHERE s.id IN :ids AND s.sharedTo.id = :recipientId")
    List<UUID> findMemoIdsByIdsAndRecipient(@Param("ids") Collection<UUID> ids, @Param("recipientId") UUID recipientId);

    @Modifying
    @Query("DELETE FROM MemoShare s WHERE s.id IN :ids")
    int deleteByIds(@Param("ids") Collection<UUID> ids);

    @Modifying
    @Query("DELETE FROM MemoShare s WHERE s.memo.id = :memoId AND s.sharedTo.id = :userId")
    int deleteByMemoIdAndUserId(@Param("memoId") UUID memoId, @Param("userId") UUID userId);

    @Modifying
    @Query("DELETE FROM MemoShare s WHERE s.sharedTo.id = :userId")
    int deleteAllSharedTo(@Param("userId") UUID userId);

    void deleteAllByMemo(Memo memo);
}
