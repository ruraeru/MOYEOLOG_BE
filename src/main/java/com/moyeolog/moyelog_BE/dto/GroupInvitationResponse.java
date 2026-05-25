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
}
