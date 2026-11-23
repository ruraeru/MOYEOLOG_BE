package com.moyeolog.moyelog_BE.dto;

import lombok.*;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupInviteRequest {
    /** 초대할 친구의 사용자 ID. 초대하는 사람의 친구만 가능하다. */
    private List<UUID> userIds;

    /** 지원 종료. 값을 보내면 400 (가입 여부 노출 방지). 요청에 들어왔는지 확인하려고만 남겨 둔다. */
    private List<String> emails;
}
