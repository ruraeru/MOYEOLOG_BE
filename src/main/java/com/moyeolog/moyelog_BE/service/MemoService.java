package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.MemoRequest;
import com.moyeolog.moyelog_BE.dto.MemoResponse;
import com.moyeolog.moyelog_BE.dto.MemoShareRequest;
import com.moyeolog.moyelog_BE.entity.Memo;
import com.moyeolog.moyelog_BE.entity.MemoShare;
import com.moyeolog.moyelog_BE.entity.MemoTag;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.repository.MemoRepository;
import com.moyeolog.moyelog_BE.repository.MemoShareRepository;
import com.moyeolog.moyelog_BE.repository.MemoTagRepository;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MemoService {

    private final MemoRepository memoRepository;
    private final UserRepository userRepository;
    private final FileService fileService;
    private final MemoTagRepository memoTagRepository;
    private final MemoShareRepository memoShareRepository;

    @Transactional
    public void shareMemo(UUID authorId, UUID memoId, MemoShareRequest request) {
        Memo memo = memoRepository.findById(memoId)
                .orElseThrow(() -> new RuntimeException("Memo not found"));

        if (!memo.getAuthor().getId().equals(authorId)) {
            throw new RuntimeException("Unauthorized share request");
        }

        for (UUID friendId : request.getFriendIds()) {
            User friend = userRepository.findById(friendId)
                    .orElseThrow(() -> new RuntimeException("Friend not found: " + friendId));

            // 이미 공유된 경우 건너뛰기
            if (memoShareRepository.findByMemoAndSharedTo(memo, friend).isPresent()) {
                continue;
            }

            MemoShare memoShare = MemoShare.builder()
                    .memo(memo)
                    .sharedTo(friend)
                    .build();
            memoShareRepository.save(memoShare);
        }
    }

    @Transactional(readOnly = true)
    public List<MemoResponse> getSharedMemos(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return memoShareRepository.findBySharedToOrderBySharedAtDesc(user).stream()
                .map(share -> convertToResponse(share.getMemo()))
                .collect(Collectors.toList());
    }

    @Transactional
    public MemoResponse createMemo(UUID userId, MemoRequest request, org.springframework.web.multipart.MultipartFile image) {
        User author = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String imageUrl = null;
        if (image != null && !image.isEmpty()) {
            String fileName = fileService.storeFile(image);
            imageUrl = "/uploads/" + fileName;
        }

        Memo memo = Memo.builder()
                .author(author)
                .groupId(request.getGroupId())
                .title(request.getTitle())
                .content(request.getContent())
                .imageUrl(imageUrl)
                .build();

        Memo savedMemo = memoRepository.save(memo);

        // 태그 저장
        if (request.getTags() != null && !request.getTags().isEmpty()) {
            List<MemoTag> tags = request.getTags().stream()
                    .map(tagName -> MemoTag.builder()
                            .memo(savedMemo)
                            .name(tagName)
                            .build())
                    .collect(Collectors.toList());
            memoTagRepository.saveAll(tags);
        }

        return convertToResponse(savedMemo);
    }

    @Transactional(readOnly = true)
    public List<MemoResponse> getMyMemos(UUID userId) {
        User author = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return memoRepository.findAllByAuthorOrderByCreatedAtDesc(author).stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteMemo(UUID userId, UUID memoId) {
        Memo memo = memoRepository.findById(memoId)
                .orElseThrow(() -> new RuntimeException("Memo not found"));

        if (!memo.getAuthor().getId().equals(userId)) {
            throw new RuntimeException("Unauthorized delete request");
        }

        memoRepository.delete(memo);
    }

    @Transactional(readOnly = true)
    public MemoResponse getMemo(UUID userId, UUID memoId) {
        Memo memo = memoRepository.findById(memoId)
                .orElseThrow(() -> new RuntimeException("Memo not found"));

        if (!memo.getAuthor().getId().equals(userId)) {
            throw new RuntimeException("Unauthorized access request");
        }

        return convertToResponse(memo);
    }

    @Transactional(readOnly = true)
    public List<MemoResponse> getGroupMemos(UUID userId, UUID groupId) {
        // 실제 운영 환경에서는 해당 유저가 그룹 멤버인지 확인하는 로직이 필요함 (여기서는 생략하거나 추가)
        return memoRepository.findByGroupIdOrderByCreatedAtDesc(groupId).stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    private MemoResponse convertToResponse(Memo memo) {
        List<String> tagNames = memoTagRepository.findAllByMemo(memo).stream()
                .map(MemoTag::getName)
                .collect(Collectors.toList());

        return MemoResponse.builder()
                .id(memo.getId())
                .title(memo.getTitle())
                .content(memo.getContent())
                .imageUrl(memo.getImageUrl())
                .groupId(memo.getGroupId())
                .tags(tagNames)
                .createdAt(memo.getCreatedAt())
                .updatedAt(memo.getUpdatedAt())
                .build();
    }
}
