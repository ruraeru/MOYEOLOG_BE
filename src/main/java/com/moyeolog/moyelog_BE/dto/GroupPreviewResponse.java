package com.moyeolog.moyelog_BE.dto;

import lombok.*;

import java.util.UUID;

/** 초대 코드로 가입하기 전에 보여 줄 모임 정보 (GET /api/groups/preview?code=) */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupPreviewResponse {
    private UUID id;
    private String name;
    private String description;
    private String colorTheme;
    private String profileImage;
    private int memberCount;
    private String ownerNickname;
    private boolean alreadyMember;
    /** 요청한 사람이 이 모임에서 강퇴돼 차단 목록에 있으면 true (코드로 가입 불가) */
    private boolean blocked;
}
