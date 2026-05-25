package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.FriendResponse;
import com.moyeolog.moyelog_BE.entity.Friend;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.enums.FriendStatus;
import com.moyeolog.moyelog_BE.repository.FriendRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FriendService {

    private final FriendRepository friendRepository;
    private final UserRepository userRepository;

    @Transactional
    public void sendFriendRequest(UUID requesterId, String receiverCustomId) {
        User requester = userRepository.findById(requesterId)
                .orElseThrow(() -> new RuntimeException("Requester not found"));

        User receiver = userRepository.findByCustomId(receiverCustomId)
                .orElseThrow(() -> new RuntimeException("Receiver not found with ID: " + receiverCustomId));

        if (requester.getId().equals(receiver.getId())) {
            throw new RuntimeException("Cannot send friend request to yourself");
        }

        friendRepository.findAnyRelationship(requester, receiver).ifPresent(f -> {
            throw new RuntimeException("Relationship already exists");
        });

        Friend friend = Friend.builder()
                .requester(requester)
                .receiver(receiver)
                .status(FriendStatus.PENDING)
                .build();

        friendRepository.save(friend);
    }

    @Transactional(readOnly = true)
    public List<FriendResponse> getAcceptedFriends(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return friendRepository.findAcceptedFriends(user).stream()
                .map(f -> {
                    User other = f.getRequester().getId().equals(userId) ? f.getReceiver() : f.getRequester();
                    return convertToResponse(f, other);
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FriendResponse> getPendingRequests(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return friendRepository.findByReceiverAndStatus(user, FriendStatus.PENDING).stream()
                .map(f -> convertToResponse(f, f.getRequester()))
                .collect(Collectors.toList());
    }

    @Transactional
    public void acceptFriendRequest(UUID userId, UUID requestId) {
        Friend friend = friendRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Request not found"));

        if (!friend.getReceiver().getId().equals(userId)) {
            throw new RuntimeException("Unauthorized to accept this request");
        }

        friend.accept();
    }

    @Transactional
    public void deleteFriendship(UUID userId, UUID friendshipId) {
        Friend friend = friendRepository.findById(friendshipId)
                .orElseThrow(() -> new RuntimeException("Friendship not found"));

        if (!friend.getRequester().getId().equals(userId) && !friend.getReceiver().getId().equals(userId)) {
            throw new RuntimeException("Unauthorized to delete this friendship");
        }

        friendRepository.delete(friend);
    }

    private FriendResponse convertToResponse(Friend friend, User other) {
        return FriendResponse.builder()
                .id(friend.getId())
                .userId(other.getId())
                .customId(other.getCustomId())
                .nickname(other.getNickname())
                .email(other.getEmail())
                .profileImage(other.getProfileImage())
                .status(friend.getStatus().name())
                .build();
    }
}
