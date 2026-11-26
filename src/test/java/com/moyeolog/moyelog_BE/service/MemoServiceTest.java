package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.MemoRequest;
import com.moyeolog.moyelog_BE.dto.MemoResponse;
import com.moyeolog.moyelog_BE.dto.MemoShareRequest;
import com.moyeolog.moyelog_BE.entity.Memo;
import com.moyeolog.moyelog_BE.entity.MemoFavorite;
import com.moyeolog.moyelog_BE.entity.MemoShare;
import com.moyeolog.moyelog_BE.entity.User;
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
    @Mock private MemoGroupShareRepository memoGroupShareRepository;
    @Mock private MemoAiInsightRepository memoAiInsightRepository;
    @Mock private GeminiService geminiService;
    @Mock private GroupRepository groupRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private FriendRepository friendRepository;
    @Mock private MemoFavoriteRepository memoFavoriteRepository;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

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
        given(memoRepository.findByIdForUpdate(memoId)).willReturn(Optional.of(withImage));

        memoService.deleteMemo(userId, memoId);

        verify(memoRepository).delete(withImage);
        verify(memoFavoriteRepository).deleteAllByMemo(withImage);
        verify(fileService).deleteFileAfterCommit("/uploads/a.png");
    }

    @Test
    @DisplayName("작성자가 아닌 경우 메모 삭제 실패")
    void deleteMemo_Unauthorized() {
        UUID otherUserId = UUID.randomUUID();
        given(memoRepository.findByIdForUpdate(memoId)).willReturn(Optional.of(memo));

        assertThatThrownBy(() -> memoService.deleteMemo(otherUserId, memoId))
                .isInstanceOf(UnauthorizedAccessException.class)
                .hasMessageContaining("작성자만");
    }

    @Test
    @DisplayName("즐겨찾기 토글 - 사용자별로 저장된다")
    void toggleFavorite_Success() {
        given(memoRepository.findById(memoId)).willReturn(Optional.of(memo));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(memoFavoriteRepository.existsByUser_IdAndMemo_Id(userId, memoId)).willReturn(false);

        MemoResponse response = memoService.toggleFavorite(userId, memoId);

        assertThat(response.getIsFavorite()).isTrue();
        verify(memoFavoriteRepository).save(any(MemoFavorite.class));
    }

    @Test
    @DisplayName("즐겨찾기 토글 - 이미 즐겨찾기면 해제된다")
    void toggleFavorite_Off() {
        given(memoRepository.findById(memoId)).willReturn(Optional.of(memo));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(memoFavoriteRepository.existsByUser_IdAndMemo_Id(userId, memoId)).willReturn(true);
        // 동시에 해제돼 지울 행이 없어도(0) 오류가 나지 않는다
        given(memoFavoriteRepository.deleteByUserIdAndMemoId(userId, memoId)).willReturn(0);

        MemoResponse response = memoService.toggleFavorite(userId, memoId);

        assertThat(response.getIsFavorite()).isFalse();
    }

    @Test
    @DisplayName("모임 멤버인 경우 그룹 메모 접근 성공")
    void getMemo_GroupMember_Success() {
        UUID groupId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        Memo groupMemo = Memo.builder().id(UUID.randomUUID()).author(user).groupId(groupId).build();

        User other = User.builder().id(otherUserId).build();
        given(memoRepository.findById(groupMemo.getId())).willReturn(Optional.of(groupMemo));
        given(userRepository.findById(otherUserId)).willReturn(Optional.of(other));
        given(groupMemberRepository.findByUser(other)).willReturn(List.of(membership(groupId, other)));

        MemoResponse response = memoService.getMemo(otherUserId, groupMemo.getId());

        assertThat(response.getId()).isEqualTo(groupMemo.getId());
        // 모임 멤버(작성자 아님): 수정·태그만
        assertThat(response.getPermissions().isEdit()).isTrue();
        assertThat(response.getPermissions().isTag()).isTrue();
        assertThat(response.getPermissions().isDelete()).isFalse();
        assertThat(response.getPermissions().isShare()).isFalse();
        assertThat(response.getPermissions().isAnalyze()).isFalse();
    }

    @Test
    @DisplayName("그룹 메모 목록 - 멤버는 조회 가능")
    void getGroupMemos_Member_Success() {
        UUID groupId = UUID.randomUUID();
        Memo groupMemo = Memo.builder().id(UUID.randomUUID()).author(user).groupId(groupId).title("g").build();
        given(groupRepository.existsById(groupId)).willReturn(true);
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(true);
        given(memoRepository.findByGroupIdOrderByCreatedAtDesc(groupId)).willReturn(List.of(groupMemo));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));

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
                .isInstanceOf(ItemNotFoundException.class);
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
    @DisplayName("공유받은 사용자는 메모를 수정할 수 없다 (기본 읽기 전용)")
    void updateMemo_ShareRecipient_Fail() {
        UUID recipientId = UUID.randomUUID();
        given(memoRepository.findByIdForUpdate(memoId)).willReturn(Optional.of(memo));

        assertThatThrownBy(() -> memoService.updateMemo(recipientId, memoId, request(), null))
                .isInstanceOf(UnauthorizedAccessException.class);
    }

    @Test
    @DisplayName("친구가 아닌 사용자에게는 메모를 공유할 수 없다")
    void shareMemo_NotFriend_Fail() {
        UUID strangerId = UUID.randomUUID();
        given(memoRepository.findByIdForUpdate(memoId)).willReturn(Optional.of(memo));
        given(friendRepository.findAcceptedBetweenForUpdate(userId, strangerId)).willReturn(List.of());

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
        given(memoRepository.findById(memoId)).willReturn(Optional.of(memo));
        given(memoShareRepository.deleteByMemoIdAndUserId(memoId, recipientId)).willReturn(1);

        memoService.unshareMemo(userId, memoId, recipientId);
        verify(memoShareRepository).deleteByMemoIdAndUserId(memoId, recipientId);
        // 받은 사람의 즐겨찾기도 정리된다
        verify(memoFavoriteRepository).deleteByUserIdAndMemoId(recipientId, memoId);

        assertThatThrownBy(() -> memoService.unshareMemo(UUID.randomUUID(), memoId, recipientId))
                .isInstanceOf(UnauthorizedAccessException.class);
    }

    // ─── 2차 QA ─────────────────────────────────────────────────────

    private static com.moyeolog.moyelog_BE.entity.GroupMember membership(UUID groupId, User member) {
        return com.moyeolog.moyelog_BE.entity.GroupMember.builder()
                .group(com.moyeolog.moyelog_BE.entity.Group.builder().id(groupId).build())
                .user(member).build();
    }

    private Memo groupMemo(UUID groupId) {
        return Memo.builder().id(memoId).author(user).groupId(groupId).title("g").content("c")
                .taggedMemos(new java.util.ArrayList<>()).version(3L).build();
    }

    @Test
    @DisplayName("QA03-009: 이모지 200개 제목은 검증을 통과하고, 256자는 거부된다 (코드 포인트 기준)")
    void title_CountsCodePoints() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            MemoRequest ok = request();
            ok.setTitle("😀".repeat(200));
            assertThat(validator.validate(ok)).isEmpty();

            MemoRequest tooLong = request();
            tooLong.setTitle("😀".repeat(256));
            assertThat(validator.validate(tooLong)).isNotEmpty();
        }
    }

    @Test
    @DisplayName("QA04-017: 자기 자신·null 만 담아 공유하면 400, 저장하지 않는다")
    void shareMemo_OnlySelfOrNull_400() {
        given(memoRepository.findByIdForUpdate(memoId)).willReturn(Optional.of(memo));
        List<UUID> selfAndNull = new java.util.ArrayList<>();
        selfAndNull.add(userId);
        selfAndNull.add(null);

        assertThatThrownBy(() -> memoService.shareMemo(userId, memoId,
                MemoShareRequest.builder().friendIds(selfAndNull).build()))
                .isInstanceOf(com.moyeolog.moyelog_BE.exception.BadRequestException.class);
        verify(memoShareRepository, never()).save(any(MemoShare.class));
    }

    @Test
    @DisplayName("공유: 이미 공유한 친구는 다시 저장하지 않고 권한만 바꾸며, 새로 공유받은 친구에게만 MEMO_SHARED 알림")
    void shareMemo_DedupAndNotify() {
        User f1 = User.builder().id(UUID.randomUUID()).nickname("f1").build();
        User f2 = User.builder().id(UUID.randomUUID()).nickname("f2").build();
        given(memoRepository.findByIdForUpdate(memoId)).willReturn(Optional.of(memo));
        given(friendRepository.findAcceptedBetweenForUpdate(any(), any()))
                .willReturn(List.of(com.moyeolog.moyelog_BE.entity.Friend.builder().build()));
        given(userRepository.findById(f1.getId())).willReturn(Optional.of(f1));
        given(userRepository.findById(f2.getId())).willReturn(Optional.of(f2));
        MemoShare existing = MemoShare.builder().memo(memo).sharedTo(f1).build();
        given(memoShareRepository.findAllByMemo_IdAndSharedTo_Id(memoId, f1.getId())).willReturn(List.of(existing));
        given(memoShareRepository.findAllByMemo_IdAndSharedTo_Id(memoId, f2.getId())).willReturn(List.of());

        memoService.shareMemo(userId, memoId,
                MemoShareRequest.builder().friendIds(List.of(f1.getId(), f2.getId())).canEdit(true).build());

        org.mockito.ArgumentCaptor<MemoShare> saved = org.mockito.ArgumentCaptor.forClass(MemoShare.class);
        verify(memoShareRepository, org.mockito.Mockito.times(1)).save(saved.capture());
        assertThat(saved.getValue().getSharedTo()).isEqualTo(f2);
        assertThat(saved.getValue().isCanEdit()).isTrue();
        // 이미 공유한 f1 은 권한만 '수정 가능'으로 바뀐다
        assertThat(existing.isCanEdit()).isTrue();
        org.mockito.ArgumentCaptor<com.moyeolog.moyelog_BE.event.NotificationEvent> captor =
                org.mockito.ArgumentCaptor.forClass(com.moyeolog.moyelog_BE.event.NotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().recipientId()).isEqualTo(f2.getId());
        assertThat(captor.getValue().type()).isEqualTo(com.moyeolog.moyelog_BE.event.NotificationType.MEMO_SHARED);
        assertThat(captor.getValue().link()).isEqualTo("/memo/" + memoId);
    }

    @Test
    @DisplayName("QA03-043: 보낸 version 이 서버와 다르면 409, 덮어쓰지 않는다")
    void updateMemo_StaleVersion_409() {
        Memo m = Memo.builder().id(memoId).author(user).title("t").content("c").version(5L).build();
        given(memoRepository.findByIdForUpdate(memoId)).willReturn(Optional.of(m));
        MemoRequest req = request();
        req.setVersion(4L);

        assertThatThrownBy(() -> memoService.updateMemo(userId, memoId, req, null))
                .isInstanceOf(com.moyeolog.moyelog_BE.exception.ConflictException.class);
        assertThat(m.getTitle()).isEqualTo("t");
    }

    @Test
    @DisplayName("removeImage=true 면 이미지를 지우고 파일도 커밋 뒤 삭제한다")
    void updateMemo_RemoveImage() {
        Memo m = Memo.builder().id(memoId).author(user).title("t").content("c").imageUrl("/uploads/x.png")
                .taggedMemos(new java.util.ArrayList<>()).build();
        given(memoRepository.findByIdForUpdate(memoId)).willReturn(Optional.of(m));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        MemoRequest req = request();
        req.setRemoveImage(true);

        MemoResponse response = memoService.updateMemo(userId, memoId, req, null);

        assertThat(response.getImageUrl()).isNull();
        verify(fileService).deleteFileAfterCommit("/uploads/x.png");
    }

    @Test
    @DisplayName("QA03-037/QA04-045: 모임 멤버(작성자 아님)도 태그를 바꿀 수 있다")
    void updateTags_GroupMember_Allowed() {
        UUID groupId = UUID.randomUUID();
        User member = User.builder().id(UUID.randomUUID()).nickname("m").build();
        Memo gm = groupMemo(groupId);
        given(memoRepository.findByIdForUpdate(memoId)).willReturn(Optional.of(gm));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, member.getId())).willReturn(true);
        given(userRepository.findById(member.getId())).willReturn(Optional.of(member));

        memoService.updateTags(member.getId(), memoId, List.of("디자인"));

        verify(memoTagRepository).saveAll(any());
        assertThat(gm.getLastModifier()).isEqualTo(member);
    }

    @Test
    @DisplayName("QA07-031: 모임을 나간 작성자는 모임 메모를 고칠 수 없지만 삭제는 할 수 있다")
    void groupMemo_FormerMemberAuthor() {
        UUID groupId = UUID.randomUUID();
        Memo gm = groupMemo(groupId);
        given(memoRepository.findByIdForUpdate(memoId)).willReturn(Optional.of(gm));
        given(groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)).willReturn(false);

        assertThatThrownBy(() -> memoService.updateMemo(userId, memoId, request(), null))
                .isInstanceOf(UnauthorizedAccessException.class);
        assertThatThrownBy(() -> memoService.updateTags(userId, memoId, List.of("x")))
                .isInstanceOf(UnauthorizedAccessException.class);

        memoService.deleteMemo(userId, memoId);
        verify(memoRepository).delete(gm);
    }

    @Test
    @DisplayName("QA04-030/031: 공유받은 사람에게는 읽을 수 없는 연결 메모·일정을 숨기고, 권한은 모두 false")
    void getMemo_SharedRecipient_HidesUnreadableLinks() {
        User recipient = User.builder().id(UUID.randomUUID()).nickname("r").build();
        Memo secret = Memo.builder().id(UUID.randomUUID()).author(user).title("개인 일기").build();
        Memo shared = Memo.builder().id(memoId).author(user).title("회의록")
                .taggedMemos(new java.util.ArrayList<>(List.of(secret))).build();
        com.moyeolog.moyelog_BE.entity.Schedule privateSchedule = com.moyeolog.moyelog_BE.entity.Schedule.builder()
                .id(UUID.randomUUID()).author(user).title("병원").participants(new java.util.ArrayList<>()).build();
        given(memoRepository.findById(memoId)).willReturn(Optional.of(shared));
        given(userRepository.findById(recipient.getId())).willReturn(Optional.of(recipient));
        given(memoShareRepository.existsByMemo_IdAndSharedTo_Id(memoId, recipient.getId())).willReturn(true);
        given(memoShareRepository.existsByMemo_IdAndSharedTo_Id(secret.getId(), recipient.getId())).willReturn(false);
        given(scheduleRepository.findByTaggedMemosContains(shared)).willReturn(List.of(privateSchedule));

        MemoResponse response = memoService.getMemo(recipient.getId(), memoId);

        assertThat(response.getTaggedMemos()).isEmpty();
        assertThat(response.getTaggedSchedules()).isEmpty();
        MemoResponse.Permissions p = response.getPermissions();
        assertThat(p.isEdit() || p.isDelete() || p.isShare() || p.isTag() || p.isAnalyze()).isFalse();
    }

    @Test
    @DisplayName("QA05-031: 일정 쪽에서 만든 연결은 메모를 taggedScheduleIds=[] 로 고쳐도 남고, 메모 쪽 연결만 끊긴다")
    void updateMemo_KeepsScheduleSideLinks() {
        Memo m = Memo.builder().id(memoId).author(user).title("t").content("c")
                .taggedMemos(new java.util.ArrayList<>()).build();
        com.moyeolog.moyelog_BE.entity.Schedule fromSchedule = com.moyeolog.moyelog_BE.entity.Schedule.builder()
                .id(UUID.randomUUID()).author(user).title("리허설")
                .taggedMemos(new java.util.ArrayList<>(List.of(m))).build();
        com.moyeolog.moyelog_BE.entity.Schedule fromMemo = com.moyeolog.moyelog_BE.entity.Schedule.builder()
                .id(UUID.randomUUID()).author(user).title("발표")
                .taggedMemos(new java.util.ArrayList<>(List.of(m))).build();
        m.replaceMentionedSchedules(java.util.Set.of(fromMemo.getId()));
        given(memoRepository.findByIdForUpdate(memoId)).willReturn(Optional.of(m));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(scheduleRepository.findAllById(any())).willReturn(List.of(fromMemo));
        MemoRequest req = request();
        req.setTaggedScheduleIds(List.of());

        memoService.updateMemo(userId, memoId, req, null);

        assertThat(fromSchedule.getTaggedMemos()).contains(m);
        assertThat(fromMemo.getTaggedMemos()).doesNotContain(m);
        assertThat(m.getMentionedScheduleIds()).isEmpty();
    }

    @Test
    @DisplayName("QA05-032: 참여자는 남의 일정에 자기 메모를 연결할 수 없다 (건너뜀)")
    void createMemo_CannotLinkOthersSchedule() {
        User owner = User.builder().id(UUID.randomUUID()).build();
        com.moyeolog.moyelog_BE.entity.Schedule others = com.moyeolog.moyelog_BE.entity.Schedule.builder()
                .id(UUID.randomUUID()).author(owner).title("리허설")
                .participants(new java.util.ArrayList<>(List.of(user)))
                .taggedMemos(new java.util.ArrayList<>()).build();
        MemoRequest req = request();
        req.setTaggedScheduleIds(List.of(others.getId()));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(scheduleRepository.findAllById(any())).willReturn(List.of(others));
        given(memoRepository.save(any(Memo.class))).willAnswer(inv -> inv.getArgument(0));

        memoService.createMemo(userId, req, null);

        assertThat(others.getTaggedMemos()).isEmpty();
    }

    @Test
    @DisplayName("즐겨찾기 목록: 더 이상 읽을 수 없는 메모는 빠진다")
    void getFavoriteMemos_OnlyReadable() {
        User other = User.builder().id(UUID.randomUUID()).build();
        Memo mine = Memo.builder().id(UUID.randomUUID()).author(user).title("mine").build();
        Memo lost = Memo.builder().id(UUID.randomUUID()).author(other).title("lost").build();
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(memoFavoriteRepository.findMemoIdsByUserId(userId)).willReturn(List.of(mine.getId(), lost.getId()));
        given(memoRepository.findAllById(any())).willReturn(List.of(mine, lost));

        List<MemoResponse> favorites = memoService.getFavoriteMemos(userId);

        assertThat(favorites).extracting(MemoResponse::getId).containsExactly(mine.getId());
        assertThat(favorites.get(0).getIsFavorite()).isTrue();
    }

    @Test
    @DisplayName("q 검색: 제목·본문에 포함된 내 메모만")
    void getMyMemos_Search() {
        Memo a = Memo.builder().id(UUID.randomUUID()).author(user).title("회의록").content("x").build();
        Memo b = Memo.builder().id(UUID.randomUUID()).author(user).title("일기").content("오늘 회의").build();
        Memo c = Memo.builder().id(UUID.randomUUID()).author(user).title("장보기").content("우유").build();
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(memoRepository.findAllByAuthorOrderByCreatedAtDesc(user)).willReturn(List.of(a, b, c));

        assertThat(memoService.getMyMemos(userId, " 회의 ")).extracting(MemoResponse::getId)
                .containsExactly(a.getId(), b.getId());
    }
}
