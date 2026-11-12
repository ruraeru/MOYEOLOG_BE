package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.MemoRequest;
import com.moyeolog.moyelog_BE.dto.MemoResponse;
import com.moyeolog.moyelog_BE.dto.MemoShareRequest;
import com.moyeolog.moyelog_BE.entity.Memo;
import com.moyeolog.moyelog_BE.entity.MemoFavorite;
import com.moyeolog.moyelog_BE.entity.MemoShare;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.exception.ResourceNotFoundException;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import com.moyeolog.moyelog_BE.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MemoServiceTest {

    @Mock private MemoRepository memoRepository;
    @Mock private UserRepository userRepository;
    @Mock private FileService fileService;
    @Mock private MemoTagRepository memoTagRepository;
    @Mock private MemoShareRepository memoShareRepository;
    @Mock private MemoAiInsightRepository memoAiInsightRepository;
    @Mock private GeminiService geminiService;
    @Mock private GroupRepository groupRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private FriendRepository friendRepository;
    @Mock private MemoFavoriteRepository memoFavoriteRepository;

    @InjectMocks
    private MemoService memoService;

    private User user;
    private UUID userId;
    private Memo memo;
    private UUID memoId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = User.builder().id(userId).nickname("testUser").build();
        memoId = UUID.randomUUID();
        memo = Memo.builder()
                .id(memoId)
                .author(user)
                .title("Test Title")
                .content("Test Content")
                .build();
    }

    private MemoRequest request() {
        return MemoRequest.builder().title("Title").content("Content").tags(Collections.emptyList()).build();
    }

    @Test
    @DisplayName("메모 생성 성공")
    void createMemo_Success() {
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(memoRepository.save(any(Memo.class))).willReturn(memo);

        MemoResponse response = memoService.createMemo(userId, request(), null);

        assertThat(response.getTitle()).isEqualTo(memo.getTitle());
        verify(memoRepository).save(any(Memo.class));
    }

    @Test
    @DisplayName("메모 삭제 성공 - 첨부 이미지도 삭제 요청된다")
    void deleteMemo_Success() {
        Memo withImage = Memo.builder().id(memoId).author(user).title("t").imageUrl("/uploads/a.png").build();
        given(memoRepository.findById(memoId)).willReturn(Optional.of(withImage));

        memoService.deleteMemo(userId, memoId);

        verify(memoRepository).delete(withImage);
        verify(memoFavoriteRepository).deleteAllByMemo(withImage);
        verify(fileService).deleteFileAfterCommit("/uploads/a.png");
    }

    @Test
    @DisplayName("작성자가 아닌 경우 메모 삭제 실패")
    void deleteMemo_Unauthorized() {
        UUID otherUserId = UUID.randomUUID();
        given(memoRepository.findById(memoId)).willReturn(Optional.of(memo));

        assertThatThrownBy(() -> memoService.deleteMemo(otherUserId, memoId))
                .isInstanceOf(UnauthorizedAccessException.class)
                .hasMessageContaining("권한이 없습니다");
    }

    @Test
    @DisplayName("즐겨찾기 토글 - 사용자별로 저장된다")
    void toggleFavorite_Success() {
        given(memoRepository.findById(memoId)).willReturn(Optional.of(memo));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(memoFavoriteRepository.findAllByUser_IdAndMemo_Id(userId, memoId)).willReturn(List.of());

        MemoResponse response = memoService.toggleFavorite(userId, memoId);

        assertThat(response.getIsFavorite()).isTrue();
        verify(memoFavoriteRepository).save(any(MemoFavorite.class));
    }

    @Test
    @DisplayName("즐겨찾기 토글 - 이미 즐겨찾기면 해제된다")
    void toggleFavorite_Off() {
        MemoFavorite favorite = MemoFavorite.builder().id(UUID.randomUUID()).user(user).memo(memo).build();
        given(memoRepository.findById(memoId)).willReturn(Optional.of(memo));
        given(memoFavoriteRepository.findAllByUser_IdAndMemo_Id(userId, memoId)).willReturn(List.of(favorite));

        MemoResponse response = memoService.toggleFavorite(userId, memoId);

        assertThat(response.getIsFavorite()).isFalse();
        verify(memoFavoriteRepository).deleteAll(List.of(favorite));
    }

    @Test
    @DisplayName("모임 멤버인 경우 그룹 메모 접근 성공")
    void getMemo_GroupMember_Success() {
        UUID groupId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        Memo groupMemo = Memo.builder().id(UUID.randomUUID()).author(user).groupId(groupId).build();

        given(memoRepository.findById(groupMemo.getId())).willReturn(Optional.of(groupMemo));
        given(memoShareRepository.existsByMemo_IdAndSharedTo_Id(groupMemo.getId(), otherUserId)).willReturn(false);
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, otherUserId)).willReturn(true);

        MemoResponse response = memoService.getMemo(otherUserId, groupMemo.getId());

        assertThat(response.getId()).isEqualTo(groupMemo.getId());
    }

    @Test
    @DisplayName("그룹 메모 목록 - 멤버는 조회 가능")
    void getGroupMemos_Member_Success() {
        UUID groupId = UUID.randomUUID();
        Memo groupMemo = Memo.builder().id(UUID.randomUUID()).author(user).groupId(groupId).title("g").build();
        given(groupRepository.existsById(groupId)).willReturn(true);
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);
        given(memoRepository.findByGroupIdOrderByCreatedAtDesc(groupId)).willReturn(List.of(groupMemo));

        List<MemoResponse> responses = memoService.getGroupMemos(userId, groupId);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getIsFavorite()).isFalse();
    }

    @Test
    @DisplayName("그룹 메모 목록 - 비멤버는 403, 메모를 조회하지 않는다")
    void getGroupMemos_NotMember_Fail() {
        UUID groupId = UUID.randomUUID();
        given(groupRepository.existsById(groupId)).willReturn(true);
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(false);

        assertThatThrownBy(() -> memoService.getGroupMemos(userId, groupId))
                .isInstanceOf(UnauthorizedAccessException.class);
        verify(memoRepository, never()).findByGroupIdOrderByCreatedAtDesc(any());
    }

    @Test
    @DisplayName("그룹 메모 목록 - 없는 그룹은 404")
    void getGroupMemos_UnknownGroup_Fail() {
        UUID groupId = UUID.randomUUID();
        given(groupRepository.existsById(groupId)).willReturn(false);

        assertThatThrownBy(() -> memoService.getGroupMemos(userId, groupId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("그룹 메모 생성 - 비멤버는 403, 저장하지 않는다")
    void createMemo_GroupNotMember_Fail() {
        UUID groupId = UUID.randomUUID();
        MemoRequest request = request();
        request.setGroupId(groupId);
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(groupRepository.existsById(groupId)).willReturn(true);
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(false);

        assertThatThrownBy(() -> memoService.createMemo(userId, request, null))
                .isInstanceOf(UnauthorizedAccessException.class);
        verify(memoRepository, never()).save(any(Memo.class));
    }

    @Test
    @DisplayName("공유받은 사용자는 메모를 수정할 수 없다 (읽기 전용)")
    void updateMemo_ShareRecipient_Fail() {
        UUID recipientId = UUID.randomUUID();
        given(memoRepository.findById(memoId)).willReturn(Optional.of(memo));

        assertThatThrownBy(() -> memoService.updateMemo(recipientId, memoId, request(), null))
                .isInstanceOf(UnauthorizedAccessException.class);
    }

    @Test
    @DisplayName("친구가 아닌 사용자에게는 메모를 공유할 수 없다")
    void shareMemo_NotFriend_Fail() {
        UUID strangerId = UUID.randomUUID();
        given(memoRepository.findById(memoId)).willReturn(Optional.of(memo));
        given(friendRepository.areFriends(userId, strangerId)).willReturn(false);

        assertThatThrownBy(() -> memoService.shareMemo(userId, memoId,
                MemoShareRequest.builder().friendIds(List.of(strangerId)).build()))
                .isInstanceOf(UnauthorizedAccessException.class);
        verify(memoShareRepository, never()).save(any(MemoShare.class));
    }

    @Test
    @DisplayName("읽을 수 없는 메모는 태그할 수 없다")
    void createMemo_TagUnreadableMemo_Fail() {
        User other = User.builder().id(UUID.randomUUID()).build();
        Memo foreign = Memo.builder().id(UUID.randomUUID()).author(other).title("secret").build();
        MemoRequest request = request();
        request.setTaggedMemoIds(List.of(foreign.getId()));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(memoRepository.findAllById(org.mockito.ArgumentMatchers.<Iterable<UUID>>any())).willReturn(List.of(foreign));
        given(memoShareRepository.existsByMemo_IdAndSharedTo_Id(foreign.getId(), userId)).willReturn(false);

        assertThatThrownBy(() -> memoService.createMemo(userId, request, null))
                .isInstanceOf(UnauthorizedAccessException.class);
        verify(memoRepository, never()).save(any(Memo.class));
    }

    @Test
    @DisplayName("작성자는 공유를 해제할 수 있고, 제3자는 할 수 없다")
    void unshareMemo() {
        UUID recipientId = UUID.randomUUID();
        MemoShare share = MemoShare.builder().id(UUID.randomUUID()).memo(memo).build();
        given(memoRepository.findById(memoId)).willReturn(Optional.of(memo));
        given(memoShareRepository.findAllByMemo_IdAndSharedTo_Id(memoId, recipientId)).willReturn(List.of(share));

        memoService.unshareMemo(userId, memoId, recipientId);
        verify(memoShareRepository).deleteAll(List.of(share));

        assertThatThrownBy(() -> memoService.unshareMemo(UUID.randomUUID(), memoId, recipientId))
                .isInstanceOf(UnauthorizedAccessException.class);
    }
}
