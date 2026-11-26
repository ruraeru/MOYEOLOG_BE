package com.moyeolog.moyelog_BE.datepoll;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DatePollRepository extends JpaRepository<DatePoll, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM DatePoll p WHERE p.id = :id")
    Optional<DatePoll> findByIdForUpdate(@Param("id") UUID id);

    List<DatePoll> findAllByGroup_IdAndStatusOrderByCreatedAtDesc(UUID groupId, DatePoll.Status status);

    List<DatePoll> findTop5ByGroup_IdAndStatusOrderByConfirmedAtDesc(UUID groupId, DatePoll.Status status);

    List<DatePoll> findAllByCreator_Id(UUID creatorId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM DatePoll p WHERE p.group.id = :groupId")
    int deleteAllByGroupId(@Param("groupId") UUID groupId);
}
