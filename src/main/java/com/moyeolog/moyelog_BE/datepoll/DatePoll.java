package com.moyeolog.moyelog_BE.datepoll;

import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 모임 날짜 조율. 모임장이 후보 기간(날짜만)을 열면 멤버가 가능한 날을 고르고, 모임장이 하나를 정하면 모임 일정이 된다.
 * 모임이 지워지면 GroupService 가 선택과 함께 지운다. 만들어진 일정(scheduleId)은 따로 산다(외래 키 아님).
 */
@Entity
@Table(name = "date_polls", indexes = @Index(name = "idx_date_poll_group", columnList = "group_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class DatePoll {

    public enum Status { OPEN, CONFIRMED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @Column(nullable = false, length = 50)
    private String title;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "confirmed_date")
    private LocalDate confirmedDate;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "schedule_id", columnDefinition = "BINARY(16)")
    private UUID scheduleId;

    // AI 추천(마지막 한 번). recommended 는 [{date, reason}] JSON. sourceHash 는 추천에 쓴 선택들의 지문(stale 판단).
    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    @Column(name = "ai_recommended", columnDefinition = "TEXT")
    private String aiRecommended;

    @Column(name = "ai_generated_at")
    private LocalDateTime aiGeneratedAt;

    @Column(name = "ai_source_hash", length = 64)
    private String aiSourceHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public void confirm(LocalDate date, UUID scheduleId) {
        this.status = Status.CONFIRMED;
        this.confirmedDate = date;
        this.confirmedAt = LocalDateTime.now();
        this.scheduleId = scheduleId;
    }

    public void saveAi(String summary, String recommendedJson, String sourceHash) {
        this.aiSummary = summary;
        this.aiRecommended = recommendedJson;
        this.aiSourceHash = sourceHash;
        this.aiGeneratedAt = LocalDateTime.now();
    }

    /** 회원 탈퇴로 만든 사람이 사라질 때 지금 모임장에게 넘긴다. */
    public void changeCreator(User creator) {
        this.creator = creator;
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }
}
