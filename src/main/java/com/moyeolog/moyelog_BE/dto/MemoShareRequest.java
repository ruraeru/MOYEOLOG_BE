package com.moyeolog.moyelog_BE.dto;

import lombok.*;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoShareRequest {
    private List<UUID> friendIds;
}
