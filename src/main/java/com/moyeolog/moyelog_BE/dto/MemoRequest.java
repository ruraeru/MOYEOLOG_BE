package com.moyeolog.moyelog_BE.dto;

import lombok.*;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoRequest {
    private String title;
    private String content;
    private String imageUrl;
    private UUID groupId;
    private java.util.List<String> tags;
}
