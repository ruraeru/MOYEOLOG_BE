package com.moyeolog.moyelog_BE.notice;

import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.event.UserDeletingEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 회원 탈퇴 직전: 그 사람의 공지 읽음·투표를 지우고, 그 사람이 쓴 공지는 지금 방장에게 넘긴다.
 * 모임 이양·삭제(GroupService, @Order(20)) 뒤에 돌아야 새 방장이 정해져 있다.
 */
@Component
@RequiredArgsConstructor
public class NoticeUserCleanup {

    private final GroupNoticeRepository noticeRepository;
    private final GroupNoticeReadRepository readRepository;
    private final GroupNoticePollVoteRepository voteRepository;

    @EventListener
    @Order(30)
    @Transactional
    public void onUserDeleting(UserDeletingEvent event) {
        UUID userId = event.userId();
        readRepository.deleteAllByUserId(userId);
        voteRepository.deleteAllByUserId(userId);
        for (GroupNotice notice : noticeRepository.findAllByAuthor_Id(userId)) {
            User owner = notice.getGroup().getCreatedBy();
            if (owner.getId().equals(userId)) {
                // 넘길 사람이 없다(모임이 이미 지워졌어야 하는 경우). 공지를 지우면 읽음·투표는 CASCADE 로 같이 간다.
                noticeRepository.delete(notice);
            } else {
                notice.changeAuthor(owner);
            }
        }
        noticeRepository.flush();
    }
}
