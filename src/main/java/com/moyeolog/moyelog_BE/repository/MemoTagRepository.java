package com.moyeolog.moyelog_BE.repository;

import com.moyeolog.moyelog_BE.entity.Memo;
import com.moyeolog.moyelog_BE.entity.MemoTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemoTagRepository extends JpaRepository<MemoTag, Long> {
    List<MemoTag> findAllByMemo(Memo memo);
    void deleteAllByMemo(Memo memo);
}
