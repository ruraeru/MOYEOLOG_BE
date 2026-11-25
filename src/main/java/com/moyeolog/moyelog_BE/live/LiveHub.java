package com.moyeolog.moyelog_BE.live;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 사용자별 실시간 연결(SSE) 등록부. 앱 인스턴스가 하나라 메모리에 둔다.
 * - 연결은 서블릿 비동기 요청이라 요청 스레드를 붙잡지 않는다.
 * - 보내기·닫기는 연결마다 가상 스레드에서 한다. SseEmitter 의 send·complete 는 연결별 writeLock 을 잡고 그 안에서 소켓에 쓰므로,
 *   읽지 않는 클라이언트 하나에 쓰기가 막혀도 그 연결의 작업만 기다린다(다른 연결·하트비트·API 요청 스레드는 막히지 않는다).
 * - 사용자당 연결 수 상한을 넘으면 가장 오래된 연결에 evicted 를 보내고 닫는다.
 * - 연결은 maxAge(기본 30분) 또는 액세스 토큰 만료 중 이른 때에 서버가 닫는다. 클라이언트가 새 토큰으로 다시 붙는다.
 */
@Component
@Slf4j
public class LiveHub {

    /** 연결 직후 한 번 보내는 이벤트(헤더를 바로 내보내고, 클라이언트가 '붙었다'를 안다). */
    static final String READY_EVENT = "ready";
    static final String CHANGED_EVENT = "changed";
    /** 사용자당 상한을 넘어 서버가 닫기 직전에 보내는 이벤트(클라이언트는 한동안 다시 붙지 않는다). */
    static final String EVICTED_EVENT = "evicted";

    private final int maxPerUser;
    private final long maxAgeMs;
    /** 모든 접근은 connections 자체로 잠근다(연결 수가 적어 잠금 하나로 충분하다). */
    private final Map<UUID, Deque<Connection>> connections = new HashMap<>();
    /** 앱이 내려가는 중이면 새 연결을 받자마자 닫는다(graceful shutdown 이 기다리지 않게). */
    private volatile boolean closed;
    /** 하트비트 시각만 잰다(실제 보내기는 sender). */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "live-heartbeat");
        t.setDaemon(true);
        return t;
    });
    /** 보내기·닫기 작업마다 가상 스레드 하나 */
    private final ExecutorService sender =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("live-send-", 0).factory());

    /** 한 연결. jti 는 연 토큰의 ID(로그아웃한 기기의 연결만 닫을 때 쓴다, 없을 수 있음). */
    record Connection(UUID userId, String jti, SseEmitter emitter) {
    }

    public LiveHub(@Value("${live.max-connections-per-user:6}") int maxPerUser,
                   @Value("${live.max-age-ms:1800000}") long maxAgeMs,
                   @Value("${live.heartbeat-ms:25000}") long heartbeatMs) {
        this.maxPerUser = Math.max(1, maxPerUser);
        this.maxAgeMs = maxAgeMs;
        scheduler.scheduleWithFixedDelay(this::heartbeat, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 연결을 등록한다.
     *
     * @param lifetimeMs 이 연결이 살아 있을 최대 시간(토큰 만료까지). maxAge 보다 길면 maxAge.
     */
    public SseEmitter open(UUID userId, String jti, long lifetimeMs) {
        SseEmitter emitter = new SseEmitter(Math.max(1000L, Math.min(maxAgeMs, lifetimeMs)));
        if (closed) {
            emitter.complete();
            return emitter;
        }
        Connection connection = new Connection(userId, jti, emitter);
        // 수명이 끝나면 정상 종료(200 으로 스트림 끝). complete 하지 않으면 MVC 가 시간 초과 오류로 처리한다.
        emitter.onTimeout(emitter::complete);
        emitter.onCompletion(() -> remove(connection));
        emitter.onError(e -> remove(connection));

        List<Connection> evicted = new ArrayList<>();
        synchronized (connections) {
            Deque<Connection> deque = connections.computeIfAbsent(userId, id -> new ArrayDeque<>());
            deque.addLast(connection);
            while (deque.size() > maxPerUser) {
                evicted.add(deque.pollFirst());
            }
        }
        evicted.forEach(old -> run(() -> {
            send(old, SseEmitter.event().name(EVICTED_EVENT).data("{}"));
            old.emitter().complete();
        }));

        try {
            // 핸들러가 끝나기 전 보내기는 SseEmitter 가 모아 뒀다가 응답이 준비되면 내보낸다.
            emitter.send(SseEmitter.event().name(READY_EVENT).data("{}"));
        } catch (IOException | IllegalStateException e) {
            remove(connection);
        }
        return emitter;
    }

    /** 받는 사람의 모든 연결에 '바뀐 토픽'을 보낸다(비동기). */
    public void publish(Collection<UUID> recipients, Collection<String> topics) {
        if (recipients.isEmpty() || topics.isEmpty()) return;
        String data = toJson(topics);
        for (UUID userId : recipients) {
            for (Connection connection : snapshot(userId)) {
                run(() -> send(connection, SseEmitter.event().name(CHANGED_EVENT).data(data)));
            }
        }
    }

    /** 사용자의 연결을 닫는다. jti 가 있으면 그 토큰으로 연 연결만. */
    public void close(UUID userId, String jti) {
        for (Connection connection : snapshot(userId)) {
            if (jti == null || jti.equals(connection.jti())) {
                remove(connection);
                run(connection.emitter()::complete);
            }
        }
    }

    /** 지금 열린 연결 수(테스트·운영 확인용) */
    public int connectionCount(UUID userId) {
        return snapshot(userId).size();
    }

    /**
     * 앱이 내려갈 때 열린 연결을 먼저 닫는다. 열린 스트림은 '진행 중 요청'이라 Tomcat graceful shutdown 이 끝나기를 기다리다
     * docker stop 시간(10초)을 넘겨 강제 종료됐다. ContextClosedEvent 는 graceful shutdown 보다 먼저 온다.
     */
    @EventListener(ContextClosedEvent.class)
    @PreDestroy
    void shutdown() {
        closed = true;
        scheduler.shutdownNow();
        List<Connection> all;
        synchronized (connections) {
            all = connections.values().stream().flatMap(Collection::stream).toList();
            connections.clear();
        }
        all.forEach(c -> run(c.emitter()::complete));
        sender.shutdown();
        try {
            // 보통은 바로 끝난다. 쓰기가 막힌 연결이 있어도 종료를 오래 붙잡지 않는다.
            sender.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void heartbeat() {
        List<Connection> all;
        synchronized (connections) {
            all = connections.values().stream().flatMap(Collection::stream).toList();
        }
        for (Connection connection : all) {
            // 주석 줄 ": ping" (SSE 클라이언트는 무시하고, 중간 프록시는 연결이 살아 있다고 본다)
            run(() -> send(connection, SseEmitter.event().comment(" ping")));
        }
    }

    private void run(Runnable task) {
        try {
            sender.execute(task);
        } catch (RejectedExecutionException e) {
            // 앱이 내려가는 중
        }
    }

    private void send(Connection connection, SseEmitter.SseEventBuilder event) {
        try {
            connection.emitter().send(event);
        } catch (IOException | IllegalStateException e) {
            // 끊긴 연결. 정리는 컨테이너의 오류 알림(onError)이 하지만, 등록부에서는 바로 뺀다.
            remove(connection);
        }
    }

    private void remove(Connection connection) {
        synchronized (connections) {
            Deque<Connection> deque = connections.get(connection.userId());
            if (deque == null) return;
            deque.remove(connection);
            if (deque.isEmpty()) connections.remove(connection.userId());
        }
    }

    private List<Connection> snapshot(UUID userId) {
        synchronized (connections) {
            Deque<Connection> deque = connections.get(userId);
            return deque == null ? List.of() : List.copyOf(deque);
        }
    }

    /** 토픽은 서버가 정한 이름과 UUID 뿐이라 이스케이프할 글자가 없다. */
    static String toJson(Collection<String> topics) {
        return topics.stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(",", "{\"topics\":[", "]}"));
    }
}
