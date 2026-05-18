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
    private List<String> memberNicknames;
    private int memberCount;
    private LocalDateTime createdAt;
}
