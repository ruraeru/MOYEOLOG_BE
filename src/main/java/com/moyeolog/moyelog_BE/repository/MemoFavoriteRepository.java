package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Memo;
import com.moyeolog.moyelog_BE.entity.MemoFavorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface MemoFavoriteRepository extends JpaRepository<MemoFavorite, UUID> {

    boolean existsByUser_IdAndMemo_Id(UUID userId, UUID memoId);

    List<MemoFavorite> findAllByUser_IdAndMemo_Id(UUID userId, UUID memoId);

    @Query("SELECT f.memo.id FROM MemoFavorite f WHERE f.user.id = :userId")
    List<UUID> findMemoIdsByUserId(@Param("userId") UUID userId);

    /** 행이 없어도 오류 없이 0 을 돌려준다 (동시 해제 경합에서 500 이 나지 않도록). */
    @Modifying
    @Query("DELETE FROM MemoFavorite f WHERE f.user.id = :userId AND f.memo.id = :memoId")
    int deleteByUserIdAndMemoId(@Param("userId") UUID userId, @Param("memoId") UUID memoId);

    /** memoIds 중 userId 가 모임 멤버로서 여전히 읽을 수 있는 모임 메모를 뺀 나머지의 즐겨찾기를 지운다 (공유가 끊겼을 때). */
    @Modifying
    @Query("DELETE FROM MemoFavorite f WHERE f.user.id = :userId AND f.memo.id IN :memoIds " +
            "AND f.memo.id NOT IN (SELECT m.id FROM Memo m WHERE m.id IN :memoIds AND m.groupId IN " +
            "(SELECT gm.group.id FROM GroupMember gm WHERE gm.user.id = :userId))")
    int deleteUnreadableByUserIdAndMemoIds(@Param("userId") UUID userId, @Param("memoIds") Collection<UUID> memoIds);

    @Modifying
    @Query("DELETE FROM MemoFavorite f WHERE f.user.id = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);

    void deleteAllByMemo(Memo memo);
}
