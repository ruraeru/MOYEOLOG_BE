package com.moyeolog.moyelog_BE.midpoint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduleDepartureRepository extends JpaRepository<ScheduleDeparture, UUID> {

    @Query("SELECT d FROM ScheduleDeparture d JOIN FETCH d.user WHERE d.schedule.id = :scheduleId ORDER BY d.updatedAt ASC")
    List<ScheduleDeparture> findAllBySchedule(@Param("scheduleId") UUID scheduleId);

    Optional<ScheduleDeparture> findBySchedule_IdAndUser_Id(UUID scheduleId, UUID userId);

    /** 다른 일정에 마지막으로 적은 출발지 ('지난번 출발지 쓰기'용) */
    Optional<ScheduleDeparture> findFirstByUser_IdAndSchedule_IdNotOrderByUpdatedAtDesc(UUID userId, UUID scheduleId);

    @Modifying
    @Query("DELETE FROM ScheduleDeparture d WHERE d.user.id = :userId")
    void deleteAllByUserId(@Param("userId") UUID userId);
}
