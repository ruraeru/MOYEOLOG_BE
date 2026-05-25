package com.moyeolog.moyelog_BE.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupInviteRequest {
    private List<String> emails;
}
