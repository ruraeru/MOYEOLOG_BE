package com.moyeolog.moyelog_BE.notice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GroupNoticePreviewTest {

    @Test
    void scriptAndStyleContentIsDropped() {
        String preview = GroupNoticeService.preview("앞 <script>alert(1)</script> 가운데 <STYLE>p{color:red}</STYLE> 뒤");
        assertThat(preview).isEqualTo("앞 가운데 뒤");
    }

    @Test
    void unclosedScriptDropsTheRest() {
        assertThat(GroupNoticeService.preview("보임 <script>alert(1)")).isEqualTo("보임");
    }

    @Test
    void otherTagsKeepText() {
        assertThat(GroupNoticeService.preview("<b>굵게</b> **강조**")).isEqualTo("굵게 강조");
    }
}
