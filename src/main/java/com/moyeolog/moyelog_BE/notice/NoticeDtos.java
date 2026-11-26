package com.moyeolog.moyelog_BE.notice;

import com.moyeolog.moyelog_BE.validation.CodePointSize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 모임 공지 API 의 요청·응답 모양 (NOTICE_BRIEF.md·계획서 '계약'과 같다) */
public final class NoticeDtos {

    private NoticeDtos() {
    }

    public record NoticeUser(UUID id, String nickname, String profileImage) {
    }

    /** 선택지 개수·길이·중복·마감 시각은 GroupNoticePollService.createPoll 이 검사한다. */
    public record PollRequest(
            @NotNull(message = "투표 선택지를 보내 주세요.")
            List<@NotNull(message = "선택지가 올바르지 않아요.") String> options,
            boolean multiple,
            boolean anonymous,
            LocalDateTime closesAt) {
    }

    public record CreateRequest(
            @NotBlank(message = "공지 제목을 입력해 주세요.")
            @CodePointSize(max = 100, message = "공지 제목은 100자까지 쓸 수 있어요.")
            String title,
            @NotNull(message = "공지 내용을 보내 주세요.")
            @CodePointSize(max = 100000, message = "공지 내용은 100,000자까지 쓸 수 있어요.")
            String content,
            @Valid
            PollRequest poll) {
    }

    public record UpdateRequest(
            @NotBlank(message = "공지 제목을 입력해 주세요.")
            @CodePointSize(max = 100, message = "공지 제목은 100자까지 쓸 수 있어요.")
            String title,
            @NotNull(message = "공지 내용을 보내 주세요.")
            @CodePointSize(max = 100000, message = "공지 내용은 100,000자까지 쓸 수 있어요.")
            String content) {
    }

    /** 내 선택을 통째로 바꾼다. 빈 배열은 투표 취소다. */
    public record VoteRequest(
            @NotNull(message = "고른 선택지를 보내 주세요.")
            List<@NotNull(message = "선택지가 올바르지 않아요.") UUID> optionIds) {
    }

    /** 익명이면 voters 는 빈 목록 */
    public record PollOptionResponse(UUID id, String text, int count, boolean mine, List<NoticeUser> voters) {
    }

    /** voterCount: 투표한 사람 수. 익명이면 notVoted 는 빈 목록 */
    public record PollResponse(boolean multiple, boolean anonymous, LocalDateTime closesAt, boolean closed,
                               int voterCount, List<PollOptionResponse> options, List<NoticeUser> notVoted) {
    }

    /**
     * preview: 내용 앞 100자(마크다운 기호 제거). read: 보는 사람이 읽었나. canManage: 보는 사람이 방장.
     * hasPoll·pollClosed·myVoted: 투표가 없으면 모두 false.
     */
    public record NoticeSummary(UUID id, String title, String preview, NoticeUser author,
                                LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime pinnedAt,
                                int readCount, int memberCount, boolean read,
                                boolean hasPoll, boolean pollClosed, boolean myVoted,
                                boolean canManage) {
    }

    /** NoticeSummary 의 필드 + 상세. poll 은 투표가 없으면 null */
    public record NoticeDetail(UUID id, String title, String preview, NoticeUser author,
                               LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime pinnedAt,
                               int readCount, int memberCount, boolean read,
                               boolean hasPoll, boolean pollClosed, boolean myVoted,
                               boolean canManage,
                               String content, List<NoticeUser> readers, List<NoticeUser> notReaders,
                               PollResponse poll) {
    }
}
