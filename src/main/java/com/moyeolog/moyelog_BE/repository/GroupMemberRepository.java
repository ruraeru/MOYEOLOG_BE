package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.GroupMember;
import com.moyeolog.moyelog_BE.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GroupMemberRepository extends JpaRepository<GroupMember, UUID> {
    List<GroupMember> findByUser(User user);
    List<GroupMember> findByGroup(Group group);
    // (group, user) 조회는 과거 데이터에 중복 행이 있어도 예외가 나지 않도록 exists / 목록 조회만 사용한다.
    boolean existsByGroup_IdAndUser_Id(UUID groupId, UUID userId);

    List<GroupMember> findAllByGroup_IdAndUser_Id(UUID groupId, UUID userId);

    long countByGroup_Id(UUID groupId);

    @Query("SELECT m.user.id FROM GroupMember m WHERE m.group.id = :groupId")
    List<UUID> findUserIdsByGroupId(@Param("groupId") UUID groupId);

    /** 가입 순서대로 (방장 이양 대상 찾기용) */
    List<GroupMember> findAllByGroup_IdOrderByJoinedAtAsc(UUID groupId);

    /** 동시에 여러 번 와도 예외 없이 지운 행 수를 돌려준다(0 이면 이미 멤버가 아님). */
    @Modifying(flushAutomatically = true)
    @Query("delete from GroupMember m where m.group.id = :groupId and m.user.id = :userId")
    int deleteMembership(@Param("groupId") UUID groupId, @Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query("delete from GroupMember m where m.user.id = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);

    void deleteAllByGroup(Group group);
}
