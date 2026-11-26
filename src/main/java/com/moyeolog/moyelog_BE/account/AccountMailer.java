package com.moyeolog.moyelog_BE.account;

import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.HtmlUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 계정 메일(텍스트 + 단순 HTML, 링크 하나). 커밋 뒤에 별도 스레드에서 보낸다.
 * - 응답 시간이 '메일을 보냈는지(=계정이 있는지)'에 따라 달라지지 않게 하고,
 * - 롤백된 토큰이 담긴 메일이 나가지 않게 한다.
 * 로그에 받는 주소·토큰·본문을 남기지 않는다.
 */
@Component
@Slf4j
public class AccountMailer {

    static final int MAX_PER_HOUR = 200;

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    // shortcut: 전체 상한을 앱 메모리에 둔다(재시작하면 초기화, 인스턴스 하나 기준). 여러 대로 늘리면 공유 저장소로 옮긴다.
    private final Deque<Long> sentTimes = new ArrayDeque<>();
    private final String mailHost;
    private final String from;
    private final String fallbackFrom;
    private final String appUrl;
    // shortcut: 메일은 한 통씩 차례로 보낸다(SMTP 가 느리면 대기열이 길어짐). 발송량이 늘면 스레드 수를 늘린다.
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "account-mail");
        t.setDaemon(true);
        return t;
    });

    public AccountMailer(ObjectProvider<JavaMailSender> mailSenderProvider,
                         @Value("${spring.mail.host:}") String mailHost,
                         @Value("${mail.from:}") String from,
                         @Value("${spring.mail.username:}") String fallbackFrom,
                         @Value("${app.url:https://moyeolog.ruraeru.xyz}") String appUrl) {
        this.mailSenderProvider = mailSenderProvider;
        this.mailHost = mailHost;
        this.from = from;
        this.fallbackFrom = fallbackFrom;
        this.appUrl = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    /** 메일 서버 설정이 없으면 503. 메일이 필요한 요청은 다른 일을 하기 전에 이것부터 부른다. */
    public void requireAvailable() {
        if (!available()) {
            throw new MailUnavailableException();
        }
    }

    private boolean available() {
        return mailHost != null && !mailHost.isBlank() && mailSenderProvider.getIfAvailable() != null;
    }

    /** 전체 발송 상한(시간당 MAX_PER_HOUR 통). 임의 주소로 대량 요청해 SMTP 한도를 소진시키는 것을 막는다. */
    private boolean underGlobalCap() {
        long now = System.currentTimeMillis();
        synchronized (sentTimes) {
            while (!sentTimes.isEmpty() && now - sentTimes.peekFirst() >= 3_600_000L) {
                sentTimes.pollFirst();
            }
            if (sentTimes.size() >= MAX_PER_HOUR) {
                return false;
            }
            sentTimes.addLast(now);
            return true;
        }
    }

    public void sendSignupVerify(String to, String token) {
        queue(to, "[모여로그] 이메일 주소를 확인해 주세요",
                "모여로그 가입을 마치려면 아래 링크를 열고 '인증하기' 버튼을 눌러 주세요. 링크는 30분 동안 쓸 수 있어요.",
                link("/verify", token, "signup"),
                "가입을 요청하지 않았다면 이 메일을 무시해 주세요.");
    }

    public void sendEmailVerify(String to, String token) {
        queue(to, "[모여로그] 새 이메일 주소를 확인해 주세요",
                "모여로그 계정에 이 이메일 주소를 등록하려면 아래 링크를 열고 '인증하기' 버튼을 눌러 주세요. 링크는 30분 동안 쓸 수 있어요.",
                // 인증 화면이 어떤 주소로 바꾸는지 보여 주도록 주소도 붙인다(받는 사람 자신의 주소라 새로 드러나는 것 없음)
                link("/verify", token, "email") + "&email=" + URLEncoder.encode(to, StandardCharsets.UTF_8),
                "요청하지 않았다면 이 메일을 무시해 주세요. 계정의 이메일은 바뀌지 않아요.");
    }

    public void sendPasswordReset(String to, String token) {
        queue(to, "[모여로그] 비밀번호 설정 링크",
                "아래 링크에서 새 비밀번호를 설정할 수 있어요. 링크는 30분 동안 쓸 수 있어요. 비밀번호를 바꾸면 모든 기기에서 로그아웃돼요.",
                link("/reset-password", token, null),
                "요청하지 않았다면 이 메일을 무시해 주세요. 비밀번호는 바뀌지 않아요.");
    }

    public void sendAlreadyRegistered(String to, String token) {
        queue(to, "[모여로그] 이미 가입된 이메일이에요",
                "이 이메일로 가입된 모여로그 계정이 이미 있어요. 비밀번호를 모르거나 아직 설정하지 않았다면 아래 링크에서 설정할 수 있어요. 링크는 30분 동안 쓸 수 있어요.",
                link("/reset-password", token, null),
                "요청하지 않았다면 이 메일을 무시해 주세요. 계정은 바뀌지 않아요.");
    }

    /** 가입·비밀번호 찾기를 한 이메일이 미인증 상태로 카카오 계정에 있을 때(링크 없음). */
    public void sendKakaoAccountNotice(String to) {
        queue(to, "[모여로그] 카카오로 가입한 계정이 있어요",
                "이 이메일은 카카오로 가입한 계정에 등록돼 있어요. 카카오로 로그인하면 이메일이 확인되고, 설정의 계정 및 보안에서 비밀번호를 설정할 수 있어요.",
                null,
                "요청하지 않았다면 이 메일을 무시해 주세요.");
    }

    /** 비밀번호가 설정·변경됐을 때 계정의 인증된 이메일로(링크 없음). */
    public void sendPasswordChangedNotice(String to) {
        queueSecurityNotice(to, "[모여로그] 비밀번호가 설정됐어요",
                "모여로그 계정의 비밀번호가 방금 설정되거나 바뀌었어요.",
                null,
                "본인이 아니라면 카카오로 로그인해 설정의 '계정 및 보안'을 확인하거나, 로그인 화면의 '비밀번호를 잊었어요'로 비밀번호를 다시 설정해 주세요.");
    }

    /** 이메일이 바뀌었을 때 예전 인증된 이메일로(링크 없음). */
    public void sendEmailChangedNotice(String oldTo) {
        queueSecurityNotice(oldTo, "[모여로그] 계정 이메일이 바뀌었어요",
                "모여로그 계정의 이메일이 다른 주소로 바뀌었어요. 이 주소로는 더 이상 로그인 안내 메일이 가지 않아요.",
                null,
                "본인이 아니라면 카카오로 로그인해 설정의 '계정 및 보안'을 확인하거나, 비밀번호를 다시 설정해 주세요.");
    }

    private String link(String path, String token, String purpose) {
        return appUrl + path + "?token=" + token + (purpose == null ? "" : "&purpose=" + purpose);
    }

    /** 메일 서버 설정이 없거나 전체 상한을 넘으면 조용히 버린다. */
    private void queue(String to, String subject, String body, String link, String footer) {
        enqueue(to, subject, body, link, footer, false);
    }

    /** 보안 알림: 전체 상한에 걸리지 않고 세지도 않는다(상한을 먼저 채워 알림을 막는 것 방지). 발송 실패는 동작을 막지 않는다. */
    private void queueSecurityNotice(String to, String subject, String body, String link, String footer) {
        enqueue(to, subject, body, link, footer, true);
    }

    private void enqueue(String to, String subject, String body, String link, String footer, boolean securityNotice) {
        if (!available()) {
            return;
        }
        if (!securityNotice && !underGlobalCap()) {
            log.warn("계정 메일 전체 발송 상한(시간당 {}통)을 넘어 한 통을 보내지 않았어요.", MAX_PER_HOUR);
            return;
        }
        Runnable task = () -> send(to, subject, body, link, footer);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(task);
                }
            });
        } else {
            executor.execute(task);
        }
    }

    private void send(String to, String subject, String body, String link, String footer) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            return;
        }
        if (sender instanceof JavaMailSenderImpl impl) {
            // 운영은 application.yml 없이 환경변수로만 설정한다. SMTP 가 멈춰도 발송 스레드가 영원히 묶이지 않게 기본 시간 제한.
            impl.getJavaMailProperties().putIfAbsent("mail.smtp.connectiontimeout", "10000");
            impl.getJavaMailProperties().putIfAbsent("mail.smtp.timeout", "10000");
            impl.getJavaMailProperties().putIfAbsent("mail.smtp.writetimeout", "10000");
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            String sendFrom = from.isBlank() ? fallbackFrom : from;
            if (!sendFrom.isBlank()) {
                helper.setFrom(sendFrom, "모여로그");
            }
            helper.setTo(to);
            helper.setSubject(subject);
            String text = body + "\n\n" + (link == null ? "" : link + "\n\n") + footer + "\n";
            String html = "<p>" + HtmlUtils.htmlEscape(body) + "</p>"
                    + (link == null ? "" : "<p><a href=\"" + HtmlUtils.htmlEscape(link) + "\">"
                    + HtmlUtils.htmlEscape(link) + "</a></p>")
                    + "<p>" + HtmlUtils.htmlEscape(footer) + "</p>";
            helper.setText(text, html);
            sender.send(message);
        } catch (Exception e) {
            log.warn("계정 메일 발송 실패: {}", e.getClass().getSimpleName());
        }
    }
}
