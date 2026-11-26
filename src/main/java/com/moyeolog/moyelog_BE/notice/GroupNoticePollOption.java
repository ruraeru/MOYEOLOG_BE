package com.moyeolog.moyelog_BE.notice;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.UUID;

/** 투표의 선택지 하나. position 은 만든 순서(0부터). */
@Entity
@Table(name = "group_notice_poll_options", indexes = @Index(name = "idx_group_notice_poll_option_poll", columnList = "poll_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class GroupNoticePollOption {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poll_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private GroupNoticePoll poll;

    // 칸 이름은 DB 예약어(TEXT, POSITION)를 피한다.
    @Column(name = "option_text", nullable = false, length = 100)
    private String text;

    @Column(name = "sort_order", nullable = false)
    private int position;
}
