package com.moyeolog.moyelog_BE.live;

import com.moyeolog.moyelog_BE.entity.Memo;
import com.moyeolog.moyelog_BE.entity.Schedule;
import com.moyeolog.moyelog_BE.event.ChangeEvents;
import com.moyeolog.moyelog_BE.event.LiveTopics;
import com.moyeolog.moyelog_BE.repository.GroupMemberRepository;
import com.moyeolog.moyelog_BE.repository.MemoShareRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 실시간 갱신의 받는 사람 계산. 지금(호출한 순간) 그 데이터를 볼 수 있는 사람을 changes 에 담는다.
 * 변경 전·후에 각각 부르면 '변경 전에 볼 수 있던 사람'까지 들어간다.
 * group:{id} 는 그 모임 멤버에게만 담는다(모임 밖 참여자·공유받은 사람·나간 작성자는 받지 않는다).
 */
public final class LiveAudience {

    private LiveAudience() {
    }

    /** 일정: 작성자·참여자·(모임 일정) 모임 멤버 → schedules, schedule:{id} / 모임 멤버 → group:{gid} */
    public static ChangeEvents addSchedule(ChangeEvents changes, Schedule schedule, GroupMemberRepository groupMembers) {
        Set<UUID> viewers = new HashSet<>();
        viewers.add(schedule.getAuthor().getId());
        if (schedule.getParticipants() != null) {
            schedule.getParticipants().forEach(p -> viewers.add(p.getId()));
        }
        List<UUID> members = members(schedule.getGroupId(), groupMembers);
        viewers.addAll(members);
        changes.add(viewers, LiveTopics.SCHEDULES, LiveTopics.schedule(schedule.getId()));
        changes.add(members, LiveTopics.group(schedule.getGroupId()));
        return changes;
    }

    /** 메모: 작성자·공유받은 사람·(모임 메모) 모임 멤버 → memos / 모임 멤버 → group:{gid} */
    public static ChangeEvents addMemo(ChangeEvents changes, Memo memo, MemoShareRepository shares,
                                       GroupMemberRepository groupMembers) {
        Set<UUID> viewers = new HashSet<>();
        viewers.add(memo.getAuthor().getId());
        shares.findAllByMemo(memo).forEach(share -> viewers.add(share.getSharedTo().getId()));
        List<UUID> members = members(memo.getGroupId(), groupMembers);
        viewers.addAll(members);
        changes.add(viewers, LiveTopics.MEMOS);
        changes.add(members, LiveTopics.group(memo.getGroupId()));
        return changes;
    }

    private static List<UUID> members(UUID groupId, GroupMemberRepository groupMembers) {
        return groupId == null ? List.of() : groupMembers.findUserIdsByGroupId(groupId);
    }
}
