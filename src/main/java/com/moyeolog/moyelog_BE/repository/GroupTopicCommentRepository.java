package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.GroupTopic;
import com.moyeolog.moyelog_BE.entity.GroupTopicComment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GroupTopicCommentRepository extends JpaRepository<GroupTopicComment, UUID> {
    List<GroupTopicComment> findAllByTopicOrderByCreatedAtAsc(GroupTopic topic);
    List<GroupTopicComment> findByTopic_Group_IdInOrderByCreatedAtDesc(List<UUID> groupIds);

    long countByTopic(GroupTopic topic);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from GroupTopicComment c where c.id = :id")
    Optional<GroupTopicComment> findByIdForUpdate(@Param("id") UUID id);

    @Modifying(flushAutomatically = true)
    @Query("delete from GroupTopicComment c where c.author.id = :userId")
    int deleteAllByAuthorId(@Param("userId") UUID userId);
}
