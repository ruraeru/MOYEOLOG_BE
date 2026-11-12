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
    @NotBlank(message = "친구 ID는 필수입니다.")
    @Size(max = 20, message = "친구 ID가 올바르지 않습니다.")
    private String customId;
}
