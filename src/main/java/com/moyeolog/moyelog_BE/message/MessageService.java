package com.moyeolog.moyelog_BE.message;

import com.moyeolog.moyelog_BE.entity.Friend;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.event.UserDeletingEvent;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import com.moyeolog.moyelog_BE.notification.NotFoundException;
import com.moyeolog.moyelog_BE.notification.NotificationService;
import com.moyeolog.moyelog_BE.repository.FriendRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import com.moyeolog.moyelog_BE.validation.CodePointSizeValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MessageService {

    static final int MAX_CONTENT_LENGTH = 2000;
    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 100;
    static final String USER_NOT_FOUND = "사용자를 찾을 수 없어요.";
    static final String NOT_FRIEND = "친구에게만 메시지를 보낼 수 있어요.";
    static final String INVALID_CONTENT = "메시지는 1~2000자로 입력해 주세요.";

    /** 공백과 보이지 않는 문자(폭 없는 공백, 방향 표시, 단어 결합자, BOM 등) */
    private static final Pattern BLANK = Pattern.compile("[\\s\\p{Z}\\u200B-\\u200F\\u2028-\\u202F\\u2060-\\u2064\\uFEFF]*");

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final FriendRepository friendRepository;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public List<ConversationResponse> getConversations(UUID userId) {
        User me = findUser(userId);

        // 상대: 지금 친구 + 메시지를 주고받은 적 있는 사람(친구를 끊어도 대화는 남는다)
        Map<UUID, User> partners = new LinkedHashMap<>();
        for (Friend f : friendRepository.findAcceptedFriends(me)) {
            User other = f.getRequester().getId().equals(userId) ? f.getReceiver() : f.getRequester();
            partners.put(other.getId(), other);
        }
        Set<UUID> messaged = new HashSet<>(messageRepository.findRecipientIdsOf(userId));
        messaged.addAll(messageRepository.findSenderIdsTo(userId));
        messaged.removeAll(partners.keySet());
        messaged.remove(userId);
        if (!messaged.isEmpty()) {
            userRepository.findAllById(messaged).forEach(u -> partners.put(u.getId(), u));
        }

        Map<UUID, Long> unread = new HashMap<>();
        for (Object[] row : messageRepository.countUnreadBySender(userId)) {
            unread.put((UUID) row[0], ((Number) row[1]).longValue());
        }

        List<ConversationResponse> result = new ArrayList<>();
        for (User partner : partners.values()) {
            Message last = messageRepository.findBetween(userId, partner.getId(), PageRequest.of(0, 1))
                    .stream().findFirst().orElse(null);
            result.add(new ConversationResponse(ConversationResponse.Friend.from(partner),
                    ConversationResponse.LastMessage.from(last), unread.getOrDefault(partner.getId(), 0L)));
        }

        // 최근 메시지 순, 메시지가 없는 친구는 뒤에 닉네임 순
        result.sort(Comparator
                .comparing((ConversationResponse c) -> c.lastMessage() == null ? null : c.lastMessage().createdAt(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(c -> Objects.toString(c.friend().nickname(), "")));
        return result;
    }

    /** 오래된 순(마지막이 최신). before 가 있으면 그보다 이전 메시지 size 개. */
    @Transactional(readOnly = true)
    public List<MessageResponse> getMessages(UUID userId, UUID friendId, LocalDateTime before, Integer size) {
        findUser(friendId);
        int safeSize = size == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest page = PageRequest.of(0, safeSize);
        List<Message> newestFirst = before == null
                ? messageRepository.findBetween(userId, friendId, page)
                : messageRepository.findBetweenBefore(userId, friendId, before, page);
        List<MessageResponse> result = new ArrayList<>(newestFirst.stream().map(MessageResponse::from).toList());
        Collections.reverse(result);
        return result;
    }

    @Transactional
    public MessageResponse send(UUID userId, UUID friendId, MessageSendRequest request) {
        String content = request == null || request.content() == null ? "" : request.content().strip();
        int length = CodePointSizeValidator.length(content);
        if (length < 1 || length > MAX_CONTENT_LENGTH || BLANK.matcher(content).matches()) {
            throw new BadRequestException(INVALID_CONTENT);
        }
        User sender = findUser(userId);
        User recipient = findUser(friendId);
        if (userId.equals(friendId) || !friendRepository.areFriends(userId, friendId)) {
            throw new UnauthorizedAccessException(NOT_FRIEND);
        }
        Message saved = messageRepository.save(Message.builder()
                .sender(sender)
                .recipient(recipient)
                .content(content)
                .build());
        return MessageResponse.from(saved);
    }

    @Transactional
    public void markRead(UUID userId, UUID friendId) {
        findUser(friendId);
        messageRepository.markRead(userId, friendId, LocalDateTime.now());
    }

    /** 헤더 배지용. 알림 설정에서 메시지를 끄면 0 (메시지 자체는 그대로). */
    @Transactional(readOnly = true)
    public long getUnreadCount(UUID userId) {
        if (!notificationService.getPreferenceEntity(userId).isMessage()) {
            return 0;
        }
        return messageRepository.countByRecipient_IdAndReadAtIsNull(userId);
    }

    /** 회원 탈퇴 직전(같은 트랜잭션): 내가 보내거나 받은 메시지를 지운다. */
    @EventListener
    public void onUserDeleting(UserDeletingEvent event) {
        messageRepository.deleteAllInvolving(event.userId());
    }

    private User findUser(UUID id) {
        return userRepository.findById(id).orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));
    }
}
