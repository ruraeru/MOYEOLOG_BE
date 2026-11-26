package com.moyeolog.moyelog_BE.dto;

import lombok.*;

/**
 * POST /api/groups/{id}/invitations 응답.
 * 이미 대기 초대가 있거나 이미 멤버인 친구는 건너뛰므로, 실제로 보낸 수를 따로 알려 준다
 * (화면이 "n명에게 보냈어요" 를 사실대로 말하게).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupInviteResponse {
    /** 새로 보낸 초대 수 */
    private int invitedCount;
    /** 이미 대기 중인 초대가 있어 건너뛴 수 */
    private int alreadyInvitedCount;
    /** 이미 모임 멤버라 건너뛴 수 */
    private int alreadyMemberCount;
}
