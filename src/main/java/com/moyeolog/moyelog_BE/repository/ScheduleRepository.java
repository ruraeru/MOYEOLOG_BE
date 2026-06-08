package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Schedule;
import com.moyeolog.moyelog_BE.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {
    
    @Query("SELECT DISTINCT s FROM Schedule s LEFT JOIN s.participants p WHERE s.author = :user OR s.groupId IN :groupIds OR p = :user")
    List<Schedule> findByUserOrGroupIds(@Param("user") User user, @Param("groupIds") List<UUID> groupIds);

    @Query("SELECT DISTINCT s FROM Schedule s LEFT JOIN s.participants p WHERE s.author = :user OR p = :user")
    List<Schedule> findAllByUser(@Param("user") User user);

    List<Schedule> findAllByGroupIdOrderByStartTimeAsc(UUID groupId);
    List<Schedule> findByGroupIdInOrderByCreatedAtDesc(List<UUID> groupIds);
    List<Schedule> findByTaggedMemosContains(com.moyeolog.moyelog_BE.entity.Memo memo);
}
