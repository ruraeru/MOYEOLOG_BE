package com.moyeolog.moyelog_BE.datepoll;

import com.moyeolog.moyelog_BE.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 날짜 조율에서 한 사람이 가능하다고 고른 날 하나. (조율, 사용자, 날짜) 유일.
 * date 가 NULL 인 줄은 '응답했지만 가능한 날이 없음'이다. 한 사람의 줄은 조율 행을 잠근 채 통째로 바꾸므로
 * NULL 줄이 날짜 줄과 섞이거나 둘 이상 생기지 않는다(MySQL 유일 키는 NULL 중복을 막지 않는다).
 */
@Entity
@Table(name = "date_poll_votes",
        uniqueConstraints = @UniqueConstraint(name = "uk_date_poll_vote", columnNames = {"poll_id", "user_id", "vote_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class DatePollVote {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poll_id", nullable = false)
    private DatePoll poll;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "vote_date")
    private LocalDate date;
}
