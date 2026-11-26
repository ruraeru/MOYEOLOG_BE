package com.moyeolog.moyelog_BE.notice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface GroupNoticePollOptionRepository extends JpaRepository<GroupNoticePollOption, UUID> {

    /** 여러 투표의 선택지(만든 순서) */
    @Query("SELECT o FROM GroupNoticePollOption o WHERE o.poll.id IN :pollIds ORDER BY o.position ASC")
    List<GroupNoticePollOption> findAllByPollIds(@Param("pollIds") Collection<UUID> pollIds);
}
