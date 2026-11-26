package com.moyeolog.moyelog_BE.notice;

import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.ConflictException;
import com.moyeolog.moyelog_BE.notice.NoticeDtos.NoticeUser;
import com.moyeolog.moyelog_BE.notice.NoticeDtos.PollOptionResponse;
import com.moyeolog.moyelog_BE.notice.NoticeDtos.PollRequest;
import com.moyeolog.moyelog_BE.notice.NoticeDtos.PollResponse;
import com.moyeolog.moyelog_BE.service.ItemNotFoundException;
import com.moyeolog.moyelog_BE.validation.CodePointSizeValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 공지에 붙는 투표(공지 하나에 0~1개).
 * - 만들기: 공지 만들기 트랜잭션 안에서 GroupNoticeService 가 부른다. 만든 뒤에는 내용을 바꾸지 못한다.
 * - 고르기: 멤버, 마감 전까지 통째로 바꾼다(빈 목록 = 취소). 마감·재촉: 방장.
 * - 집계는 지금 모임 멤버의 표만 센다(나간 사람의 표는 GroupService 가 지우지만, 읽을 때도 한 번 더 거른다).
 * - 익명이면 선택지별 이름과 안 한 사람 목록을 비운다(방장에게도). 재촉은 익명이어도 서버가 안 한 사람에게 보낸다.
 * GroupNoticeService 를 주입받지 않는다(그쪽이 이 서비스를 쓴다). 권한은 NoticeAccess 로 본다.
 */
@Service
@RequiredArgsConstructor
public class GroupNoticePollService {

    static final int MIN_OPTIONS = 2;
    static final int MAX_OPTIONS = 10;
    static final int MAX_OPTION_LENGTH = 100;
    private static final String NOT_FOUND = "투표를 찾을 수 없어요. 삭제됐을 수 있어요.";

    private final NoticeAccess access;
    private final GroupNoticePollRepository pollRepository;
    private final GroupNoticePollOptionRepository optionRepository;
    private final GroupNoticePollVoteRepository voteRepository;

    /** 공지 응답에 넣을 투표 부분. closed·myVoted 는 목록(NoticeSummary)에서도 쓴다. */
    public record PollView(PollResponse response, boolean closed, boolean myVoted) {
    }

    /** 검증하고 저장한다. request 가 null 이면 투표 없는 공지다. */
    @Transactional
    public void createPoll(GroupNotice notice, PollRequest request) {
        if (request == null) return;
        if (request.options() == null) {
            throw new BadRequestException("투표 선택지를 보내 주세요.");
        }
        List<String> options = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : request.options()) {
            String text = raw == null ? "" : raw.trim();
            int length = CodePointSizeValidator.length(text);
            if (length < 1 || length > MAX_OPTION_LENGTH) {
                throw new BadRequestException("선택지는 1~" + MAX_OPTION_LENGTH + "자로 써 주세요.");
            }
            if (!seen.add(text)) {
                throw new BadRequestException("같은 선택지가 두 번 있어요.");
            }
            options.add(text);
        }
        if (options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) {
            throw new BadRequestException("선택지는 " + MIN_OPTIONS + "~" + MAX_OPTIONS + "개로 만들어 주세요.");
        }
        if (request.closesAt() != null && !request.closesAt().isAfter(LocalDateTime.now())) {
            throw new BadRequestException("마감 시각은 지금 이후로 정해 주세요.");
        }

        GroupNoticePoll poll = pollRepository.save(GroupNoticePoll.builder()
                .notice(notice)
                .multiple(request.multiple())
                .anonymous(request.anonymous())
                .closesAt(request.closesAt())
                .build());
        List<GroupNoticePollOption> rows = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            rows.add(GroupNoticePollOption.builder().poll(poll).text(options.get(i)).position(i).build());
        }
        optionRepository.saveAll(rows);
    }

    /**
     * 공지 ID → 투표 보기. 투표가 없는 공지는 Map 에 없다.
     * 투표·선택지·표를 공지 묶음 단위로 한 번씩만 읽는다(N+1 없음).
     *
     * @param members 지금 모임 멤버(가입 순서). 이름 목록도 이 순서다.
     */
    @Transactional(readOnly = true)
    public Map<UUID, PollView> views(Collection<GroupNotice> notices, UUID viewerId, List<User> members) {
        if (notices.isEmpty()) return Map.of();
        List<GroupNoticePoll> polls = pollRepository.findAllByNoticeIds(
                notices.stream().map(GroupNotice::getId).toList());
        if (polls.isEmpty()) return Map.of();
        List<UUID> pollIds = polls.stream().map(GroupNoticePoll::getId).toList();

        Map<UUID, List<GroupNoticePollOption>> optionsByPoll = new HashMap<>();
        for (GroupNoticePollOption option : optionRepository.findAllByPollIds(pollIds)) {
            optionsByPoll.computeIfAbsent(option.getPoll().getId(), k -> new ArrayList<>()).add(option);
        }
        optionsByPoll.values().forEach(list -> list.sort(Comparator.comparingInt(GroupNoticePollOption::getPosition)));
        Set<UUID> memberIds = new HashSet<>();
        members.forEach(u -> memberIds.add(u.getId()));
        // 선택지 ID → 그 선택지를 고른 멤버 ID
        Map<UUID, Set<UUID>> votersByOption = new HashMap<>();
        for (GroupNoticePollVote vote : voteRepository.findAllByPollIds(pollIds)) {
            UUID userId = vote.getUser().getId();
            if (!memberIds.contains(userId)) continue;
            votersByOption.computeIfAbsent(vote.getOption().getId(), k -> new HashSet<>()).add(userId);
        }

        LocalDateTime now = LocalDateTime.now();
        Map<UUID, PollView> result = new HashMap<>();
        for (GroupNoticePoll poll : polls) {
            List<GroupNoticePollOption> options = optionsByPoll.getOrDefault(poll.getId(), List.of());
            Set<UUID> voted = new HashSet<>();
            List<PollOptionResponse> optionResponses = new ArrayList<>();
            for (GroupNoticePollOption option : options) {
                Set<UUID> voters = votersByOption.getOrDefault(option.getId(), Set.of());
                voted.addAll(voters);
                List<NoticeUser> names = poll.isAnonymous() ? List.of() : members.stream()
                        .filter(u -> voters.contains(u.getId())).map(GroupNoticePollService::noticeUser).toList();
                optionResponses.add(new PollOptionResponse(option.getId(), option.getText(), voters.size(),
                        voters.contains(viewerId), names));
            }
            List<NoticeUser> notVoted = poll.isAnonymous() ? List.of() : members.stream()
                    .filter(u -> !voted.contains(u.getId())).map(GroupNoticePollService::noticeUser).toList();
            boolean closed = poll.isClosed(now);
            boolean myVoted = voted.contains(viewerId);
            result.put(poll.getNotice().getId(), new PollView(
                    new PollResponse(poll.isMultiple(), poll.isAnonymous(), poll.getClosesAt(), closed,
                            voted.size(), optionResponses, notVoted),
                    closed, myVoted));
        }
        return result;
    }

    /** 내 선택을 통째로 바꾼다. 빈 목록 = 취소. 단일 선택인데 2개 이상이면 400, 닫혔으면 409. */
    @Transactional
    public void vote(UUID userId, UUID groupId, UUID noticeId, List<UUID> optionIds) {
        access.requireMember(groupId, userId);
        access.findNotice(groupId, noticeId, false);
        // 같은 사람이 동시에 두 번 저장해도 한 벌만 남도록 투표 행을 잠근다.
        GroupNoticePoll poll = pollRepository.findByNoticeIdForUpdate(noticeId)
                .orElseThrow(() -> new ItemNotFoundException(NOT_FOUND));
        if (poll.isClosed(LocalDateTime.now())) {
            throw new ConflictException("마감된 투표예요.");
        }
        if (optionIds == null) {
            throw new BadRequestException("고른 선택지를 보내 주세요.");
        }
        Set<UUID> chosen = new LinkedHashSet<>(optionIds);
        if (!poll.isMultiple() && chosen.size() > 1) {
            throw new BadRequestException("이 투표는 하나만 고를 수 있어요.");
        }
        Map<UUID, GroupNoticePollOption> options = new HashMap<>();
        optionRepository.findAllByPollIds(List.of(poll.getId())).forEach(o -> options.put(o.getId(), o));
        for (UUID id : chosen) {
            if (!options.containsKey(id)) {
                throw new BadRequestException("이 투표에 없는 선택지예요.");
            }
        }

        List<User> members = access.members(groupId);
        User me = members.stream().filter(u -> u.getId().equals(userId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("requireMember passed but member row is gone"));
        voteRepository.deleteByPollIdAndUserId(poll.getId(), userId);
        LocalDateTime now = LocalDateTime.now();
        voteRepository.saveAll(chosen.stream().map(id -> GroupNoticePollVote.builder()
                .poll(poll).option(options.get(id)).user(me).votedAt(now).build()).toList());
        voteRepository.flush();

        access.publishChange(groupId, members);
    }

    /** 방장이 바로 마감한다. 이미 닫혀 있으면 아무것도 바꾸지 않는다. */
    @Transactional
    public void close(UUID userId, UUID groupId, UUID noticeId) {
        Group group = access.requireMember(groupId, userId);
        access.requireOwner(group, userId, "방장만 투표를 마감할 수 있어요.");
        access.findNotice(groupId, noticeId, false);
        GroupNoticePoll poll = pollRepository.findByNoticeIdForUpdate(noticeId)
                .orElseThrow(() -> new ItemNotFoundException(NOT_FOUND));
        LocalDateTime now = LocalDateTime.now();
        if (poll.isClosed(now)) return;
        poll.close(now);
        pollRepository.flush();
        access.publishChange(groupId, access.members(groupId));
    }

    /** 아직 투표하지 않은 멤버에게 알림. 익명이어도 보낸다(받는 사람 목록은 응답에 나가지 않는다). */
    @Transactional
    public void remind(UUID userId, UUID groupId, UUID noticeId) {
        Group group = access.requireMember(groupId, userId);
        access.requireOwner(group, userId, "방장만 투표를 재촉할 수 있어요.");
        GroupNotice notice = access.findNotice(groupId, noticeId, false);
        GroupNoticePoll poll = pollRepository.findByNotice_Id(noticeId)
                .orElseThrow(() -> new ItemNotFoundException(NOT_FOUND));
        if (poll.isClosed(LocalDateTime.now())) {
            throw new ConflictException("마감된 투표는 재촉할 수 없어요.");
        }
        List<User> members = access.members(groupId);
        Set<UUID> voted = new HashSet<>();
        voteRepository.findAllByPollIds(List.of(poll.getId())).forEach(v -> voted.add(v.getUser().getId()));
        List<User> waiting = members.stream().filter(u -> !voted.contains(u.getId())).toList();
        String actor = members.stream().filter(u -> u.getId().equals(userId)).findFirst()
                .map(User::getNickname).orElse("방장");
        access.notify(waiting, userId, groupId, notice.getId(),
                actor + "님이 '" + shortTitle(notice) + "' 공지의 투표를 기다리고 있어요.");
    }

    private static NoticeUser noticeUser(User user) {
        return new NoticeUser(user.getId(), user.getNickname(), user.getProfileImage());
    }

    private static String shortTitle(GroupNotice notice) {
        String title = notice.getTitle().trim();
        return CodePointSizeValidator.length(title) > 30 ? CodePointSizeValidator.truncate(title, 30) + "…" : title;
    }
}
