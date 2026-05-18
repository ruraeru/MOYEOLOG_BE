package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.GroupRequest;
import com.moyeolog.moyelog_BE.dto.GroupResponse;
import com.moyeolog.moyelog_BE.entity.Group;
import com.moyeolog.moyelog_BE.entity.GroupMember;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.repository.GroupMemberRepository;
import com.moyeolog.moyelog_BE.repository.GroupRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;

    @Transactional
    public GroupResponse createGroup(UUID userId, GroupRequest request) {
        User creator = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Group group = Group.builder()
                .name(request.getName())
                .description(request.getDescription())
                .colorTheme(request.getColorTheme())
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

    @Transactional(readOnly = true)
    public List<GroupResponse> getMyGroups(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<GroupMember> memberships = groupMemberRepository.findByUser(user);
        
        return memberships.stream()
                .map(membership -> convertToResponse(membership.getGroup()))
                .collect(Collectors.toList());
    }

    private GroupResponse convertToResponse(Group group) {
        List<GroupMember> members = groupMemberRepository.findByGroup(group);
        List<String> memberNicknames = members.stream()
                .map(m -> m.getUser().getNickname())
                .collect(Collectors.toList());

        return GroupResponse.builder()
                .id(group.getId())
                .name(group.getName())
                .description(group.getDescription())
                .colorTheme(group.getColorTheme())
                .createdByNickname(group.getCreatedBy().getNickname())
                .memberNicknames(memberNicknames)
                .memberCount(memberNicknames.size())
                .createdAt(group.getCreatedAt())
                .build();
    }
}
