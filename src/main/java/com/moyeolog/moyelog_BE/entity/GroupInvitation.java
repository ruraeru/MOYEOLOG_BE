package com.moyeolog.moyelog_BE.entity;

import com.moyeolog.moyelog_BE.enums.InvitationStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 모임 초대.
 * 같은 사람에게 대기(PENDING) 초대는 하나만 있도록 pending_flag 를 둔다:
 * 대기 중이면 true, 처리되면 NULL. (group_id, invitee_id, pending_flag) 유니크 제약은
 * NULL 끼리는 겹쳐도 되므로 처리된 초대는 여러 개 남을 수 있다.
 */
@Entity
@Table(name = "group_invitations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_group_invitations_pending", columnNames = {"group_id", "invitee_id", "pending_flag"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class GroupInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inviter_id", nullable = false)
    private User inviter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invitee_id", nullable = false)
    private User invitee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvitationStatus status;

    /** 대기 중이면 true, 처리되면 NULL (중복 대기 초대 방지용 유니크 키) */
    @Column(name = "pending_flag")
    private Boolean pendingFlag;

    @Column(name = "invited_at", updatable = false)
    private LocalDateTime invitedAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @PrePersist
    protected void onInvite() {
        invitedAt = LocalDateTime.now();
        if (status == null) {
            status = InvitationStatus.PENDING;
        }
        pendingFlag = status == InvitationStatus.PENDING ? Boolean.TRUE : null;
    }

    public void accept() {
        this.status = InvitationStatus.ACCEPTED;
        this.pendingFlag = null;
        this.respondedAt = LocalDateTime.now();
    }

    public void reject() {
        this.status = InvitationStatus.REJECTED;
        this.pendingFlag = null;
        this.respondedAt = LocalDateTime.now();
    }
}
