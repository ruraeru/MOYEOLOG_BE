package com.moyeolog.moyelog_BE.dto;

import lombok.*;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FriendResponse {
    private UUID id; // 관계 ID (삭제 시 필요)
    private UUID userId; // 상대방 유저 ID
    private String customId;
    private String nickname;
    private String email;
    private String profileImage;
    private String status; // PENDING, ACCEPTED
}
