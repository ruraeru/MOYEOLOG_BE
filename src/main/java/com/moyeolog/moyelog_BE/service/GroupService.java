package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.*;
import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.GroupInvitation;
import com.moyeolog.moyelog_BE.entity.GroupMember;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.enums.InvitationStatus;
import com.moyeolog.moyelog_BE.repository.GroupInvitationRepository;
import com.moyeolog.moyelog_BE.repository.GroupMemberRepository;
import com.moyeolog.moyelog_BE.repository.GroupRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final GroupInvitationRepository groupInvitationRepository;
    private final FileService fileService;

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 6;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public GroupResponse createGroup(UUID userId, GroupRequest request) {
        User creator = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

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
        Group group = groupRepository.findByInviteCode(inviteCode.toUpperCase())
                .orElseThrow(() -> new RuntimeException("Invalid invite code"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 이미 멤버인지 확인
        boolean alreadyMember = groupMemberRepository.findByGroup(group).stream()
                .anyMatch(m -> m.getUser().getId().equals(userId));

        if (alreadyMember) {
            throw new RuntimeException("Already a member of this group");
        }

        GroupMember member = GroupMember.builder()
                .group(group)
                .user(user)
                .build();
        groupMemberRepository.save(member);
    }

    @Transactional(readOnly = true)
    public List<GroupResponse> getMyGroups(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<GroupMember> memberships = groupMemberRepository.findByUser(user);

        return memberships.stream()
                .map(membership -> convertToResponse(membership.getGroup()))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public GroupResponse getGroup(UUID userId, UUID groupId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 멤버인지 확인 (보안)
        groupMemberRepository.findByGroup(group).stream()
                .filter(m -> m.getUser().getId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Unauthorized access to group"));

        return convertToResponse(group);
    }

    @Transactional
    public void inviteMembers(UUID inviterId, UUID groupId, GroupInviteRequest request) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        User inviter = userRepository.findById(inviterId)
                .orElseThrow(() -> new RuntimeException("Inviter not found"));

        // 초대자가 멤버인지 확인
        groupMemberRepository.findByGroup(group).stream()
                .filter(m -> m.getUser().getId().equals(inviterId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Only group members can invite others"));

        for (String email : request.getEmails()) {
            User invitee = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found with email: " + email));

            // 이미 멤버인지 확인
            boolean alreadyMember = groupMemberRepository.findByGroup(group).stream()
                    .anyMatch(m -> m.getUser().getId().equals(invitee.getId()));

            if (alreadyMember) continue;

            // 이미 대기 중인 초대 있는지 확인
            groupInvitationRepository.findByGroupAndInviteeAndStatus(group, invitee, InvitationStatus.PENDING)
                    .ifPresent(i -> {
                        throw new RuntimeException("Invitation already pending for " + email);
                    });

            GroupInvitation invitation = GroupInvitation.builder()
                    .group(group)
                    .inviter(inviter)
                    .invitee(invitee)
                    .status(InvitationStatus.PENDING)
                    .build();

            groupInvitationRepository.save(invitation);
        }
    }

    @Transactional(readOnly = true)
    public List<GroupInvitationResponse> getMyInvitations(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

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
        GroupInvitation invitation = groupInvitationRepository.findById(invitationId)
                .orElseThrow(() -> new RuntimeException("Invitation not found"));

        if (!invitation.getInvitee().getId().equals(userId)) {
            throw new RuntimeException("Unauthorized invitation access");
        }

        invitation.accept();

        // 멤버로 추가
        GroupMember member = GroupMember.builder()
                .group(invitation.getGroup())
                .user(invitation.getInvitee())
                .build();
        groupMemberRepository.save(member);
    }

    @Transactional
    public void rejectInvitation(UUID userId, UUID invitationId) {
        GroupInvitation invitation = groupInvitationRepository.findById(invitationId)
                .orElseThrow(() -> new RuntimeException("Invitation not found"));

        if (!invitation.getInvitee().getId().equals(userId)) {
            throw new RuntimeException("Unauthorized invitation access");
        }
    }

    @Transactional
    public GroupResponse updateGroup(UUID userId, UUID groupId, GroupRequest request,
                                     org.springframework.web.multipart.MultipartFile image,
                                     org.springframework.web.multipart.MultipartFile bgImage) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        // 멤버인지 확인
        boolean isMember = groupMemberRepository.findByGroup(group).stream()
                .anyMatch(m -> m.getUser().getId().equals(userId));

        if (!isMember) {
            throw new RuntimeException("Unauthorized to update group info");
        }

        if (request.getName() != null) group.setName(request.getName());
        if (request.getDescription() != null) group.setDescription(request.getDescription());
        if (request.getColorTheme() != null) group.setColorTheme(request.getColorTheme());

        if (image != null && !image.isEmpty()) {
            group.setProfileImage("/uploads/" + fileService.storeFile(image));
        }

        if (bgImage != null && !bgImage.isEmpty()) {
            group.setBackgroundImage("/uploads/" + fileService.storeFile(bgImage));
        }

        return convertToResponse(group);
    }

    @Transactional(readOnly = true)
    protected GroupResponse convertToResponse(Group group) {
        List<GroupMember> memberships = groupMemberRepository.findByGroup(group);
        List<GroupResponse.MemberResponse> memberResponses = memberships.stream()
                .map(m -> GroupResponse.MemberResponse.builder()
                        .id(m.getUser().getId())
                        .nickname(m.getUser().getNickname())
                        .profileImage(m.getUser().getProfileImage())
                        .build())
                .collect(Collectors.toList());

        return GroupResponse.builder()
                .id(group.getId())
                .name(group.getName())
                .description(group.getDescription())
                .colorTheme(group.getColorTheme())
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
