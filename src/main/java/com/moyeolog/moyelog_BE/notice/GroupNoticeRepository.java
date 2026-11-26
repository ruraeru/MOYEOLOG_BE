package com.moyeolog.moyelog_BE.notice;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GroupNoticeRepository extends JpaRepository<GroupNotice, UUID> {

    /** 모임의 공지 전부(작성자 함께). 정렬은 서비스가 한다. */
    @Query("SELECT n FROM GroupNotice n JOIN FETCH n.author WHERE n.group.id = :groupId")
    List<GroupNotice> findAllByGroupId(@Param("groupId") UUID groupId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT n FROM GroupNotice n WHERE n.id = :id")
    Optional<GroupNotice> findByIdForUpdate(@Param("id") UUID id);

    List<GroupNotice> findAllByAuthor_Id(UUID userId);

    /** 모임 삭제. 읽음·투표·선택지·표는 DB 가 ON DELETE CASCADE 로 지운다. */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM GroupNotice n WHERE n.group.id = :groupId")
    int deleteAllByGroupId(@Param("groupId") UUID groupId);
}
