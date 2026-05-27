package com.moyeolog.moyelog_BE.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserUpdateRequest {
    private String nickname;
    private String bio;
}
