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
    @NotEmpty(message = "공유할 친구를 한 명 이상 선택해 주세요.")
    private List<UUID> friendIds;

    /** 공유받은 사람이 메모를 고칠 수 있게 할지 (없으면 false = 읽기 전용). 이미 공유한 사람에게 다시 보내면 이 값으로 바뀐다. */
    private Boolean canEdit;
}
