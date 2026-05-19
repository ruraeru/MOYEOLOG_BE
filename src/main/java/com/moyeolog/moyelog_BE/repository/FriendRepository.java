package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Friend;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.enums.FriendStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FriendRepository extends JpaRepository<Friend, UUID> {
    
    // 수락된 친구 목록 (내가 신청했거나 받았거나)
    @Query("SELECT f FROM Friend f WHERE (f.requester = :user OR f.receiver = :user) AND f.status = 'ACCEPTED'")
    List<Friend> findAcceptedFriends(@Param("user") User user);

    // 내가 받은 요청 목록
    List<Friend> findByReceiverAndStatus(User receiver, FriendStatus status);

    // 이미 존재하는 관계 확인 (신청 중이거나 이미 친구)
    @Query("SELECT f FROM Friend f WHERE (f.requester = :u1 AND f.receiver = :u2) OR (f.requester = :u2 AND f.receiver = :u1)")
    Optional<Friend> findAnyRelationship(@Param("u1") User u1, @Param("u2") User u2);
}
