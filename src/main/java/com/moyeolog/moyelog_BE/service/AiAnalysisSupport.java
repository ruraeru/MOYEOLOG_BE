package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.MemoInsightResponse;
import com.moyeolog.moyelog_BE.event.UserDeletingEvent;
import com.moyeolog.moyelog_BE.exception.ConflictException;
import com.moyeolog.moyelog_BE.repository.GroupTopicInsightRepository;
import com.moyeolog.moyelog_BE.repository.MemoAiInsightRepository;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 메모·토픽 AI 분석의 공통 흐름.
 * <ol>
 *   <li>짧은 읽기 트랜잭션: 권한 확인 후 분석할 값(본문·이미지 주소)만 꺼낸다.</li>
 *   <li>트랜잭션 밖: LLM 호출 (최대 150초 이상). DB 커넥션을 잡지 않는다.</li>
 *   <li>짧은 쓰기 트랜잭션: 결과 저장.</li>
 * </ol>
 * 같은 대상에 분석이 진행 중이면 두 번째 요청은 LLM 을 부르지 않고 409.
 * (백엔드는 인스턴스 하나로 돌므로 메모리 안 잠금으로 충분하다.)
 */
@Component
@Slf4j
public class AiAnalysisSupport {

    public static final String IN_PROGRESS_MESSAGE = "이미 분석하고 있어요. 잠시 후 다시 확인해 주세요.";

    /** 분석에 넣을 값. 엔티티가 아니라 값이라서 트랜잭션이 끝난 뒤에도 쓸 수 있다. */
    public record Source(String id, String content, String imageUrl) {
    }

    private final Set<String> inProgress = ConcurrentHashMap.newKeySet();
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;
    private final EntityManagerFactory entityManagerFactory;
    private final GeminiService geminiService;
    private final MemoAiInsightRepository memoAiInsightRepository;
    private final GroupTopicInsightRepository groupTopicInsightRepository;

    public AiAnalysisSupport(PlatformTransactionManager transactionManager,
                             EntityManagerFactory entityManagerFactory,
                             GeminiService geminiService,
                             MemoAiInsightRepository memoAiInsightRepository,
                             GroupTopicInsightRepository groupTopicInsightRepository) {
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
        this.entityManagerFactory = entityManagerFactory;
        this.geminiService = geminiService;
        this.memoAiInsightRepository = memoAiInsightRepository;
        this.groupTopicInsightRepository = groupTopicInsightRepository;
    }

    /**
     * @param key   진행 중 중복을 막을 키 (예: "memo:{id}")
     * @param read  읽기 트랜잭션 안에서 실행: 권한 확인 후 Source 반환 (예외는 그대로 전파)
     * @param write 쓰기 트랜잭션 안에서 실행: 분석 결과(ocrText/summary/keywords)와 Source 로 저장 후 응답 반환
     */
    public MemoInsightResponse run(String key, Supplier<Source> read,
                                   Function<Analyzed, MemoInsightResponse> write) {
        return run(key, read,
                source -> geminiService.analyze(source.content(), source.imageUrl(), source.id()),
                (source, result) -> write.apply(new Analyzed(source, result)));
    }

    /**
     * 같은 흐름의 일반형(날짜 조율 추천 등): read(읽기 트랜잭션) → call(트랜잭션 밖, LLM) → write(쓰기 트랜잭션).
     * 같은 key 가 진행 중이면 409.
     */
    public <S, R, T> T run(String key, Supplier<S> read, Function<S, R> call, BiFunction<S, R, T> write) {
        // Open-in-View 가 요청 내내 붙잡아 두는 EntityManager 를 잠시 떼어 낸다.
        // 그대로 두면 첫 트랜잭션에서 얻은 커넥션을 요청이 끝날 때까지(LLM 대기 포함) 반납하지 않는다.
        Object suspended = null;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            suspended = TransactionSynchronizationManager.unbindResourceIfPossible(entityManagerFactory);
        }
        try {
            S source = readTx.execute(status -> read.get());
            if (!inProgress.add(key)) {
                throw new ConflictException(IN_PROGRESS_MESSAGE);
            }
            try {
                R result = call.apply(source);
                return writeTx.execute(status -> write.apply(source, result));
            } finally {
                inProgress.remove(key);
            }
        } finally {
            if (suspended != null) {
                TransactionSynchronizationManager.bindResource(entityManagerFactory, suspended);
            }
        }
    }

    /** 분석한 값과 결과 */
    public record Analyzed(Source source, Map<String, Object> result) {
        public String ocrText() {
            return text("ocrText");
        }

        public String summary() {
            return text("summary");
        }

        public java.util.List<String> keywords() {
            return GeminiService.toKeywords(result != null ? result.get("keywords") : null);
        }

        /** 분석에 쓴 본문·이미지의 지문. 나중에 내용이 바뀌었는지(stale) 판단할 때 쓴다. */
        public String sourceHash() {
            return fingerprint(source.content(), source.imageUrl());
        }

        private String text(String key) {
            Object value = result != null ? result.get(key) : null;
            return value != null ? value.toString() : "";
        }
    }

    /** 본문과 이미지 주소의 SHA-256 (hex) */
    public static String fingerprint(String content, String imageUrl) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((content != null ? content : "").getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update((imageUrl != null ? imageUrl : "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 분석 뒤 대상이 바뀌었는지. 지문이 있으면 지문으로, 예전 결과(지문 없음)는 수정 시각으로 판단한다.
     */
    public static boolean isStale(String sourceHash, LocalDateTime analyzedAt,
                                  String content, String imageUrl, LocalDateTime updatedAt) {
        if (sourceHash != null) {
            return !sourceHash.equals(fingerprint(content, imageUrl));
        }
        return analyzedAt != null && updatedAt != null && updatedAt.isAfter(analyzedAt);
    }

    /**
     * 회원 탈퇴: 그 사용자가 쓴 메모·토픽(그리고 혼자 남은 모임의 메모·토픽)의 분석 결과를 지운다.
     * 메모·토픽 행을 지우는 다른 리스너보다 먼저 돌아야 외래 키에 걸리지 않으므로 가장 먼저 실행한다.
     */
    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @Transactional
    public void onUserDeleting(UserDeletingEvent event) {
        UUID userId = event.userId();
        int memos = memoAiInsightRepository.deleteAllForUserDeletion(userId);
        int topics = groupTopicInsightRepository.deleteAllForUserDeletion(userId);
        log.info("[AI] 회원 탈퇴로 분석 결과 삭제: 메모 {}건, 토픽 {}건", memos, topics);
    }
}
