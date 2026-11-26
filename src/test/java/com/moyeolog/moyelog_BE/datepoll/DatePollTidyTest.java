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

    @Test
    @DisplayName("q20-053: 문장 끝 ~습니다/합니다/됩니다/입니다 중 바꿔도 뜻이 같은 꼴만 해요체로")
    void toHaeyo() {
        // 재검증 때 실제로 나온 이유·요약 문장
        assertThat(DatePollService.tidy("11월 29일에 2명이 가능하므로 가장 좋습니다.", List.of()))
                .isEqualTo("11월 29일에 2명이 가능하므로 가장 좋아요.");
        assertThat(DatePollService.tidy("다른 날짜보다 선택의 여지가 있습니다.", List.of()))
                .isEqualTo("다른 날짜보다 선택의 여지가 있어요.");
        assertThat(DatePollService.tidy("가능한 사람이 없어요. 28일에는 0명의 참여자가 있습니다.", List.of()))
                .isEqualTo("가능한 사람이 없어요. 28일에는 0명의 참여자가 있어요.");
        // 받침 ㅆ + 습니다, 규칙 어간, 합니다·됩니다, 입니다(받침에 따라)
        assertThat(DatePollService.toHaeyo("아직 응답하지 않았습니다. 모두 가능하겠습니다! 사람이 많습니다 날짜가 같습니다"))
                .isEqualTo("아직 응답하지 않았어요. 모두 가능하겠어요! 사람이 많아요 날짜가 같아요");
        assertThat(DatePollService.toHaeyo("이 날을 추천합니다. 모두 참석하게 됩니다. 가장 좋은 날입니다. 첫 후보 날짜입니다."))
                .isEqualTo("이 날을 추천해요. 모두 참석하게 돼요. 가장 좋은 날이에요. 첫 후보 날짜예요.");
        assertThat(DatePollService.toHaeyo("가능한 사람이 없습니다. 인원이 적습니다. 응답이 늦습니다."))
                .isEqualTo("가능한 사람이 없어요. 인원이 적어요. 응답이 늦어요.");
        // 불규칙일 수 있는 꼴과 문장 중간은 건드리지 않는다
        assertThat(DatePollService.toHaeyo("거리가 가깝습니다. 모두 갑니다. 있습니다만 어렵습니다."))
                .isEqualTo("거리가 가깝습니다. 모두 갑니다. 있습니다만 어렵습니다.");
        // 이미 해요체면 그대로
        assertThat(DatePollService.toHaeyo("11월 29일이 가장 좋아요.")).isEqualTo("11월 29일이 가장 좋아요.");
    }
}
