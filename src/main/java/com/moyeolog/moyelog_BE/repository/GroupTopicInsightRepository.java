package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.GroupTopicInsight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface GroupTopicInsightRepository extends JpaRepository<GroupTopicInsight, UUID> {
}
