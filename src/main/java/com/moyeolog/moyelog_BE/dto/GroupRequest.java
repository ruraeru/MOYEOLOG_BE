package com.moyeolog.moyelog_BE.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupRequest {
    @NotBlank(message = "그룹 이름은 필수입니다.")
    @Size(max = 50, message = "그룹 이름은 50자를 초과할 수 없습니다.")
    private String name;
    
    @Size(max = 255, message = "설명은 255자를 초과할 수 없습니다.")
    private String description;
    
    private String colorTheme;
}
