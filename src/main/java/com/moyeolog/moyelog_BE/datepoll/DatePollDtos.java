package com.moyeolog.moyelog_BE.datepoll;

import com.moyeolog.moyelog_BE.validation.CodePointSize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** 날짜 조율 API 의 요청·응답 모양 (DATEPOLL_BRIEF.md '계약'과 같다) */
public final class DatePollDtos {

    private DatePollDtos() {
    }

    public record CreateRequest(
            @NotBlank(message = "조율 제목을 입력해 주세요.")
            @CodePointSize(max = 50, message = "조율 제목은 50자까지 쓸 수 있어요.")
            String title,
            @NotNull(message = "시작 날짜를 골라 주세요.")
            LocalDate startDate,
            @NotNull(message = "끝 날짜를 골라 주세요.")
            LocalDate endDate) {
    }

    /** 내 선택을 통째로 바꾼다. 빈 배열은 '가능한 날 없음'으로 응답한 것이다. */
    public record VotesRequest(
            @NotNull(message = "고른 날짜를 보내 주세요.")
            List<@NotNull(message = "날짜가 올바르지 않아요.") LocalDate> dates) {
    }

    public record ConfirmRequest(
            @NotNull(message = "정할 날짜를 골라 주세요.")
            LocalDate date,
            @NotNull(message = "시작 시각을 입력해 주세요.")
            LocalTime startTime,
            LocalTime endTime) {
    }

    public record PollUser(UUID id, String nickname, String profileImage) {
    }

    public record DatePollSummary(UUID id, String title, LocalDate startDate, LocalDate endDate,
                                  DatePoll.Status status, int respondedCount, int memberCount, boolean myResponded,
                                  LocalDate confirmedDate, UUID scheduleId, LocalDateTime createdAt) {
    }

    public record DateTally(LocalDate date, int count, List<PollUser> voters) {
    }

    public record Recommendation(LocalDate date, String reason) {
    }

    public record Ai(String summary, List<Recommendation> recommended, LocalDateTime generatedAt, boolean stale) {
    }

    /** DatePollSummary 의 필드 + 상세 */
    public record DatePollDetail(UUID id, String title, LocalDate startDate, LocalDate endDate,
                                 DatePoll.Status status, int respondedCount, int memberCount, boolean myResponded,
                                 LocalDate confirmedDate, UUID scheduleId, LocalDateTime createdAt,
                                 PollUser creator, List<LocalDate> myDates, List<DateTally> dates, List<LocalDate> best,
                                 List<PollUser> responded, List<PollUser> notResponded, Ai ai) {
    }
}
