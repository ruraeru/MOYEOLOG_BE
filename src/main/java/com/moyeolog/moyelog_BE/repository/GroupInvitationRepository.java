package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.GroupInvitation;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.enums.InvitationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GroupInvitationRepository extends JpaRepository<GroupInvitation, UUID> {
    List<GroupInvitation> findByInviteeAndStatus(User invitee, InvitationStatus status);
    Optional<GroupInvitation> findByGroupAndInviteeAndStatus(Group group, User invitee, InvitationStatus status);
}
