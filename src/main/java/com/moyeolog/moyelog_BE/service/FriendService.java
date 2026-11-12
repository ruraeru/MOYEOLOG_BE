package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.FriendResponse;
import com.moyeolog.moyelog_BE.entity.Friend;
import com.moyeolog.moyelog_BE.entity.MemoShare;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.enums.FriendStatus;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.ConflictException;
import com.moyeolog.moyelog_BE.exception.ResourceNotFoundException;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import com.moyeolog.moyelog_BE.repository.FriendRepository;
import com.moyeolog.moyelog_BE.repository.MemoShareRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FriendService {

    private final FriendRepository friendRepository;
    private final UserRepository userRepository;
    private final MemoShareRepository memoShareRepository;

    @Transactional
    public void sendFriendRequest(UUID requesterId, String receiverCustomId) {
        if (receiverCustomId == null || receiverCustomId.isBlank()) {
            throw new BadRequestException("친구 ID는 필수입니다.");
        }
        User requester = findUserById(requesterId);
        User receiver = userRepository.findByCustomId(receiverCustomId.trim())
                .orElseThrow(() -> new ResourceNotFoundException("사용자", receiverCustomId.trim()));

        if (requester.getId().equals(receiver.getId())) {
            throw new BadRequestException("자기 자신에게는 친구 요청을 보낼 수 없습니다.");
        }

        if (!friendRepository.findAnyRelationship(requester, receiver).isEmpty()) {
            throw new ConflictException("이미 친구이거나 친구 요청이 진행 중입니다.");
        }

        Friend friend = Friend.builder()
                .requester(requester)
                .receiver(receiver)
                .status(FriendStatus.PENDING)
                .build();

        friendRepository.save(friend);
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

    @Transactional
    public void acceptFriendRequest(UUID userId, UUID requestId) {
        Friend friend = friendRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("친구 요청", requestId));

        if (!friend.getReceiver().getId().equals(userId)) {
            throw new UnauthorizedAccessException("본인에게 온 친구 요청만 수락할 수 있습니다.");
        }

        friend.accept();
    }

    @Transactional
    public void deleteFriendship(UUID userId, UUID friendshipId) {
        Friend friend = friendRepository.findById(friendshipId)
                .orElseThrow(() -> new ResourceNotFoundException("친구 관계", friendshipId));

        UUID requesterId = friend.getRequester().getId();
        UUID receiverId = friend.getReceiver().getId();
        if (!requesterId.equals(userId) && !receiverId.equals(userId)) {
            throw new UnauthorizedAccessException("본인의 친구 관계만 삭제할 수 있습니다.");
        }

        // 친구 관계가 끊어지면 두 사용자 사이의 메모 공유도 양방향 모두 해제한다.
        List<MemoShare> shares = new ArrayList<>();
        shares.addAll(memoShareRepository.findAllByAuthorAndRecipient(requesterId, receiverId));
        shares.addAll(memoShareRepository.findAllByAuthorAndRecipient(receiverId, requesterId));
        if (!shares.isEmpty()) {
            memoShareRepository.deleteAll(shares);
        }

        friendRepository.delete(friend);
    }

    private User findUserById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));
    }

    private FriendResponse convertToResponse(Friend friend, User other, boolean includeEmail) {
        return FriendResponse.builder()
                .id(friend.getId())
                .userId(other.getId())
                .customId(other.getCustomId())
                .nickname(other.getNickname())
                .email(includeEmail ? other.getEmail() : null)
                .profileImage(other.getProfileImage())
                .status(friend.getStatus().name())
                .build();
    }
}
