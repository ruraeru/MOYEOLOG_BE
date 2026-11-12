package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Memo;
import com.moyeolog.moyelog_BE.entity.MemoFavorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MemoFavoriteRepository extends JpaRepository<MemoFavorite, UUID> {

    boolean existsByUser_IdAndMemo_Id(UUID userId, UUID memoId);

    List<MemoFavorite> findAllByUser_IdAndMemo_Id(UUID userId, UUID memoId);

    @Query("SELECT f.memo.id FROM MemoFavorite f WHERE f.user.id = :userId")
    List<UUID> findMemoIdsByUserId(@Param("userId") UUID userId);

    void deleteAllByMemo(Memo memo);
}
