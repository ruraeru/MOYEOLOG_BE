package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.GroupInvitation;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.enums.InvitationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GroupInvitationRepository extends JpaRepository<GroupInvitation, UUID> {
    List<GroupInvitation> findByInviteeAndStatus(User invitee, InvitationStatus status);
    boolean existsByGroupAndInviteeAndStatus(Group group, User invitee, InvitationStatus status);

    /** 초대 ID 로 모임 ID 만 읽는다(엔티티를 영속성 컨텍스트에 올리지 않음). */
    @Query("select i.group.id from GroupInvitation i where i.id = :id")
    Optional<UUID> findGroupIdById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from GroupInvitation i where i.id = :id")
    Optional<GroupInvitation> findByIdForUpdate(@Param("id") UUID id);

    /** 최근에 이 모임 초대를 거절했는지 (재초대 제한) */
    boolean existsByGroup_IdAndInvitee_IdAndStatusAndRespondedAtAfter(UUID groupId, UUID inviteeId,
                                                                      InvitationStatus status, LocalDateTime after);

    /** 그 사람이 받은 이 모임의 대기 초대 정리 (가입·강퇴 시) */
    @Modifying(flushAutomatically = true)
    @Query("delete from GroupInvitation i where i.group.id = :groupId and i.invitee.id = :inviteeId " +
            "and i.status = com.moyeolog.moyelog_BE.enums.InvitationStatus.PENDING")
    int deletePendingForInvitee(@Param("groupId") UUID groupId, @Param("inviteeId") UUID inviteeId);

    /** 그 사람이 보낸 이 모임의 대기 초대 정리 (강퇴·나가기 시) */
    @Modifying(flushAutomatically = true)
    @Query("delete from GroupInvitation i where i.group.id = :groupId and i.inviter.id = :inviterId " +
            "and i.status = com.moyeolog.moyelog_BE.enums.InvitationStatus.PENDING")
    int deletePendingFromInviter(@Param("groupId") UUID groupId, @Param("inviterId") UUID inviterId);

    @Modifying(flushAutomatically = true)
    @Query("delete from GroupInvitation i where i.inviter.id = :userId or i.invitee.id = :userId")
    int deleteAllInvolvingUser(@Param("userId") UUID userId);

    void deleteAllByGroup(Group group);
}
