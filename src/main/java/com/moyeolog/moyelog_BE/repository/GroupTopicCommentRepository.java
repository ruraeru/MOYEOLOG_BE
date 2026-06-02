package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.GroupTopic;
import com.moyeolog.moyelog_BE.entity.GroupTopicComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GroupTopicCommentRepository extends JpaRepository<GroupTopicComment, UUID> {
    List<GroupTopicComment> findAllByTopicOrderByCreatedAtAsc(GroupTopic topic);
    List<GroupTopicComment> findByTopic_Group_IdInOrderByCreatedAtDesc(List<UUID> groupIds);
}
