package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.GroupTopic;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GroupTopicRepository extends JpaRepository<GroupTopic, UUID> {
    List<GroupTopic> findAllByGroupOrderByCreatedAtDesc(Group group);

    List<GroupTopic> findAllByGroup(Group group);
    List<GroupTopic> findByGroup_IdInOrderByCreatedAtDesc(List<UUID> groupIds);

    List<GroupTopic> findAllByAuthor_Id(UUID authorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from GroupTopic t where t.id = :id")
    Optional<GroupTopic> findByIdForUpdate(@Param("id") UUID id);

    /** 예전 방식(data URL)으로 이미지가 저장된 토픽 (일회성 변환용) */
    @Query("select t.id from GroupTopic t where t.imageUrl like 'data:%'")
    List<UUID> findIdsWithDataUrlImage();

    /** 수정 시각을 건드리지 않고 이미지 경로만 바꾼다 (일회성 변환용) */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update GroupTopic t set t.imageUrl = :imageUrl where t.id = :id")
    int updateImageUrl(@Param("id") UUID id, @Param("imageUrl") String imageUrl);

    /** 이 사람이 쓴 토픽 중 이 이미지를 쓰는 것이 있는지 (업로드 소유 확인) */
    boolean existsByAuthor_IdAndImageUrl(UUID authorId, String imageUrl);
}
