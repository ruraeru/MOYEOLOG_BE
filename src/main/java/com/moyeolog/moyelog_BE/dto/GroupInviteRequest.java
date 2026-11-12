package com.moyeolog.moyelog_BE.dto;

import java.util.UUID;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupInviteRequest {
    // 둘 다 선택 사항이지만 최소 하나는 비어 있지 않아야 합니다. userIds(사용자 UUID) 사용을 권장합니다.
    private List<String> emails;
    private List<UUID> userIds;
}
