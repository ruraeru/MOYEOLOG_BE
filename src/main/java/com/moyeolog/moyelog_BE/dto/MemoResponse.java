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
public class MemoResponse {
    private UUID id;
    private String title;
    private String content;
    private String imageUrl;
    private UUID authorId;
    private String authorNickname;
    private String authorProfileImage;
    private UUID lastModifierId;
    private String lastModifierNickname;
    private UUID groupId;
    private List<String> tags;
    private MemoInsightResponse insight;
    private Boolean isFavorite; // 요청한 사용자 기준
    private List<SharedUserResponse> sharedWith; // 작성자에게만 채워짐 (그 외에는 빈 목록)
    private List<UUID> sharedGroupIds; // 이 개인 메모를 공유한 모임 (작성자에게만 채워짐)
    private List<MemoSummaryResponse> taggedMemos;
    private List<ScheduleSummaryResponse> taggedSchedules;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version; // 수정 요청에 그대로 돌려보내면 낙관적 잠금
    private LocalDateTime pinnedAt; // 고정한 시각(없으면 null). 모임 메모는 모임 공통, 개인 메모는 보는 사람 본인의 고정
    private Permissions permissions; // 요청한 사용자 기준

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Permissions {
        private boolean edit;
        private boolean delete;
        private boolean share;
        private boolean tag;
        private boolean analyze;
        private boolean pin; // 고정·해제 (모임 메모: 작성자·방장, 개인 메모: 작성자)
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MemoSummaryResponse {
        private UUID id;
        private String title;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SharedUserResponse {
        private UUID id;
        private String nickname;
        private String profileImage;
        private boolean canEdit;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ScheduleSummaryResponse {
        private UUID id;
        private String title;
    }
}
