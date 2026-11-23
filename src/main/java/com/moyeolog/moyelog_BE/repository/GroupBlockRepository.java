package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.GroupBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface GroupBlockRepository extends JpaRepository<GroupBlock, UUID> {
    boolean existsByGroup_IdAndUser_Id(UUID groupId, UUID userId);

    @Modifying(flushAutomatically = true)
    @Query("delete from GroupBlock b where b.group.id = :groupId and b.user.id = :userId")
    int deleteBlock(@Param("groupId") UUID groupId, @Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query("delete from GroupBlock b where b.user.id = :userId or b.blockedBy.id = :userId")
    int deleteAllInvolvingUser(@Param("userId") UUID userId);

    void deleteAllByGroup(Group group);
}
