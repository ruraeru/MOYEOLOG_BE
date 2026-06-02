package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.GroupTopic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GroupTopicRepository extends JpaRepository<GroupTopic, UUID> {
    List<GroupTopic> findAllByGroupOrderByCreatedAtDesc(Group group);
    List<GroupTopic> findByGroup_IdInOrderByCreatedAtDesc(List<UUID> groupIds);
}
