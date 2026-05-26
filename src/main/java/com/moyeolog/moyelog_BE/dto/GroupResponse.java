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
    private String createdByNickname;
    private List<MemberResponse> members;
    private String inviteCode;
    private String profileImage;
    private String backgroundImage;
    private int memberCount;
    private LocalDateTime createdAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MemberResponse {
        private UUID id;
        private String nickname;
        private String profileImage;
    }
}
