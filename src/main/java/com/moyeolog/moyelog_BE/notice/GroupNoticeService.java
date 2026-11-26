package com.moyeolog.moyelog_BE.notice;

import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.notice.NoticeDtos.*;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import com.moyeolog.moyelog_BE.validation.CodePointSizeValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 모임 공지.
 * - 보기: 모임 멤버(상세를 열면 읽음으로 기록). 쓰기·수정·삭제·고정: 방장.
 * - 투표는 GroupNoticePollService 가 만들고 응답을 조립한다(여기서는 createPoll·views 만 부른다).
 * - 읽음 수는 지금 멤버의 읽음만 센다(나간 사람의 읽음은 GroupService 가 지우지만, 읽을 때도 한 번 더 거른다).
 */
@Service
@RequiredArgsConstructor
public class GroupNoticeService {

    private static final int PREVIEW_LENGTH = 100;
    /** 고정된 것 먼저(고정 시각 최신순), 그다음 최신순 */
    private static final Comparator<GroupNotice> ORDER = Comparator
            .comparing(GroupNotice::getPinnedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(GroupNotice::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));

    private final GroupNoticeRepository noticeRepository;
    private final GroupNoticeReadRepository readRepository;
    private final GroupNoticePollService pollService;
    private final NoticeAccess access;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<NoticeSummary> list(UUID userId, UUID groupId) {
        Group group = access.requireMember(groupId, userId);
        List<User> members = access.members(groupId);
        List<GroupNotice> notices = new ArrayList<>(noticeRepository.findAllByGroupId(groupId));
        notices.sort(ORDER);
        if (notices.isEmpty()) return List.of();

        Map<UUID, Set<UUID>> readers = readers(notices, members);
        var polls = pollService.views(notices, userId, members);
        boolean canManage = access.isOwner(group, userId);
        return notices.stream().map(notice -> {
            Set<UUID> read = readers.getOrDefault(notice.getId(), Set.of());
            var poll = polls.get(notice.getId());
            return new NoticeSummary(notice.getId(), notice.getTitle(), preview(notice.getContent()),
                    noticeUser(notice.getAuthor()), notice.getCreatedAt(), notice.getUpdatedAt(), notice.getPinnedAt(),
                    read.size(), members.size(), read.contains(userId),
                    poll != null, poll != null && poll.closed(), poll != null && poll.myVoted(),
                    canManage, poll == null ? null : poll.response().closesAt());
        }).toList();
    }

    @Transactional
    public NoticeDetail create(UUID userId, UUID groupId, CreateRequest request) {
        Group group = access.requireMember(groupId, userId);
        access.requireOwner(group, userId, "방장만 공지를 올릴 수 있어요.");
        LocalDateTime now = LocalDateTime.now();
        User me = userRepository.getReferenceById(userId);
        GroupNotice notice = noticeRepository.save(GroupNotice.builder()
                .group(group).author(me)
                .title(request.title().trim())
                .content(request.content())
                .createdAt(now).updatedAt(now)
                .build());
        if (request.poll() != null) {
            pollService.createPoll(notice, request.poll());
        }
        // 쓴 사람은 읽은 것으로 둔다(자기 공지에 안 읽음 점이 뜨지 않게).
        readRepository.save(GroupNoticeRead.builder().notice(notice).user(me).readAt(now).build());
        noticeRepository.flush();

        List<User> members = access.members(groupId);
        access.notify(members, userId, groupId, notice.getId(), nickname(members, userId) + "님이 공지 '" + shortTitle(notice)
                + "'을(를) 올렸어요." + (request.poll() != null ? " 투표에 참여해 주세요." : ""));
        access.publishChange(groupId, members);
        return detail(group, notice, members, userId);
    }

    /**
     * @param markRead 보는 사람을 읽음으로 기록한다(GET 상세). 투표 경로가 응답을 만들 때는 false.
     */
    @Transactional
    public NoticeDetail detail(UUID userId, UUID groupId, UUID noticeId, boolean markRead) {
        Group group = access.requireMember(groupId, userId);
        GroupNotice notice = access.findNotice(groupId, noticeId, false);
        if (markRead && !readRepository.existsByNotice_IdAndUser_Id(noticeId, userId)) {
            // 같은 사람이 두 탭에서 동시에 열어도 읽음 행이 하나만 생기도록 공지 행을 잠그고 다시 본다.
            access.findNotice(groupId, noticeId, true);
            if (!readRepository.existsByNotice_IdAndUser_Id(noticeId, userId)) {
                readRepository.saveAndFlush(GroupNoticeRead.builder()
                        .notice(notice).user(userRepository.getReferenceById(userId))
                        .readAt(LocalDateTime.now()).build());
                // 읽음 수는 방장 화면에만 보이므로 방장에게, 그리고 본인의 다른 탭·기기(안 읽음 점·숫자)에 보낸다.
                access.publishChange(groupId, List.of(group.getCreatedBy(), userRepository.getReferenceById(userId)));
            }
        }
        return detail(group, notice, access.members(groupId), userId);
    }

    @Transactional
    public NoticeDetail update(UUID userId, UUID groupId, UUID noticeId, UpdateRequest request) {
        Group group = access.requireMember(groupId, userId);
        access.requireOwner(group, userId, "방장만 공지를 고칠 수 있어요.");
        GroupNotice notice = access.findNotice(groupId, noticeId, true);
        notice.edit(request.title().trim(), request.content());
        noticeRepository.flush();
        List<User> members = access.members(groupId);
        access.publishChange(groupId, members);
        return detail(group, notice, members, userId);
    }

    /** 공지와 읽음·투표를 지운다(자식 행은 DB 의 ON DELETE CASCADE). */
    @Transactional
    public void delete(UUID userId, UUID groupId, UUID noticeId) {
        Group group = access.requireMember(groupId, userId);
        access.requireOwner(group, userId, "방장만 공지를 지울 수 있어요.");
        GroupNotice notice = access.findNotice(groupId, noticeId, true);
        noticeRepository.delete(notice);
        noticeRepository.flush();
        access.publishChange(groupId, access.members(groupId));
    }

    /** 맨 위 고정. 다시 고정해도 처음 시각을 지킨다. */
    @Transactional
    public NoticeDetail pin(UUID userId, UUID groupId, UUID noticeId, boolean pin) {
        Group group = access.requireMember(groupId, userId);
        access.requireOwner(group, userId, "방장만 공지를 고정할 수 있어요.");
        GroupNotice notice = access.findNotice(groupId, noticeId, true);
        if (pin && notice.getPinnedAt() == null) {
            notice.changePinnedAt(LocalDateTime.now());
        } else if (!pin && notice.getPinnedAt() != null) {
            notice.changePinnedAt(null);
        }
        noticeRepository.flush();
        List<User> members = access.members(groupId);
        access.publishChange(groupId, members);
        return detail(group, notice, members, userId);
    }

    // ─── 응답 조립 ──────────────────────────────────────────────────

    private NoticeDetail detail(Group group, GroupNotice notice, List<User> members, UUID viewerId) {
        Set<UUID> read = readers(List.of(notice), members).getOrDefault(notice.getId(), Set.of());
        List<NoticeUser> readers = new ArrayList<>(), notReaders = new ArrayList<>();
        for (User u : members) {
            (read.contains(u.getId()) ? readers : notReaders).add(noticeUser(u));
        }
        var poll = pollService.views(List.of(notice), viewerId, members).get(notice.getId());
        return new NoticeDetail(notice.getId(), notice.getTitle(), preview(notice.getContent()),
                noticeUser(notice.getAuthor()), notice.getCreatedAt(), notice.getUpdatedAt(), notice.getPinnedAt(),
                readers.size(), members.size(), read.contains(viewerId),
                poll != null, poll != null && poll.closed(), poll != null && poll.myVoted(),
                access.isOwner(group, viewerId),
                notice.getContent(), readers, notReaders,
                poll == null ? null : poll.response());
    }

    /** 공지 ID → 읽은 지금 멤버의 ID (한 번에 읽는다) */
    private Map<UUID, Set<UUID>> readers(Collection<GroupNotice> notices, List<User> members) {
        Set<UUID> memberIds = new HashSet<>();
        members.forEach(u -> memberIds.add(u.getId()));
        Map<UUID, Set<UUID>> result = new HashMap<>();
        for (GroupNoticeRead r : readRepository.findAllByNoticeIds(notices.stream().map(GroupNotice::getId).toList())) {
            UUID uid = r.getUser().getId();
            if (memberIds.contains(uid)) {
                result.computeIfAbsent(r.getNotice().getId(), k -> new HashSet<>()).add(uid);
            }
        }
        return result;
    }

    /** 알림 문구에 쓸, 실제로 행동한 사람의 닉네임 */
    private String nickname(List<User> members, UUID userId) {
        return members.stream().filter(u -> u.getId().equals(userId)).findFirst()
                .orElseGet(() -> userRepository.getReferenceById(userId)).getNickname();
    }

    static NoticeUser noticeUser(User user) {
        return new NoticeUser(user.getId(), user.getNickname(), user.getProfileImage());
    }

    private static String shortTitle(GroupNotice notice) {
        String title = notice.getTitle().trim();
        return CodePointSizeValidator.length(title) > 30 ? CodePointSizeValidator.truncate(title, 30) + "…" : title;
    }

    // ─── 미리보기 ───────────────────────────────────────────────────

    private static final Pattern CODE_FENCE = Pattern.compile("(?m)^\\s*(```|~~~).*$");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern LINE_PREFIX = Pattern.compile(
            "(?m)^\\s*(#{1,6}\\s+|>+\\s?|[-*+]\\s+\\[[ xX]]\\s+|[-*+]\\s+|\\d+[.)]\\s+)");
    private static final Pattern RULE = Pattern.compile("(?m)^\\s*([-*_]\\s*){3,}$|^\\s*\\|?\\s*:?-{3,}.*$");
    private static final Pattern EMPHASIS = Pattern.compile("[*_~`|\\\\]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** 내용 앞 100자(마크다운 기호 제거, 줄바꿈은 빈칸 하나) */
    static String preview(String content) {
        if (content == null || content.isBlank()) return "";
        String text = CODE_FENCE.matcher(content).replaceAll(" ");
        text = IMAGE.matcher(text).replaceAll("$1");
        text = LINK.matcher(text).replaceAll("$1");
        text = HTML_TAG.matcher(text).replaceAll(" ");
        text = RULE.matcher(text).replaceAll(" ");
        text = LINE_PREFIX.matcher(text).replaceAll("");
        text = EMPHASIS.matcher(text).replaceAll(" ");
        text = text.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        text = SPACES.matcher(text).replaceAll(" ").trim();
        return CodePointSizeValidator.length(text) > PREVIEW_LENGTH
                ? CodePointSizeValidator.truncate(text, PREVIEW_LENGTH) : text;
    }
}
