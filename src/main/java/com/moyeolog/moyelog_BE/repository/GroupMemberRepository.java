package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.GroupMember;
import com.moyeolog.moyelog_BE.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

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

    void deleteAllByGroup(Group group);
}
