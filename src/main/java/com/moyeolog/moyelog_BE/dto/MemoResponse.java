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
    private List<MemoSummaryResponse> taggedMemos;
    private List<ScheduleSummaryResponse> taggedSchedules;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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
