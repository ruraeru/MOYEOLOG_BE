package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.exception.ConflictException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import org.hibernate.StaleStateException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 동시에 들어온 쓰기가 DB 에서 부딪혔을 때(낙관적 잠금 실패, 데드락, 유니크 제약 위반) 500 대신 409 로 바꾼다.
 * 반드시 flush 를 감싸야 효과가 있다 (커밋 시점까지 미루면 서비스 밖에서 터진다).
 */
final class ConcurrentUpdates {

    static final String CONFLICT_MESSAGE = "다른 곳에서 먼저 바뀌었어요. 새로고침 후 다시 시도해 주세요.";

    private ConcurrentUpdates() {
    }

    static void flush(Runnable flush) {
        flush(flush, CONFLICT_MESSAGE);
    }

    static void flush(Runnable flush, String conflictMessage) {
        try {
            flush.run();
        } catch (ConcurrencyFailureException | DataIntegrityViolationException
                 | OptimisticLockException | PessimisticLockException | StaleStateException e) {
            throw new ConflictException(conflictMessage);
        }
    }
}
