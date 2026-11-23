package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Memo;
import com.moyeolog.moyelog_BE.entity.Schedule;
import com.moyeolog.moyelog_BE.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {
    
    @Query("SELECT DISTINCT s FROM Schedule s LEFT JOIN s.participants p WHERE s.author = :user OR s.groupId IN :groupIds OR p = :user")
    List<Schedule> findByUserOrGroupIds(@Param("user") User user, @Param("groupIds") List<UUID> groupIds);

    @Query("SELECT DISTINCT s FROM Schedule s LEFT JOIN s.participants p WHERE s.author = :user OR p = :user")
    List<Schedule> findAllByUser(@Param("user") User user);

    List<Schedule> findAllByGroupIdOrderByStartTimeAsc(UUID groupId);
    List<Schedule> findByGroupIdInOrderByCreatedAtDesc(List<UUID> groupIds);
    List<Schedule> findByTaggedMemosContains(Memo memo);

    /** 같은 일정에 동시에 들어오는 수정·삭제를 줄 세운다 (SELECT ... FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Schedule s WHERE s.id = :id")
    Optional<Schedule> findByIdForUpdate(@Param("id") UUID id);

    /** authorId 가 만든 개인 일정 중 participantId 가 참여자인 것 (친구 삭제 시 정리용). */
    @Query("SELECT DISTINCT s FROM Schedule s JOIN s.participants p WHERE s.groupId IS NULL " +
            "AND s.author.id = :authorId AND p.id = :participantId")
    List<Schedule> findPersonalByAuthorAndParticipant(@Param("authorId") UUID authorId,
                                                      @Param("participantId") UUID participantId);

    @Query("SELECT s FROM Schedule s WHERE s.author.id = :authorId")
    List<Schedule> findAllByAuthorId(@Param("authorId") UUID authorId);

    @Query("SELECT DISTINCT s FROM Schedule s JOIN s.participants p WHERE p.id = :userId")
    List<Schedule> findAllByParticipantId(@Param("userId") UUID userId);
}
