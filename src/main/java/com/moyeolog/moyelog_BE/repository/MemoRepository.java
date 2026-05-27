package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Memo;
import com.moyeolog.moyelog_BE.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MemoRepository extends JpaRepository<Memo, UUID> {
    List<Memo> findAllByAuthorOrderByCreatedAtDesc(User author);
    List<Memo> findByGroupIdOrderByCreatedAtDesc(UUID groupId);
}
