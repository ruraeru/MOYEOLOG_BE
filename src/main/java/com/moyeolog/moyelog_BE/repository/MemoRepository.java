package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Memo;
import com.moyeolog.moyelog_BE.entity.User;
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
public interface MemoRepository extends JpaRepository<Memo, UUID> {
    List<Memo> findAllByAuthorOrderByCreatedAtDesc(User author);
    List<Memo> findByGroupIdOrderByCreatedAtDesc(UUID groupId);
    List<Memo> findByGroupIdInOrderByCreatedAtDesc(List<UUID> groupIds);
    List<Memo> findByTaggedMemosContains(Memo memo);

    /** 수정·삭제·공유처럼 같은 메모에 동시에 들어오는 쓰기를 줄 세운다 (SELECT ... FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Memo m WHERE m.id = :id")
    Optional<Memo> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT m FROM Memo m WHERE m.author.id = :authorId")
    List<Memo> findAllByAuthorId(@Param("authorId") UUID authorId);

    /** 회원 탈퇴: 다른 사람 메모의 '마지막 수정자' 참조를 지운다. */
    @Modifying
    @Query("UPDATE Memo m SET m.lastModifier = null WHERE m.lastModifier.id = :userId")
    int clearLastModifier(@Param("userId") UUID userId);
}
