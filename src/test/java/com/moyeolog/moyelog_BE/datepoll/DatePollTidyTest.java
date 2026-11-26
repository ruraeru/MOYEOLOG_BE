package com.moyeolog.moyelog_BE.datepoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatePollTidyTest {

    @Test
    @DisplayName("AI 문장의 숫자·단위, 이름·조사 사이 공백을 붙인다")
    void tidy() {
        assertThat(DatePollService.tidy("큐에이20b 는 아직 응답하지 않았어요. 11 월 29 일과 12 월 1 일에 2 명이 가능해요."))
                .isEqualTo("큐에이20b는 아직 응답하지 않았어요. 11월 29일과 12월 1일에 2명이 가능해요.");
        // 이미 바른 문장은 그대로 둔다
        assertThat(DatePollService.tidy("민지는 11월 29일에 가능해요."))
                .isEqualTo("민지는 11월 29일에 가능해요.");
    }
}
