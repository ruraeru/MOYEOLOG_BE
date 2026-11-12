package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Memo;
import com.moyeolog.moyelog_BE.entity.MemoShare;
import com.moyeolog.moyelog_BE.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MemoShareRepository extends JpaRepository<MemoShare, UUID> {
    List<MemoShare> findBySharedToOrderBySharedAtDesc(User user);
    boolean existsByMemo_IdAndSharedTo_Id(UUID memoId, UUID userId);

    List<MemoShare> findAllByMemo_IdAndSharedTo_Id(UUID memoId, UUID userId);

    List<MemoShare> findAllByMemo(Memo memo);

    // author 가 작성해서 recipient 에게 공유한 메모 공유 목록 (친구 삭제 시 정리용)
    @Query("SELECT s FROM MemoShare s WHERE s.memo.author.id = :authorId AND s.sharedTo.id = :recipientId")
    List<MemoShare> findAllByAuthorAndRecipient(@Param("authorId") UUID authorId, @Param("recipientId") UUID recipientId);
    void deleteAllByMemo(Memo memo);
}
