package com.moyeolog.moyelog_BE.dto;

import com.moyeolog.moyelog_BE.enums.InvitationStatus;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupInvitationResponse {
    private UUID id;
    private UUID groupId;
    private String groupName;
    private String inviterNickname;
    private InvitationStatus status;
    private LocalDateTime invitedAt;
    /** 수락 전에 어떤 모임인지 볼 수 있게(초대 링크 미리보기와 같은 정보) */
    private String groupDescription;
    private int memberCount;
    private String ownerNickname;
}
