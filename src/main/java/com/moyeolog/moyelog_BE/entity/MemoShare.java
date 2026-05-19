package com.moyeolog.moyelog_BE.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "memo_shares")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class MemoShare {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "memo_id", nullable = false)
    private Memo memo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shared_to_id", nullable = false)
    private User sharedTo;

    @Column(name = "shared_at", updatable = false)
    private LocalDateTime sharedAt;

    @PrePersist
    protected void onShare() {
        sharedAt = LocalDateTime.now();
    }
}
