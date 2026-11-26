package com.moyeolog.moyelog_BE.datepoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    @DisplayName("q20-053: 멤버 닉네임 속에 끼어든 공백을 원래 닉네임으로 되돌리고 조사를 붙인다")
    void tidyRestoresNames() {
        List<String> names = List.of("큐에이20a", "큐에이20b", "큐에이20c", "민지");
        assertThat(DatePollService.tidy(
                "큐에이 20a 가 아직 답변하지 않았지만, 큐 에이 20b 와 큐에이20c 는 11 월 28 일에 시간이 있어요.", names))
                .isEqualTo("큐에이20a가 아직 답변하지 않았지만, 큐에이20b와 큐에이20c는 11월 28일에 시간이 있어요.");
        // 줄여 부른 이름은 되돌리지 못한다(한계). 짧은 닉네임은 건드리지 않는다
        assertThat(DatePollService.tidy("20c 는 가능해요. 민 지는 아직이에요.", names))
                .isEqualTo("20c는 가능해요. 민 지는 아직이에요.");
        // 공백이 든 닉네임은 원래 모양대로 둔다(한글로 끝나는 이름의 조사 공백은 고치지 않음), 특수문자는 글자 그대로 찾는다
        assertThat(DatePollService.tidy("김민지 와 a. b+c 는 가능해요.", List.of("김 민지", "a.b+c")))
                .isEqualTo("김 민지 와 a.b+c는 가능해요.");
    }
}
