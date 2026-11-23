package com.moyeolog.moyelog_BE.dto;

import com.moyeolog.moyelog_BE.validation.CodePointSize;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupTopicCommentRequest {
    @NotBlank(message = "댓글 내용을 입력해 주세요.")
    @CodePointSize(max = 10000, message = "댓글은 10,000자까지 쓸 수 있어요.")
    private String content;
}
