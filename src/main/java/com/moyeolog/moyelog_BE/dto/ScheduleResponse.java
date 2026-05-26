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
public class ScheduleResponse {
    private UUID id;
    private String title;
    private String description;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String location;
    private UUID authorId;
    private String authorNickname;
    private UUID groupId;
    private List<MemoResponse> taggedMemos;
    private List<ParticipantResponse> participants;
    private LocalDateTime createdAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ParticipantResponse {
        private UUID id;
        private String nickname;
        private String profileImage;
    }
}
