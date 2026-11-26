package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.*;
import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.GroupBlock;
import com.moyeolog.moyelog_BE.entity.GroupInvitation;
import com.moyeolog.moyelog_BE.entity.GroupMember;
import com.moyeolog.moyelog_BE.entity.GroupTopic;
import com.moyeolog.moyelog_BE.entity.Memo;
import com.moyeolog.moyelog_BE.entity.Schedule;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.enums.InvitationStatus;
import com.moyeolog.moyelog_BE.event.ChangeEvent;
import com.moyeolog.moyelog_BE.event.ChangeEvents;
import com.moyeolog.moyelog_BE.event.LiveTopics;
import com.moyeolog.moyelog_BE.event.NotificationEvent;
import com.moyeolog.moyelog_BE.event.NotificationType;
import com.moyeolog.moyelog_BE.datepoll.DatePollRepository;
import com.moyeolog.moyelog_BE.datepoll.DatePollVoteRepository;
import com.moyeolog.moyelog_BE.event.UserDeletingEvent;
import com.moyeolog.moyelog_BE.live.LiveAudience;
import com.moyeolog.moyelog_BE.repository.*;
import com.moyeolog.moyelog_BE.validation.CodePointSizeValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.moyeolog.moyelog_BE.service.GroupErrors.*;

@Service
@RequiredArgsConstructor
public class GroupService {
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final GroupInvitationRepository groupInvitationRepository;
    private final MemoRepository memoRepository;
    private final GroupTopicRepository groupTopicRepository;
    private final ScheduleRepository scheduleRepository;
    private final GroupTopicCommentRepository groupTopicCommentRepository;
    private final FileService fileService;
    private final GroupBlockRepository groupBlockRepository;
    private final FriendRepository friendRepository;
    private final GroupImageService groupImageService;
    private final DatePollRepository datePollRepository;
    private final DatePollVoteRepository datePollVoteRepository;
    private final MemoGroupShareRepository memoGroupShareRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 프론트 lib/constants.ts THEME_COLORS 의 키 */
    public static final Set<String> COLOR_THEMES = Set.of("indigo", "blue", "emerald", "orange", "rose", "amber", "purple");
    public static final String DEFAULT_COLOR_THEME = "indigo";
    static final int NAME_MAX = 50;
    /** 거절한 사람에게 일반 멤버가 다시 초대할 수 있기까지 기다리는 기간 */
    static final int REINVITE_COOLDOWN_DAYS = 7;

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 6;
    private static final SecureRandom random = new SecureRandom();

    @Transactional
    public GroupResponse createGroup(UUID userId, GroupRequest request) {
        User creator = findUserById(userId);

        Group group = Group.builder()
                .name(normalizeName(request.getName()))
                .description(request.getDescription())
                .colorTheme(normalizeColorTheme(request.getColorTheme(), DEFAULT_COLOR_THEME))
                .inviteCode(generateUniqueInviteCode())
                .createdBy(creator)
                .build();

        Group savedGroup = groupRepository.save(group);

        // 생성자를 첫 번째 멤버로 추가
        groupMemberRepository.save(GroupMember.builder()
                .group(savedGroup)
                .user(creator)
                .build());
        eventPublisher.publishEvent(ChangeEvent.of(List.of(userId), LiveTopics.GROUPS));

        return convertToResponse(savedGroup, userId);
    }

    /** 이름: trim 후 1~50자(코드 포인트), 보이지 않는 문자만으로 된 이름은 거절 */
    static String normalizeName(String name) {
        if (name == null || isVisuallyBlank(name)) {
            throw badRequest("모임 이름을 입력해 주세요.");
        }
        String trimmed = name.strip();
        if (CodePointSizeValidator.length(trimmed) > NAME_MAX) {
            throw badRequest("모임 이름은 50자까지 쓸 수 있어요.");
        }
        return trimmed;
    }

    /** colorTheme: 정해진 키만(대소문자 무시). 비어 있으면 fallback */
    static String normalizeColorTheme(String colorTheme, String fallback) {
        if (colorTheme == null || colorTheme.isBlank()) {
            return fallback;
        }
        String value = colorTheme.trim().toLowerCase(Locale.ROOT);
        if (!COLOR_THEMES.contains(value)) {
            throw badRequest("모임 색을 다시 골라 주세요.");
        }
        return value;
    }

    /** 응답용: 검사 전에 저장된 옛 값(null·hex 등)은 기본 색으로 내려 준다 */
    static String displayColorTheme(String stored) {
        if (stored == null) {
            return DEFAULT_COLOR_THEME;
        }
        String value = stored.trim().toLowerCase(Locale.ROOT);
        return COLOR_THEMES.contains(value) ? value : DEFAULT_COLOR_THEME;
    }

    private String generateUniqueInviteCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
            }
            code = sb.toString();
        } while (groupRepository.findByInviteCode(code).isPresent());
        return code;
    }

    private Group findGroupByCode(String inviteCode) {
        if (inviteCode == null || inviteCode.isBlank()) {
            throw notFound(INVALID_INVITE_CODE);
        }
        String code = inviteCode.trim().toUpperCase();
        if (code.length() != CODE_LENGTH) {
            throw notFound(INVALID_INVITE_CODE);
        }
        return groupRepository.findByInviteCode(code).orElseThrow(() -> notFound(INVALID_INVITE_CODE));
    }

    /** 초대 코드로 모임 정보 미리 보기 (가입 전 확인 화면용) */
    @Transactional(readOnly = true)
    public GroupPreviewResponse previewByCode(UUID userId, String inviteCode) {
        Group group = findGroupByCode(inviteCode);
        return GroupPreviewResponse.builder()
                .id(group.getId())
                .name(group.getName())
                .description(group.getDescription())
                .colorTheme(displayColorTheme(group.getColorTheme()))
                .profileImage(group.getProfileImage())
                .backgroundImage(group.getBackgroundImage())
                .memberCount(distinctMembers(group).size())
                .ownerNickname(group.getCreatedBy().getNickname())
                .alreadyMember(isMember(group.getId(), userId))
                .blocked(groupBlockRepository.existsByGroup_IdAndUser_Id(group.getId(), userId))
                .build();
    }

    /** 초대 링크를 공유했을 때 미리보기에 쓸 정보. 로그인 없이 보이므로 이름·소개·인원·방장 닉네임만 준다. */
    @Transactional(readOnly = true)
    public LinkPreviewResponse linkPreviewByCode(String inviteCode) {
        Group group = findGroupByCode(inviteCode);
        return new LinkPreviewResponse(group.getName(), group.getDescription(),
                distinctMembers(group).size(), group.getCreatedBy().getNickname());
    }

    @Transactional
    public GroupJoinResponse joinGroupByCode(UUID userId, String inviteCode) {
        if (inviteCode == null || inviteCode.isBlank()) {
            throw badRequest("초대 코드를 입력해 주세요.");
        }
        Group found = findGroupByCode(inviteCode);
        // 같은 모임의 가입·초대·내보내기를 한 줄로 세운다 (동시 가입 시 멤버십 1개)
        Group group = lockGroup(found.getId());
        User user = findUserById(userId);

        if (isMember(group.getId(), userId)) {
            throw new AlreadyMemberException(group.getId());
        }
        if (groupBlockRepository.existsByGroup_IdAndUser_Id(group.getId(), userId)) {
            throw forbidden(BLOCKED);
        }

        groupMemberRepository.save(GroupMember.builder()
                .group(group)
                .user(user)
                .build());
        // 먼저 받아 둔 이 모임 대기 초대는 정리
        groupInvitationRepository.deletePendingForInvitee(group.getId(), userId);
        // 가입한 사람은 받은 초대 목록에서도 이 모임 초대가 빠진다(같은 이벤트에 담는다)
        membershipChange(group.getId()).add(List.of(userId), LiveTopics.NOTIFICATIONS).publish(eventPublisher);
        return GroupJoinResponse.builder().groupId(group.getId()).build();
    }

    @Transactional(readOnly = true)
    public List<GroupResponse> getMyGroups(UUID userId) {
        User user = findUserById(userId);

        // 과거 데이터에 중복 멤버십이 있어도 모임이 두 번 나오지 않도록 한다.
        Map<UUID, Group> groups = new LinkedHashMap<>();
        for (GroupMember membership : groupMemberRepository.findByUser(user)) {
            groups.putIfAbsent(membership.getGroup().getId(), membership.getGroup());
        }
        return groups.values().stream()
                .map(group -> convertToResponse(group, userId))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public GroupResponse getGroup(UUID userId, UUID groupId) {
        Group group = findGroupById(groupId);
        requireMember(groupId, userId);
        return convertToResponse(group, userId);
    }

    @Transactional(readOnly = true)
    public List<GroupActivityResponse> getRecentActivities(UUID userId) {
        User user = findUserById(userId);

        List<UUID> groupIds = groupMemberRepository.findByUser(user).stream()
                .map(m -> m.getGroup().getId())
                .distinct()
                .collect(Collectors.toList());

        if (groupIds.isEmpty()) return new ArrayList<>();

        Map<UUID, String> groupNames = new LinkedHashMap<>();
        for (Group group : groupRepository.findAllById(groupIds)) {
            groupNames.put(group.getId(), group.getName());
        }

        // 1. 최근 메모
        List<GroupActivityResponse> memoActivities = memoRepository.findByGroupIdInOrderByCreatedAtDesc(groupIds).stream()
                .limit(20)
                .map(memo -> GroupActivityResponse.builder()
                        .type("MEMO")
                        .groupId(memo.getGroupId())
                        .groupName(groupNames.getOrDefault(memo.getGroupId(), "알 수 없는 모임"))
                        .id(memo.getId())
                        .title(memo.getTitle())
                        .contentSnippet(truncateContent(memo.getContent()))
                        .authorNickname(memo.getAuthor().getNickname())
                        .authorProfileImage(memo.getAuthor().getProfileImage())
                        .createdAt(memo.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        // 2. 최근 토픽
        List<GroupActivityResponse> topicActivities = groupTopicRepository.findByGroup_IdInOrderByCreatedAtDesc(groupIds).stream()
                .limit(20)
                .map(topic -> GroupActivityResponse.builder()
                        .type("TOPIC")
                        .groupId(topic.getGroup().getId())
                        .groupName(topic.getGroup().getName())
                        .id(topic.getId())
                        .title(topic.getTitle())
                        .contentSnippet(truncateContent(topic.getContent() == null ? "" : topic.getContent().replaceAll("[#*`]", "")))
                        .authorNickname(topic.getAuthor().getNickname())
                        .authorProfileImage(topic.getAuthor().getProfileImage())
                        .createdAt(topic.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        // 3. 최근 일정
        List<GroupActivityResponse> scheduleActivities = scheduleRepository.findByGroupIdInOrderByCreatedAtDesc(groupIds).stream()
                .limit(20)
                .map(schedule -> GroupActivityResponse.builder()
                        .type("SCHEDULE")
                        .groupId(schedule.getGroupId())
                        .groupName(groupNames.getOrDefault(schedule.getGroupId(), "알 수 없는 모임"))
                        .id(schedule.getId())
                        .title(schedule.getTitle())
                        .contentSnippet(formatDateTime(schedule.getStartTime())
                                + (schedule.getLocation() != null && !schedule.getLocation().isBlank() ? " · " + schedule.getLocation() : ""))
                        .authorNickname(schedule.getAuthor().getNickname())
                        .authorProfileImage(schedule.getAuthor().getProfileImage())
                        .createdAt(schedule.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        // 4. 최근 댓글
        List<GroupActivityResponse> commentActivities = groupTopicCommentRepository.findByTopic_Group_IdInOrderByCreatedAtDesc(groupIds).stream()
                .limit(20)
                .map(comment -> GroupActivityResponse.builder()
                        .type("COMMENT")
                        .groupId(comment.getTopic().getGroup().getId())
                        .groupName(comment.getTopic().getGroup().getName())
                        .id(comment.getTopic().getId()) // 토픽 상세로 이동할 수 있게 토픽 ID 사용
                        .title("\"" + comment.getTopic().getTitle() + "\" 토픽에 댓글이 달렸어요")
                        .contentSnippet(truncateContent(comment.getContent()))
                        .authorNickname(comment.getAuthor().getNickname())
                        .authorProfileImage(comment.getAuthor().getProfileImage())
                        .createdAt(comment.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        return Stream.of(memoActivities, topicActivities, scheduleActivities, commentActivities)
                .flatMap(List::stream)
                .sorted(Comparator.comparing(GroupActivityResponse::getCreatedAt,
                        Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder())).reversed())
                .limit(40)
                .collect(Collectors.toList());
    }

    private String truncateContent(String content) {
        if (content == null) return "";
        return CodePointSizeValidator.length(content) > 100 ? CodePointSizeValidator.truncate(content, 100) + "..." : content;
    }

    private String formatDateTime(LocalDateTime dateTime) {
        return dateTime == null ? "" : dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    /**
     * 모임 초대. userIds(친구의 사용자 ID)만 받는다.
     * - emails 는 지원 종료(400): 이메일로 가입 여부를 알아내는 경로를 막는다.
     * - 초대하는 사람은 멤버여야 하고(403), 대상은 초대하는 사람의 친구여야 한다(400, 없는 사용자도 같은 응답).
     * - 이미 멤버이거나 대기 초대가 있는 사람은 건너뛴다(같은 사람 대기 초대 1건).
     * - 내보내진 사람은 방장만 다시 초대할 수 있고(그 외 403), 방장이 초대하면 차단이 풀린다.
     * - 최근에 거절한 사람은 일반 멤버가 일정 기간 다시 초대하지 못한다(건너뜀, 아무도 초대되지 않으면 409).
     */
    @Transactional
    public void inviteMembers(UUID inviterId, UUID groupId, GroupInviteRequest request) {
        if (request != null && request.getEmails() != null) {
            throw badRequest("이메일 초대는 더 이상 지원하지 않아요. 친구를 골라 초대해 주세요.");
        }
        Set<UUID> userIds = new LinkedHashSet<>();
        if (request != null && request.getUserIds() != null) {
            request.getUserIds().stream().filter(id -> id != null).forEach(userIds::add);
        }
        if (userIds.isEmpty()) {
            throw badRequest("초대할 친구를 한 명 이상 골라 주세요.");
        }

        findGroupById(groupId);
        if (!isMember(groupId, inviterId)) {
            throw forbidden("모임 멤버만 친구를 초대할 수 있어요.");
        }
        // 같은 사람을 동시에 여러 번 초대해도 대기 초대가 1건만 생기도록 모임 단위로 줄 세운다.
        Group group = lockGroup(groupId);
        User inviter = findUserById(inviterId);
        boolean inviterIsOwner = group.getCreatedBy().getId().equals(inviterId);
        userIds.remove(inviterId);

        for (UUID userId : userIds) {
            if (!friendRepository.areFriends(inviterId, userId)) {
                throw badRequest("친구에게만 초대를 보낼 수 있어요.");
            }
        }

        LocalDateTime cooldownFrom = LocalDateTime.now().minusDays(REINVITE_COOLDOWN_DAYS);
        int created = 0;
        int coolingDown = 0;
        for (UUID userId : userIds) {
            User invitee = findUserById(userId);
            if (isMember(groupId, userId)) continue;

            if (groupBlockRepository.existsByGroup_IdAndUser_Id(groupId, userId)) {
                if (!inviterIsOwner) {
                    throw forbidden(BLOCKED);
                }
                groupBlockRepository.deleteBlock(groupId, userId);
            }
            if (groupInvitationRepository.existsByGroupAndInviteeAndStatus(group, invitee, InvitationStatus.PENDING)) continue;
            if (!inviterIsOwner && groupInvitationRepository.existsByGroup_IdAndInvitee_IdAndStatusAndRespondedAtAfter(
                    groupId, userId, InvitationStatus.REJECTED, cooldownFrom)) {
                coolingDown++;
                continue;
            }

            groupInvitationRepository.save(GroupInvitation.builder()
                    .group(group)
                    .inviter(inviter)
                    .invitee(invitee)
                    .status(InvitationStatus.PENDING)
                    .build());
            created++;
            // 받은 초대 목록(알림 화면의 초대 탭). 초대 알림을 꺼 둔 사람도 목록은 바뀐다.
            eventPublisher.publishEvent(ChangeEvent.of(List.of(userId), LiveTopics.NOTIFICATIONS));
            eventPublisher.publishEvent(new NotificationEvent(userId, inviterId, NotificationType.GROUP_INVITE,
                    inviter.getNickname() + "님이 \"" + group.getName() + "\" 모임에 초대했어요.",
                    "/notifications?tab=invites"));
        }
        if (created == 0 && coolingDown > 0) {
            throw conflict("최근에 초대를 거절한 친구예요. 며칠 뒤에 다시 초대해 주세요.");
        }
    }

    @Transactional(readOnly = true)
    public List<GroupInvitationResponse> getMyInvitations(UUID userId) {
        User user = findUserById(userId);

        return groupInvitationRepository.findByInviteeAndStatus(user, InvitationStatus.PENDING).stream()
                .map(i -> GroupInvitationResponse.builder()
                        .id(i.getId())
                        .groupId(i.getGroup().getId())
                        .groupName(i.getGroup().getName())
                        .inviterNickname(i.getInviter().getNickname())
                        .status(i.getStatus())
                        .invitedAt(i.getInvitedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional
    public void acceptInvitation(UUID userId, UUID invitationId) {
        GroupInvitation invitation = lockPendingInvitation(userId, invitationId);
        UUID groupId = invitation.getGroup().getId();

        if (groupBlockRepository.existsByGroup_IdAndUser_Id(groupId, userId)) {
            throw forbidden(BLOCKED);
        }

        invitation.accept();

        // 이미 멤버라면(초대 코드로 먼저 가입한 경우 등) 멤버십을 또 만들지 않는다.
        if (!isMember(groupId, userId)) {
            groupMemberRepository.save(GroupMember.builder()
                    .group(invitation.getGroup())
                    .user(invitation.getInvitee())
                    .build());
        }
        // 같은 모임의 다른 대기 초대(과거 중복 데이터)도 정리
        groupInvitationRepository.deletePendingForInvitee(groupId, userId);
        membershipChange(groupId).add(List.of(userId), LiveTopics.NOTIFICATIONS).publish(eventPublisher);
    }

    @Transactional
    public void rejectInvitation(UUID userId, UUID invitationId) {
        GroupInvitation invitation = lockPendingInvitation(userId, invitationId);
        invitation.reject();
        eventPublisher.publishEvent(ChangeEvent.of(List.of(userId), LiveTopics.NOTIFICATIONS));
    }

    /**
     * 본인에게 온 대기 초대를 잠근 채로 돌려준다.
     * 같은 모임의 작업과 줄을 세우려고 모임 행을 먼저 잠그고, 그 뒤 초대를 새로 읽는다
     * (수락·거절이 동시에 와도 하나만 성공).
     */
    private GroupInvitation lockPendingInvitation(UUID userId, UUID invitationId) {
        String gone = "초대를 찾을 수 없어요. 이미 취소됐을 수 있어요.";
        UUID groupId = groupInvitationRepository.findGroupIdById(invitationId).orElseThrow(() -> notFound(gone));
        groupRepository.findByIdForUpdate(groupId).orElseThrow(() -> notFound(gone));
        GroupInvitation invitation = groupInvitationRepository.findByIdForUpdate(invitationId).orElseThrow(() -> notFound(gone));

        if (!invitation.getInvitee().getId().equals(userId)) {
            throw forbidden("본인에게 온 초대만 처리할 수 있어요.");
        }
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw conflict("이미 처리한 초대예요.");
        }
        return invitation;
    }

    /**
     * 모임 정보(이름/설명/테마/이미지) 수정은 방장만 가능합니다.
     * 사진: 새 파일이 있으면 교체, 없고 remove 플래그가 true 면 지운다. 이전 파일은 커밋 뒤 삭제.
     */
    @Transactional
    public GroupResponse updateGroup(UUID userId, UUID groupId, GroupRequest request,
                                     MultipartFile image, MultipartFile bgImage) {
        Group group = findGroupById(groupId);
        requireOwner(group, userId, "방장만 모임 정보를 고칠 수 있어요.");

        String name = request.getName() != null ? normalizeName(request.getName()) : null;
        String colorTheme = normalizeColorTheme(request.getColorTheme(), null);

        // 두 파일을 저장하기 전에 모두 검사한다 (하나만 저장되고 실패하는 고아 파일 방지).
        groupImageService.requireImage(image);
        groupImageService.requireImage(bgImage);

        if (name != null) group.setName(name);
        if (request.getDescription() != null) group.setDescription(request.getDescription());
        if (colorTheme != null) group.setColorTheme(colorTheme);

        // 저장한 파일은 롤백되면 지우고, 이전 파일은 커밋 후 삭제한다.
        String newProfileImage = (image != null && !image.isEmpty()) ? groupImageService.store(image) : null;
        String newBackgroundImage = (bgImage != null && !bgImage.isEmpty()) ? groupImageService.store(bgImage) : null;

        if (newProfileImage != null || Boolean.TRUE.equals(request.getRemoveProfileImage())) {
            String previous = group.getProfileImage();
            group.setProfileImage(newProfileImage);
            fileService.deleteFileAfterCommit(previous);
        }
        if (newBackgroundImage != null || Boolean.TRUE.equals(request.getRemoveBackgroundImage())) {
            String previous = group.getBackgroundImage();
            group.setBackgroundImage(newBackgroundImage);
            fileService.deleteFileAfterCommit(previous);
        }
        eventPublisher.publishEvent(ChangeEvent.of(members(groupId), LiveTopics.GROUPS, LiveTopics.group(groupId)));

        return convertToResponse(group, userId);
    }

    /**
     * 멤버 내보내기 (방장 전용).
     * 내보낸 사람은 차단 목록에 들어가고, 그 사람이 받은·보낸 이 모임 대기 초대는 정리한다.
     */
    @Transactional
    public void kickMember(UUID ownerId, UUID groupId, UUID targetMemberId) {
        Group group = lockGroup(groupId);
        requireOwner(group, ownerId, "방장만 멤버를 내보낼 수 있어요.");

        if (ownerId.equals(targetMemberId)) {
            throw badRequest("자기 자신은 내보낼 수 없어요.");
        }

        // 내보내진 사람의 화면에서도 모임이 사라져야 하므로 지우기 전 멤버에게 알린다.
        ChangeEvents changes = membershipChange(groupId);
        // 동시에 나가기·내보내기가 와도 예외 없이 지운 행 수로 판단한다.
        if (groupMemberRepository.deleteMembership(groupId, targetMemberId) == 0) {
            throw notFound("모임 멤버를 찾을 수 없어요. 이미 나갔을 수 있어요.");
        }
        removeFromGroupSchedules(groupId, targetMemberId, changes);
        datePollVoteRepository.deleteAllByGroupAndUser(groupId, targetMemberId);
        // 내보내진 사람이 이 모임에 공유한 개인 메모는 거둔다.
        memoGroupShareRepository.deleteAllByGroupIdAndAuthorId(groupId, targetMemberId);

        if (!groupBlockRepository.existsByGroup_IdAndUser_Id(groupId, targetMemberId)) {
            groupBlockRepository.save(GroupBlock.builder()
                    .group(group)
                    .user(userRepository.getReferenceById(targetMemberId))
                    .blockedBy(group.getCreatedBy())
                    .build());
        }
        groupInvitationRepository.deletePendingForInvitee(groupId, targetMemberId);
        groupInvitationRepository.deletePendingFromInviter(groupId, targetMemberId);

        // 내보낸 멤버가 알고 있는 초대 코드는 더 이상 쓰지 못하도록 새로 발급한다.
        group.setInviteCode(generateUniqueInviteCode());
        changes.publish(eventPublisher);

        eventPublisher.publishEvent(new NotificationEvent(targetMemberId, ownerId, NotificationType.GROUP_KICKED,
                "\"" + group.getName() + "\" 모임에서 내보내졌어요.", "/groups"));
    }

    /** 모임 나가기. 방장은 나갈 수 없습니다 (모임을 삭제해야 함). */
    @Transactional
    public void leaveGroup(UUID userId, UUID groupId) {
        Group group = lockGroup(groupId);

        if (group.getCreatedBy().getId().equals(userId)) {
            throw badRequest("방장은 모임을 나갈 수 없어요. 모임을 삭제해 주세요.");
        }

        ChangeEvents changes = membershipChange(groupId);
        if (groupMemberRepository.deleteMembership(groupId, userId) == 0) {
            throw forbidden(NOT_MEMBER);
        }
        removeFromGroupSchedules(groupId, userId, changes);
        datePollVoteRepository.deleteAllByGroupAndUser(groupId, userId);
        // 나간 사람이 이 모임에 공유한 개인 메모는 거둔다.
        memoGroupShareRepository.deleteAllByGroupIdAndAuthorId(groupId, userId);
        // 나간 사람이 보낸 대기 초대는 더 이상 유효하지 않다.
        groupInvitationRepository.deletePendingFromInviter(groupId, userId);
        changes.publish(eventPublisher);
    }

    /**
     * 모임 삭제 (방장 전용).
     * 멤버/초대/차단/토픽(댓글·AI 분석 포함)은 삭제하고,
     * 모임 메모와 모임 일정은 삭제하지 않고 모임에서 분리하여(group_id = NULL) 작성자의 개인 메모/일정으로 남깁니다.
     */
    @Transactional
    public void deleteGroup(UUID userId, UUID groupId) {
        // 동시에 두 번 삭제하면 두 번째는 잠금을 기다린 뒤 404
        Group group = lockGroup(groupId);
        requireOwner(group, userId, "방장만 모임을 삭제할 수 있어요.");
        ChangeEvents changes = membershipChange(groupId);
        // 모임 일정은 작성자의 개인 일정이 되므로 그 일정을 보던 사람(멤버)에게 schedule:{id} 도
        for (Schedule schedule : scheduleRepository.findAllByGroupIdOrderByStartTimeAsc(groupId)) {
            LiveAudience.addSchedule(changes, schedule, groupMemberRepository);
        }
        deleteGroupInternal(group);
        // 모임 메모·일정은 작성자의 개인 것으로 남으므로 메모·일정 목록도 바뀐다.
        changes.publish(eventPublisher);
    }

    private Set<UUID> members(UUID groupId) {
        return new LinkedHashSet<>(groupMemberRepository.findUserIdsByGroupId(groupId));
    }

    /**
     * 멤버가 바뀜(가입·탈퇴·내보내기·삭제): 지금 멤버에게 내 모임 목록, 그 모임, 그리고 모임 메모·일정이 보이거나 안 보이게 되므로
     * 메모·일정 목록도. 나가기·내보내기·삭제는 바꾸기 전에, 가입은 바꾼 뒤에 불러 변경 전·후 멤버가 모두 받게 한다.
     */
    private ChangeEvents membershipChange(UUID groupId) {
        return new ChangeEvents().add(members(groupId), LiveTopics.GROUPS, LiveTopics.group(groupId),
                LiveTopics.SCHEDULES, LiveTopics.MEMOS);
    }

    private void deleteGroupInternal(Group group) {
        UUID groupId = group.getId();
        for (Memo memo : memoRepository.findByGroupIdOrderByCreatedAtDesc(groupId)) {
            memo.detachFromGroup();
        }
        // 이 모임에 공유한 개인 메모는 공유만 지운다(메모는 작성자에게 남는다).
        memoGroupShareRepository.deleteAllByGroupId(groupId);
        for (Schedule schedule : scheduleRepository.findAllByGroupIdOrderByStartTimeAsc(groupId)) {
            schedule.detachFromGroup();
        }

        List<GroupTopic> topics = groupTopicRepository.findAllByGroup(group);
        List<String> topicImages = topics.stream().map(GroupTopic::getImageUrl).toList();
        groupTopicRepository.deleteAll(topics); // 댓글, AI 분석 결과는 cascade 로 함께 삭제
        // 날짜 조율과 선택. 조율로 만든 일정은 위에서 개인 일정으로 남겼다.
        datePollVoteRepository.deleteAllByGroupId(groupId);
        datePollRepository.deleteAllByGroupId(groupId);

        groupInvitationRepository.deleteAllByGroup(group);
        groupBlockRepository.deleteAllByGroup(group);
        groupMemberRepository.deleteAllByGroup(group);

        String profileImage = group.getProfileImage();
        String backgroundImage = group.getBackgroundImage();

        // 자식 행 삭제를 먼저 반영한 뒤 모임을 삭제한다.
        groupRepository.flush();
        groupRepository.delete(group);

        fileService.deleteFileAfterCommit(profileImage);
        fileService.deleteFileAfterCommit(backgroundImage);
        topicImages.forEach(fileService::deleteFileAfterCommit);
    }

    /**
     * 회원 탈퇴 직전: 내가 방장인 모임은 가장 오래된 다른 멤버에게 넘기고(혼자면 모임 삭제),
     * 내가 받은·보낸 초대, 차단 기록, 멤버십을 지운다.
     * (내 토픽·댓글은 GroupTopicService 리스너가 먼저 지운다)
     */
    @EventListener
    @Order(20)
    @Transactional
    public void onUserDeleting(UserDeletingEvent event) {
        UUID userId = event.userId();
        for (Group owned : groupRepository.findAllByCreatedBy_Id(userId)) {
            Group group = lockGroup(owned.getId());
            User successor = groupMemberRepository.findAllByGroup_IdOrderByJoinedAtAsc(group.getId()).stream()
                    .map(GroupMember::getUser)
                    .filter(u -> !u.getId().equals(userId))
                    .findFirst()
                    .orElse(null);
            if (successor == null) {
                deleteGroupInternal(group);
            } else {
                group.setCreatedBy(successor);
            }
        }
        groupRepository.flush();
        groupInvitationRepository.deleteAllInvolvingUser(userId);
        groupBlockRepository.deleteAllInvolvingUser(userId);
        groupMemberRepository.deleteAllByUserId(userId);
    }

    // ─── Internal Helpers ──────────────────────────────────────────

    private Group findGroupById(UUID groupId) {
        return groupRepository.findById(groupId).orElseThrow(() -> notFound(GROUP_NOT_FOUND));
    }

    private Group lockGroup(UUID groupId) {
        return groupRepository.findByIdForUpdate(groupId).orElseThrow(() -> notFound(GROUP_NOT_FOUND));
    }

    private User findUserById(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> notFound("사용자를 찾을 수 없어요."));
    }

    private boolean isMember(UUID groupId, UUID userId) {
        return groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId);
    }

    private void requireMember(UUID groupId, UUID userId) {
        if (!isMember(groupId, userId)) {
            throw forbidden(NOT_MEMBER);
        }
    }

    private void requireOwner(Group group, UUID userId, String message) {
        if (!group.getCreatedBy().getId().equals(userId)) {
            throw forbidden(message);
        }
    }

    /** 모임을 떠난 사용자를 그 모임 일정의 참여자 목록에서 제거합니다. */
    /** 나간·내보내진 사람을 모임 일정 참여자에서 뺀다. 참여자가 바뀐 일정은 changes 에 담는다(schedule:{id}). */
    private void removeFromGroupSchedules(UUID groupId, UUID userId, ChangeEvents changes) {
        for (Schedule schedule : scheduleRepository.findAllByGroupIdOrderByStartTimeAsc(groupId)) {
            if (schedule.getParticipants() != null
                    && schedule.getParticipants().stream().anyMatch(p -> p.getId().equals(userId))) {
                LiveAudience.addSchedule(changes, schedule, groupMemberRepository);
                schedule.getParticipants().removeIf(p -> p.getId().equals(userId));
            }
        }
    }

    /** 과거 데이터에 중복 멤버십 행이 있어도 멤버가 두 번 나오지 않도록 사용자 기준으로 중복을 제거한다. */
    private List<User> distinctMembers(Group group) {
        Map<UUID, User> members = new LinkedHashMap<>();
        for (GroupMember membership : groupMemberRepository.findByGroup(group)) {
            members.putIfAbsent(membership.getUser().getId(), membership.getUser());
        }
        return new ArrayList<>(members.values());
    }

    private GroupResponse convertToResponse(Group group, UUID viewerId) {
        List<GroupResponse.MemberResponse> memberResponses = distinctMembers(group).stream()
                .map(u -> GroupResponse.MemberResponse.builder()
                        .id(u.getId())
                        .customId(u.getCustomId())
                        .nickname(u.getNickname())
                        .profileImage(u.getProfileImage())
                        .build())
                .collect(Collectors.toList());

        boolean owner = group.getCreatedBy().getId().equals(viewerId);
        return GroupResponse.builder()
                .id(group.getId())
                .name(group.getName())
                .description(group.getDescription())
                .colorTheme(displayColorTheme(group.getColorTheme()))
                .createdById(group.getCreatedBy().getId())
                .createdByNickname(group.getCreatedBy().getNickname())
                .members(memberResponses)
                .inviteCode(group.getInviteCode())
                .profileImage(group.getProfileImage())
                .backgroundImage(group.getBackgroundImage())
                .memberCount(memberResponses.size())
                .createdAt(group.getCreatedAt())
                .myRole(owner ? "OWNER" : "MEMBER")
                .build();
    }
}
