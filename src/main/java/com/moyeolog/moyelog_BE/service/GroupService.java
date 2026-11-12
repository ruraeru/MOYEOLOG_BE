package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.*;
import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.GroupInvitation;
import com.moyeolog.moyelog_BE.entity.GroupMember;
import com.moyeolog.moyelog_BE.entity.GroupTopic;
import com.moyeolog.moyelog_BE.entity.Memo;
import com.moyeolog.moyelog_BE.entity.Schedule;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.enums.InvitationStatus;
import com.moyeolog.moyelog_BE.repository.*;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.ConflictException;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import com.moyeolog.moyelog_BE.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 6;
    private static final SecureRandom random = new SecureRandom();

    @Transactional
    public GroupResponse createGroup(UUID userId, GroupRequest request) {
        User creator = findUserById(userId);

        String inviteCode = generateUniqueInviteCode();

        Group group = Group.builder()
                .name(request.getName())
                .description(request.getDescription())
                .colorTheme(request.getColorTheme())
                .inviteCode(inviteCode)
                .createdBy(creator)
                .build();

        Group savedGroup = groupRepository.save(group);

        // 생성자를 첫 번째 멤버로 추가
        GroupMember member = GroupMember.builder()
                .group(savedGroup)
                .user(creator)
                .build();
        groupMemberRepository.save(member);

        return convertToResponse(savedGroup);
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

    @Transactional
    public void joinGroupByCode(UUID userId, String inviteCode) {
        if (inviteCode == null || inviteCode.isBlank()) {
            throw new BadRequestException("초대 코드를 입력해주세요.");
        }
        Group group = groupRepository.findByInviteCode(inviteCode.trim().toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("초대 코드", inviteCode.trim()));
        User user = findUserById(userId);

        if (isMember(group.getId(), userId)) {
            throw new ConflictException("이미 가입한 그룹입니다.");
        }

        groupMemberRepository.save(GroupMember.builder()
                .group(group)
                .user(user)
                .build());
    }

    @Transactional(readOnly = true)
    public List<GroupResponse> getMyGroups(UUID userId) {
        User user = findUserById(userId);

        // 과거 데이터에 중복 멤버십이 있어도 그룹이 두 번 나오지 않도록 한다.
        Map<UUID, Group> groups = new LinkedHashMap<>();
        for (GroupMember membership : groupMemberRepository.findByUser(user)) {
            groups.putIfAbsent(membership.getGroup().getId(), membership.getGroup());
        }
        return groups.values().stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public GroupResponse getGroup(UUID userId, UUID groupId) {
        Group group = findGroupById(groupId);
        requireMember(groupId, userId);
        return convertToResponse(group);
    }

    @Transactional(readOnly = true)
    public List<GroupActivityResponse> getRecentActivities(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));

        List<UUID> groupIds = groupMemberRepository.findByUser(user).stream()
                .map(m -> m.getGroup().getId())
                .collect(Collectors.toList());

        if (groupIds.isEmpty()) return new ArrayList<>();

        // 1. 최근 메모 가져오기
        List<GroupActivityResponse> memoActivities = memoRepository.findByGroupIdInOrderByCreatedAtDesc(groupIds).stream()
                .limit(20)
                .map(memo -> {
                    Group group = groupRepository.findById(memo.getGroupId()).orElse(null);
                    return GroupActivityResponse.builder()
                            .type("MEMO")
                            .groupId(memo.getGroupId())
                            .groupName(group != null ? group.getName() : "알 수 없는 그룹")
                            .id(memo.getId())
                            .title(memo.getTitle())
                            .contentSnippet(truncateContent(memo.getContent()))
                            .authorNickname(memo.getAuthor().getNickname())
                            .authorProfileImage(memo.getAuthor().getProfileImage())
                            .createdAt(memo.getCreatedAt())
                            .build();
                })
                .collect(Collectors.toList());

        // 2. 최근 토픽 가져오기
        List<GroupActivityResponse> topicActivities = groupTopicRepository.findByGroup_IdInOrderByCreatedAtDesc(groupIds).stream()
                .limit(20)
                .map(topic -> GroupActivityResponse.builder()
                        .type("TOPIC")
                        .groupId(topic.getGroup().getId())
                        .groupName(topic.getGroup().getName())
                        .id(topic.getId())
                        .title(topic.getTitle())
                        .contentSnippet(truncateContent(topic.getContent().replaceAll("[#*`]", "")))
                        .authorNickname(topic.getAuthor().getNickname())
                        .authorProfileImage(topic.getAuthor().getProfileImage())
                        .createdAt(topic.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        // 3. 최근 일정 가져오기
        List<GroupActivityResponse> scheduleActivities = scheduleRepository.findByGroupIdInOrderByCreatedAtDesc(groupIds).stream()
                .limit(20)
                .map(schedule -> {
                    Group group = groupRepository.findById(schedule.getGroupId()).orElse(null);
                    return GroupActivityResponse.builder()
                            .type("SCHEDULE")
                            .groupId(schedule.getGroupId())
                            .groupName(group != null ? group.getName() : "알 수 없는 그룹")
                            .id(schedule.getId())
                            .title(schedule.getTitle())
                            .contentSnippet("일정 시간: " + formatDateTime(schedule.getStartTime()) + (schedule.getLocation() != null ? " @ " + schedule.getLocation() : ""))
                            .authorNickname(schedule.getAuthor().getNickname())
                            .authorProfileImage(schedule.getAuthor().getProfileImage())
                            .createdAt(schedule.getCreatedAt())
                            .build();
                })
                .collect(Collectors.toList());

        // 4. 최근 댓글 가져오기
        List<GroupActivityResponse> commentActivities = groupTopicCommentRepository.findByTopic_Group_IdInOrderByCreatedAtDesc(groupIds).stream()
                .limit(20)
                .map(comment -> GroupActivityResponse.builder()
                        .type("COMMENT")
                        .groupId(comment.getTopic().getGroup().getId())
                        .groupName(comment.getTopic().getGroup().getName())
                        .id(comment.getTopic().getId()) // 토픽 상세로 이동할 수 있게 토픽 ID 사용
                        .title("토픽에 댓글이 달렸습니다: " + comment.getTopic().getTitle())
                        .contentSnippet(truncateContent(comment.getContent()))
                        .authorNickname(comment.getAuthor().getNickname())
                        .authorProfileImage(comment.getAuthor().getProfileImage())
                        .createdAt(comment.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        return Stream.of(memoActivities, topicActivities, scheduleActivities, commentActivities)
                .flatMap(List::stream)
                .sorted(Comparator.comparing(GroupActivityResponse::getCreatedAt).reversed())
                .limit(40)
                .collect(Collectors.toList());
    }

    private String truncateContent(String content) {
        if (content == null) return "";
        return content.length() > 100 ? content.substring(0, 100) + "..." : content;
    }

    private String formatDateTime(java.time.LocalDateTime dateTime) {
        return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    /**
     * 그룹 초대. userIds(사용자 UUID)와 emails 중 최소 하나는 비어 있지 않아야 합니다.
     * 이미 멤버이거나 이미 대기 중인 초대가 있는 사용자는 건너뜁니다.
     */
    @Transactional
    public void inviteMembers(UUID inviterId, UUID groupId, GroupInviteRequest request) {
        Group group = findGroupById(groupId);
        User inviter = findUserById(inviterId);

        if (!isMember(groupId, inviterId)) {
            throw new UnauthorizedAccessException("그룹 멤버만 다른 사용자를 초대할 수 있습니다.");
        }

        List<UUID> userIds = new ArrayList<>();
        if (request != null && request.getUserIds() != null) {
            request.getUserIds().stream().filter(id -> id != null).forEach(userIds::add);
        }
        List<String> emails = new ArrayList<>();
        if (request != null && request.getEmails() != null) {
            request.getEmails().stream()
                    .filter(email -> email != null && !email.isBlank())
                    .map(String::trim)
                    .forEach(emails::add);
        }
        if (userIds.isEmpty() && emails.isEmpty()) {
            throw new BadRequestException("초대할 사용자를 한 명 이상 지정해주세요.");
        }

        // 초대 대상 수집 (중복 제거)
        Map<UUID, User> invitees = new LinkedHashMap<>();
        for (UUID userId : userIds) {
            User invitee = findUserById(userId);
            invitees.putIfAbsent(invitee.getId(), invitee);
        }
        for (String email : emails) {
            // 이메일이 null 인 사용자는 절대 매칭되지 않는다 (빈 값은 위에서 걸러짐).
            List<User> matches = userRepository.findAllByEmail(email);
            if (matches.isEmpty()) {
                throw new ResourceNotFoundException("사용자", email);
            }
            User invitee = matches.get(0);
            invitees.putIfAbsent(invitee.getId(), invitee);
        }

        for (User invitee : invitees.values()) {
            if (isMember(groupId, invitee.getId())) continue;
            if (groupInvitationRepository.existsByGroupAndInviteeAndStatus(group, invitee, InvitationStatus.PENDING)) continue;

            groupInvitationRepository.save(GroupInvitation.builder()
                    .group(group)
                    .inviter(inviter)
                    .invitee(invitee)
                    .status(InvitationStatus.PENDING)
                    .build());
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
        GroupInvitation invitation = findPendingInvitation(userId, invitationId);

        invitation.accept();

        // 이미 멤버라면(초대 코드로 먼저 가입한 경우 등) 멤버십을 또 만들지 않는다.
        if (!isMember(invitation.getGroup().getId(), userId)) {
            groupMemberRepository.save(GroupMember.builder()
                    .group(invitation.getGroup())
                    .user(invitation.getInvitee())
                    .build());
        }
    }

    @Transactional
    public void rejectInvitation(UUID userId, UUID invitationId) {
        GroupInvitation invitation = findPendingInvitation(userId, invitationId);
        invitation.reject();
    }

    /** 본인에게 온 초대이고 아직 PENDING 상태일 때만 반환합니다. */
    private GroupInvitation findPendingInvitation(UUID userId, UUID invitationId) {
        GroupInvitation invitation = groupInvitationRepository.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("초대", invitationId));

        if (!invitation.getInvitee().getId().equals(userId)) {
            throw new UnauthorizedAccessException("본인에게 온 초대만 처리할 수 있습니다.");
        }
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new ConflictException("이미 처리된 초대입니다.");
        }
        return invitation;
    }

    /** 그룹 정보(이름/설명/테마/이미지) 수정은 그룹장만 가능합니다. */
    @Transactional
    public GroupResponse updateGroup(UUID userId, UUID groupId, GroupRequest request,
                                     org.springframework.web.multipart.MultipartFile image,
                                     org.springframework.web.multipart.MultipartFile bgImage) {
        Group group = findGroupById(groupId);
        requireOwner(group, userId, "그룹장만 그룹 정보를 수정할 수 있습니다.");

        if (request.getName() != null) group.setName(request.getName());
        if (request.getDescription() != null) group.setDescription(request.getDescription());
        if (request.getColorTheme() != null) group.setColorTheme(request.getColorTheme());

        // 두 파일을 모두 저장(검증)한 뒤에 교체하고, 이전 파일은 커밋 후 삭제한다.
        String newProfileImage = (image != null && !image.isEmpty()) ? "/uploads/" + fileService.storeFile(image) : null;
        String newBackgroundImage = (bgImage != null && !bgImage.isEmpty()) ? "/uploads/" + fileService.storeFile(bgImage) : null;

        if (newProfileImage != null) {
            String previous = group.getProfileImage();
            group.setProfileImage(newProfileImage);
            fileService.deleteFileAfterCommit(previous);
        }
        if (newBackgroundImage != null) {
            String previous = group.getBackgroundImage();
            group.setBackgroundImage(newBackgroundImage);
            fileService.deleteFileAfterCommit(previous);
        }

        return convertToResponse(group);
    }

    @Transactional
    public void kickMember(UUID ownerId, UUID groupId, UUID targetMemberId) {
        Group group = findGroupById(groupId);
        requireOwner(group, ownerId, "그룹장만 멤버를 내보낼 수 있습니다.");

        if (ownerId.equals(targetMemberId)) {
            throw new BadRequestException("자기 자신을 내보낼 수 없습니다.");
        }

        List<GroupMember> memberships = groupMemberRepository.findAllByGroup_IdAndUser_Id(groupId, targetMemberId);
        if (memberships.isEmpty()) {
            throw new ResourceNotFoundException("그룹 멤버", targetMemberId);
        }
        groupMemberRepository.deleteAll(memberships);
        removeFromGroupSchedules(groupId, targetMemberId);

        // 내보낸 멤버가 알고 있는 초대 코드로 다시 들어오지 못하도록 코드를 새로 발급한다.
        group.setInviteCode(generateUniqueInviteCode());
    }

    /** 그룹 나가기. 그룹장은 나갈 수 없습니다 (그룹을 삭제해야 함). */
    @Transactional
    public void leaveGroup(UUID userId, UUID groupId) {
        Group group = findGroupById(groupId);

        if (group.getCreatedBy().getId().equals(userId)) {
            throw new BadRequestException("그룹장은 그룹을 나갈 수 없습니다. 그룹을 삭제해주세요.");
        }

        List<GroupMember> memberships = groupMemberRepository.findAllByGroup_IdAndUser_Id(groupId, userId);
        if (memberships.isEmpty()) {
            throw new UnauthorizedAccessException("해당 그룹의 멤버가 아닙니다.");
        }
        groupMemberRepository.deleteAll(memberships);
        removeFromGroupSchedules(groupId, userId);
    }

    /**
     * 그룹 삭제 (그룹장 전용).
     * 멤버/초대/토픽(댓글·AI 분석 포함)은 삭제하고,
     * 그룹 메모와 그룹 일정은 삭제하지 않고 그룹에서 분리하여(group_id = NULL) 작성자의 개인 메모/일정으로 남깁니다.
     */
    @Transactional
    public void deleteGroup(UUID userId, UUID groupId) {
        Group group = findGroupById(groupId);
        requireOwner(group, userId, "그룹장만 그룹을 삭제할 수 있습니다.");

        for (Memo memo : memoRepository.findByGroupIdOrderByCreatedAtDesc(groupId)) {
            memo.detachFromGroup();
        }
        for (Schedule schedule : scheduleRepository.findAllByGroupIdOrderByStartTimeAsc(groupId)) {
            schedule.detachFromGroup();
        }

        List<GroupTopic> topics = groupTopicRepository.findAllByGroup(group);
        groupTopicRepository.deleteAll(topics); // 댓글, AI 분석 결과는 cascade 로 함께 삭제

        groupInvitationRepository.deleteAllByGroup(group);
        groupMemberRepository.deleteAllByGroup(group);

        String profileImage = group.getProfileImage();
        String backgroundImage = group.getBackgroundImage();

        // 자식 행 삭제를 먼저 반영한 뒤 그룹을 삭제한다.
        groupRepository.flush();
        groupRepository.delete(group);

        fileService.deleteFileAfterCommit(profileImage);
        fileService.deleteFileAfterCommit(backgroundImage);
    }

    // ─── Internal Helpers ──────────────────────────────────────────

    private Group findGroupById(UUID groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("그룹", groupId));
    }

    private User findUserById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));
    }

    private boolean isMember(UUID groupId, UUID userId) {
        return groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId);
    }

    private void requireMember(UUID groupId, UUID userId) {
        if (!isMember(groupId, userId)) {
            throw new UnauthorizedAccessException("해당 그룹의 멤버가 아닙니다.");
        }
    }

    private void requireOwner(Group group, UUID userId, String message) {
        if (!group.getCreatedBy().getId().equals(userId)) {
            throw new UnauthorizedAccessException(message);
        }
    }

    /** 그룹을 떠난 사용자를 그 그룹 일정의 참여자 목록에서 제거합니다. */
    private void removeFromGroupSchedules(UUID groupId, UUID userId) {
        for (Schedule schedule : scheduleRepository.findAllByGroupIdOrderByStartTimeAsc(groupId)) {
            if (schedule.getParticipants() != null) {
                schedule.getParticipants().removeIf(p -> p.getId().equals(userId));
            }
        }
    }

    @Transactional(readOnly = true)
    protected GroupResponse convertToResponse(Group group) {
        // 과거 데이터에 중복 멤버십 행이 있어도 멤버가 두 번 나오지 않도록 사용자 기준으로 중복을 제거한다.
        Map<UUID, User> members = new LinkedHashMap<>();
        for (GroupMember membership : groupMemberRepository.findByGroup(group)) {
            members.putIfAbsent(membership.getUser().getId(), membership.getUser());
        }
        List<GroupResponse.MemberResponse> memberResponses = members.values().stream()
                .map(u -> GroupResponse.MemberResponse.builder()
                        .id(u.getId())
                        .nickname(u.getNickname())
                        .profileImage(u.getProfileImage())
                        .build())
                .collect(Collectors.toList());

        return GroupResponse.builder()
                .id(group.getId())
                .name(group.getName())
                .description(group.getDescription())
                .colorTheme(group.getColorTheme())
                .createdById(group.getCreatedBy().getId())
                .createdByNickname(group.getCreatedBy().getNickname())
                .members(memberResponses)
                .inviteCode(group.getInviteCode())
                .profileImage(group.getProfileImage())
                .backgroundImage(group.getBackgroundImage())
                .memberCount(memberResponses.size())
                .createdAt(group.getCreatedAt())
                .build();
    }
}
