package com.moyeolog.moyelog_BE.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FriendRequestDto {
    @NotBlank(message = "친구 ID를 입력해 주세요.")
    @Size(max = 20, message = "친구 ID가 올바르지 않아요.")
    private String customId;
}
