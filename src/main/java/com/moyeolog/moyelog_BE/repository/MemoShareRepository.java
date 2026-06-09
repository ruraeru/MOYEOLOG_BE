package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Memo;
import com.moyeolog.moyelog_BE.entity.MemoShare;
import com.moyeolog.moyelog_BE.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MemoShareRepository extends JpaRepository<MemoShare, UUID> {
    List<MemoShare> findBySharedToOrderBySharedAtDesc(User user);
    Optional<MemoShare> findByMemoAndSharedTo(Memo memo, User user);
    void deleteAllByMemo(Memo memo);
}
