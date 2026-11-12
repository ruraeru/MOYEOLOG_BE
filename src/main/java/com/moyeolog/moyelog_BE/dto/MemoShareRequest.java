package com.moyeolog.moyelog_BE.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.*;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoShareRequest {
    @NotEmpty(message = "공유할 친구를 한 명 이상 선택해주세요.")
    private List<UUID> friendIds;
}
