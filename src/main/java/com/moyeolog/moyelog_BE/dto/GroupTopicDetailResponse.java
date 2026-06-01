package com.moyeolog.moyelog_BE.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupTopicDetailResponse {
    private GroupTopicResponse topic;
    private List<GroupTopicCommentResponse> comments;
    private MemoInsightResponse insight; // Reuse MemoInsightResponse for simplicity
}
