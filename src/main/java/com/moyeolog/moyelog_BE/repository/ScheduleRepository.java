package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Schedule;
import com.moyeolog.moyelog_BE.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {
    
    @Query("SELECT s FROM Schedule s WHERE s.author = :author OR s.groupId IN :groupIds")
    List<Schedule> findByAuthorOrGroupIds(@Param("author") User author, @Param("groupIds") List<UUID> groupIds);

    List<Schedule> findAllByAuthor(User author);

    List<Schedule> findAllByGroupIdOrderByStartTimeAsc(UUID groupId);
}
