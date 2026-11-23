package com.moyeolog.moyelog_BE.dto;

import lombok.*;

import java.util.UUID;

/** POST /api/groups/join?code= 응답 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupJoinResponse {
    private UUID groupId;
}
