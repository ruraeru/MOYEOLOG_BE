package com.moyeolog.moyelog_BE.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/** 다른 사용자 프로필 (GET /api/users/{id}, /api/users/by-custom-id/{customId}). 이메일은 담지 않는다. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfileResponse {

    /** NONE | PENDING_SENT | PENDING_RECEIVED | FRIEND | SELF */
    public enum FriendStatus { NONE, PENDING_SENT, PENDING_RECEIVED, FRIEND, SELF }

    public record MutualGroup(UUID id, String name) {
    }

    private UUID id;
    private String customId;
    private String nickname;
    private String profileImage;
    private String bio;
    private FriendStatus friendStatus;
    private List<MutualGroup> mutualGroups;
}
