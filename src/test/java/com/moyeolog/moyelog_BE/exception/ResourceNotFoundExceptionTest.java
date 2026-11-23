package com.moyeolog.moyelog_BE.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceNotFoundExceptionTest {

    @Test
    void messagesAreFriendlyAndNeverContainTheIdentifier() {
        assertThat(new ResourceNotFoundException("사용자", "zzzz9999").getMessage()).isEqualTo("사용자를 찾을 수 없어요.");
        assertThat(new ResourceNotFoundException("메모", 1).getMessage()).isEqualTo("메모를 찾을 수 없어요.");
        assertThat(new ResourceNotFoundException("일정", 1).getMessage()).isEqualTo("일정을 찾을 수 없어요.");
        assertThat(new ResourceNotFoundException("그룹", 1).getMessage()).isEqualTo("모임을 찾을 수 없어요.");
        assertThat(new ResourceNotFoundException("초대 코드", "A".repeat(5000)).getMessage())
                .isEqualTo("초대 코드가 맞지 않거나 만료됐어요.");
        assertThat(new ResourceNotFoundException("친구 관계", "x").getMessage()).doesNotContain("x").doesNotContain("을(를)");
    }
}
