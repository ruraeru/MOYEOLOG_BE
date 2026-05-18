package com.moyeolog.moyelog_BE.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupRequest {
    private String name;
    private String description;
    private String colorTheme;
}
