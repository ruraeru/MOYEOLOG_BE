package com.moyeolog.moyelog_BE.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupResponse {
    private UUID id;
    private String name;
    private String description;
    private String colorTheme;
    private UUID createdById;
    private String createdByNickname;
    private List<MemberResponse> members;
    private String inviteCode;
    private String profileImage;
    private String backgroundImage;
    private int memberCount;
    private LocalDateTime createdAt;
    /** 요청한 사용자의 역할: "OWNER" | "MEMBER" */
    private String myRole;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MemberResponse {
        private UUID id;
        private String customId;
        private String nickname;
        private String profileImage;
    }
}
