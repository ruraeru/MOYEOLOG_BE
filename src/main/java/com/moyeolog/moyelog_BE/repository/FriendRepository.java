package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Friend;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.enums.FriendStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FriendRepository extends JpaRepository<Friend, UUID> {
    
    // 수락된 친구 목록 (내가 신청했거나 받았거나)
    @Query("SELECT f FROM Friend f WHERE (f.requester = :user OR f.receiver = :user) AND f.status = com.moyeolog.moyelog_BE.enums.FriendStatus.ACCEPTED")
    List<Friend> findAcceptedFriends(@Param("user") User user);

    // 내가 받은 요청 목록
    List<Friend> findByReceiverAndStatus(User receiver, FriendStatus status);

    // 내가 받은 요청 목록 (최근 요청 먼저)
    List<Friend> findByReceiverAndStatusOrderByCreatedAtDesc(User receiver, FriendStatus status);

    // 내가 보낸 요청 목록
    List<Friend> findByRequesterAndStatusOrderByCreatedAtDesc(User requester, FriendStatus status);

    // 이미 존재하는 관계 확인 (신청 중이거나 이미 친구)
    @Query("SELECT f FROM Friend f WHERE (f.requester = :u1 AND f.receiver = :u2) OR (f.requester = :u2 AND f.receiver = :u1)")
    List<Friend> findAnyRelationship(@Param("u1") User u1, @Param("u2") User u2);

    // 두 사용자가 수락된 친구 관계인지 확인
    @Query("SELECT COUNT(f) > 0 FROM Friend f WHERE f.status = com.moyeolog.moyelog_BE.enums.FriendStatus.ACCEPTED " +
            "AND ((f.requester.id = :a AND f.receiver.id = :b) OR (f.requester.id = :b AND f.receiver.id = :a))")
    boolean areFriends(@Param("a") UUID a, @Param("b") UUID b);

    /** 수락·삭제·공유가 겹칠 때 관계 행을 잠근다 (SELECT ... FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Friend f WHERE f.id = :id")
    Optional<Friend> findByIdForUpdate(@Param("id") UUID id);

    /** 두 사람 사이의 수락된 관계를 잠근 채로 읽는다. 공유처럼 '친구인지 확인 후 저장' 하는 곳에서 쓴다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Friend f WHERE f.status = com.moyeolog.moyelog_BE.enums.FriendStatus.ACCEPTED " +
            "AND ((f.requester.id = :a AND f.receiver.id = :b) OR (f.requester.id = :b AND f.receiver.id = :a))")
    List<Friend> findAcceptedBetweenForUpdate(@Param("a") UUID a, @Param("b") UUID b);

    @Query("SELECT f FROM Friend f WHERE f.requester.id = :userId OR f.receiver.id = :userId")
    List<Friend> findAllInvolving(@Param("userId") UUID userId);

    @Modifying
    @Query("DELETE FROM Friend f WHERE f.requester.id = :userId OR f.receiver.id = :userId")
    int deleteAllInvolving(@Param("userId") UUID userId);
}
