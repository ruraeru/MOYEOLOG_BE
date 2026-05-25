package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.MemoAiInsight;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MemoAiInsightRepository extends JpaRepository<MemoAiInsight, UUID> {
}
