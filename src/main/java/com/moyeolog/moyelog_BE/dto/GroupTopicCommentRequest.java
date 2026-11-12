package com.moyeolog.moyelog_BE.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupTopicCommentRequest {
    @NotBlank(message = "댓글 내용은 필수입니다.")
    @Size(max = 10000, message = "댓글은 10,000자를 초과할 수 없습니다.")
    private String content;
}
