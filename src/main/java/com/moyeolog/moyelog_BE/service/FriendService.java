package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.FriendResponse;
import com.moyeolog.moyelog_BE.entity.Friend;
import com.moyeolog.moyelog_BE.entity.Schedule;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.enums.FriendStatus;
import com.moyeolog.moyelog_BE.event.NotificationEvent;
import com.moyeolog.moyelog_BE.event.NotificationRetractEvent;
import com.moyeolog.moyelog_BE.event.NotificationType;
import com.moyeolog.moyelog_BE.event.UserDeletingEvent;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.ConflictException;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import com.moyeolog.moyelog_BE.repository.FriendRepository;
import com.moyeolog.moyelog_BE.repository.MemoFavoriteRepository;
import com.moyeolog.moyelog_BE.repository.MemoShareRepository;
import com.moyeolog.moyelog_BE.repository.ScheduleRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 친구 관계. 두 사람 쌍당 관계는 하나뿐이다(friends.user_low_id/user_high_id 유니크).
 * 수락·삭제는 관계 행을 잠근 뒤 처리해서, 겹치는 요청은 하나만 성공하고 나머지는 404/409 가 된다.
 */
@Service
@RequiredArgsConstructor
public class FriendService {

    private static final String ALREADY_RELATED = "이미 친구이거나 친구 요청이 진행 중이에요.";

    private final FriendRepository friendRepository;
    private final UserRepository userRepository;
    private final MemoShareRepository memoShareRepository;
    private final MemoFavoriteRepository memoFavoriteRepository;
    private final ScheduleRepository scheduleRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void sendFriendRequest(UUID requesterId, String receiverCustomId) {
        if (receiverCustomId == null || receiverCustomId.isBlank()) {
            throw new BadRequestException("친구 ID를 입력해 주세요.");
        }
        User requester = findUserById(requesterId);
        User receiver = userRepository.findByCustomId(receiverCustomId.trim())
                .orElseThrow(() -> new ItemNotFoundException("그 ID의 사용자를 찾을 수 없어요."));

        if (requester.getId().equals(receiver.getId())) {
            throw new BadRequestException("자기 자신에게는 친구 요청을 보낼 수 없어요.");
        }

        if (!friendRepository.findAnyRelationship(requester, receiver).isEmpty()) {
            throw new ConflictException(ALREADY_RELATED);
        }

        Friend friend = Friend.builder()
                .requester(requester)
                .receiver(receiver)
                .status(FriendStatus.PENDING)
                .build();

        friendRepository.save(friend);
        // 동시 요청·역요청은 쌍 유니크 제약에 걸린다 → 409.
        ConcurrentUpdates.flush(friendRepository::flush, ALREADY_RELATED);

        eventPublisher.publishEvent(new NotificationEvent(receiver.getId(), requester.getId(),
                NotificationType.FRIEND_REQUEST, requester.getNickname() + "님이 친구 요청을 보냈어요.", "/friends"));
    }

    @Transactional(readOnly = true)
    public List<FriendResponse> getAcceptedFriends(UUID userId) {
        User user = findUserById(userId);

        return friendRepository.findAcceptedFriends(user).stream()
                .map(f -> {
                    User other = f.getRequester().getId().equals(userId) ? f.getReceiver() : f.getRequester();
                    return convertToResponse(f, other, true);
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FriendResponse> getPendingRequests(UUID userId) {
        User user = findUserById(userId);

        // 수락 전에는 요청자의 이메일을 노출하지 않는다 (필드는 유지, 값은 null).
        return friendRepository.findByReceiverAndStatus(user, FriendStatus.PENDING).stream()
                .map(f -> convertToResponse(f, f.getRequester(), false))
                .collect(Collectors.toList());
    }

    /** 내가 보낸 대기 중 요청. id 는 요청 ID (취소는 DELETE /api/friends/{id}). */
    @Transactional(readOnly = true)
    public List<FriendResponse> getSentRequests(UUID userId) {
        User user = findUserById(userId);
        return friendRepository.findByRequesterAndStatusOrderByCreatedAtDesc(user, FriendStatus.PENDING).stream()
                .map(f -> convertToResponse(f, f.getReceiver(), false))
                .collect(Collectors.toList());
    }

    @Transactional
    public void acceptFriendRequest(UUID userId, UUID requestId) {
        // 수락·취소가 겹치면 먼저 잠근 쪽만 성공한다. 이미 지워졌으면 404, 이미 수락됐으면 409.
        Friend friend = friendRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ItemNotFoundException("친구 요청을 찾을 수 없어요. 취소됐을 수 있어요."));

        if (!friend.getReceiver().getId().equals(userId)) {
            throw new UnauthorizedAccessException("나에게 온 친구 요청만 수락할 수 있어요.");
        }
        if (friend.getStatus() == FriendStatus.ACCEPTED) {
            throw new ConflictException("이미 친구예요.");
        }

        friend.accept();
        ConcurrentUpdates.flush(friendRepository::flush);
        retractRequestNotification(friend);

        User receiver = friend.getReceiver();
        eventPublisher.publishEvent(new NotificationEvent(friend.getRequester().getId(), receiver.getId(),
                NotificationType.FRIEND_ACCEPTED, receiver.getNickname() + "님이 친구 요청을 수락했어요.", "/friends"));
    }

    /** 친구 끊기 / 보낸 요청 취소 / 받은 요청 거절. 친구를 끊으면 서로의 메모 공유와 개인 일정 참여도 정리한다. */
    @Transactional
    public void deleteFriendship(UUID userId, UUID friendshipId) {
        // 관계 행을 먼저 잠근다: 공유(shareMemo)도 같은 행을 잠그고 확인하므로, 끊긴 뒤에 공유가 남지 않는다.
        Friend friend = friendRepository.findByIdForUpdate(friendshipId)
                .orElseThrow(() -> new ItemNotFoundException("친구 관계를 찾을 수 없어요. 이미 정리됐을 수 있어요."));

        if (!friend.involves(userId)) {
            throw new UnauthorizedAccessException("내 친구 관계만 정리할 수 있어요.");
        }

        boolean wasPending = friend.getStatus() == FriendStatus.PENDING;
        cleanUpBetween(friend.getRequester().getId(), friend.getReceiver().getId());
        friendRepository.delete(friend);
        ConcurrentUpdates.flush(friendRepository::flush);
        if (wasPending) {
            retractRequestNotification(friend);
        }
    }

    /** 처리(수락)되거나 사라진(취소·거절) 요청의 FRIEND_REQUEST 알림을 받은 사람에게서 지운다 (커밋 뒤). */
    private void retractRequestNotification(Friend friend) {
        eventPublisher.publishEvent(new NotificationRetractEvent(friend.getReceiver().getId(),
                friend.getRequester().getId(), NotificationType.FRIEND_REQUEST));
    }

    /** 회원 탈퇴: 내 친구 관계(요청 포함)를 모두 지운다. 공유·일정은 메모·일정 쪽 리스너가 지운다. */
    @EventListener
    @Transactional
    public void onUserDeleting(UserDeletingEvent event) {
        friendRepository.deleteAllInvolving(event.userId());
    }

    /** 두 사람 사이의 메모 공유(양방향), 그 공유로 생긴 즐겨찾기, 서로의 개인 일정 참여를 정리한다. */
    private void cleanUpBetween(UUID a, UUID b) {
        List<UUID> shareIds = memoShareRepository.findIdsBetween(a, b);
        if (!shareIds.isEmpty()) {
            List<UUID> memosSharedToA = memoShareRepository.findMemoIdsByIdsAndRecipient(shareIds, a);
            List<UUID> memosSharedToB = memoShareRepository.findMemoIdsByIdsAndRecipient(shareIds, b);
            memoShareRepository.deleteByIds(shareIds);
            removeFavoritesNoLongerReadable(a, memosSharedToA);
            removeFavoritesNoLongerReadable(b, memosSharedToB);
        }

        removePersonalParticipation(a, b);
        removePersonalParticipation(b, a);
    }

    private void removeFavoritesNoLongerReadable(UUID userId, List<UUID> memoIds) {
        if (memoIds.isEmpty()) return;
        // 모임 메모라 여전히 읽을 수 있는 경우는 그대로 둔다.
        memoFavoriteRepository.deleteUnreadableByUserIdAndMemoIds(userId, memoIds);
    }

    private void removePersonalParticipation(UUID authorId, UUID participantId) {
        for (Schedule schedule : scheduleRepository.findPersonalByAuthorAndParticipant(authorId, participantId)) {
            schedule.getParticipants().removeIf(p -> p.getId().equals(participantId));
        }
    }

    private User findUserById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ItemNotFoundException("사용자를 찾을 수 없어요."));
    }

    /** 상대가 이메일 공개를 켰는지 (계약 2절: 기본 false). */
    static boolean isEmailVisible(User user) {
        return user.isEmailVisible();
    }

    private FriendResponse convertToResponse(Friend friend, User other, boolean includeEmail) {
        return FriendResponse.builder()
                .id(friend.getId())
                .userId(other.getId())
                .customId(other.getCustomId())
                .nickname(other.getNickname())
                .email(includeEmail && friend.getStatus() == FriendStatus.ACCEPTED && isEmailVisible(other) ? other.getEmail() : null)
                .profileImage(other.getProfileImage())
                .status(friend.getStatus().name())
                .build();
    }
}
