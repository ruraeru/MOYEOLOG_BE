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
    private Double latitude;
    private Double longitude;
    private UUID authorId;
    private String authorNickname;
    private UUID groupId;
    private List<MemoResponse> taggedMemos;
    private List<ParticipantResponse> participants;
    private LocalDateTime createdAt;
    /** 보는 사람이 이 일정에 출발지를 적어야 하는데 아직 안 적었는지 (여럿이 만나고, 장소가 비었고, 끝나지 않은 일정) */
    private boolean departureNeeded;
    /** 보는 사람이 이 일정을 만들었고 아직 장소를 정하지 않았는지 (여럿이 만나고, 끝나지 않은 일정) */
    private boolean placeNeeded;

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
