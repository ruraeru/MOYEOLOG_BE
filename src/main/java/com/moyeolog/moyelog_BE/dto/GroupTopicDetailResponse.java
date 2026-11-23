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
    /** 토픽 작성자 customId (topic.authorCustomId 와 같음, 없으면 null) */
    private String authorCustomId;
    private MemoInsightResponse insight; // Reuse MemoInsightResponse for simplicity
}
