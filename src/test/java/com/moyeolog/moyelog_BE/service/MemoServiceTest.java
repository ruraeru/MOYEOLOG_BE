package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.MemoRequest;
import com.moyeolog.moyelog_BE.dto.MemoResponse;
import com.moyeolog.moyelog_BE.entity.Memo;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MemoServiceTest {

    @Mock
    private MemoRepository memoRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private FileService fileService;
    @Mock
    private MemoTagRepository memoTagRepository;
    @Mock
    private MemoShareRepository memoShareRepository;
    @Mock
    private MemoAiInsightRepository memoAiInsightRepository;
    @Mock
    private GeminiService geminiService;

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

    @Test
    @DisplayName("메모 생성 성공")
    void createMemo_Success() {
        // given
        MemoRequest request = MemoRequest.builder()
                .title("Title")
                .content("Content")
                .tags(Collections.emptyList())
                .build();
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(memoRepository.save(any(Memo.class))).willReturn(memo);

        // when
        MemoResponse response = memoService.createMemo(userId, request, null);

        // then
        assertThat(response.getTitle()).isEqualTo(memo.getTitle());
        verify(memoRepository).save(any(Memo.class));
    }

    @Test
    @DisplayName("메모 삭제 성공")
    void deleteMemo_Success() {
        // given
        given(memoRepository.findById(memoId)).willReturn(Optional.of(memo));

        // when
        memoService.deleteMemo(userId, memoId);

        // then
        verify(memoRepository).delete(memo);
    }

    @Test
    @DisplayName("작성자가 아닌 경우 메모 삭제 실패")
    void deleteMemo_Unauthorized() {
        // given
        UUID otherUserId = UUID.randomUUID();
        given(memoRepository.findById(memoId)).willReturn(Optional.of(memo));

        // when & then
        assertThatThrownBy(() -> memoService.deleteMemo(otherUserId, memoId))
                .isInstanceOf(UnauthorizedAccessException.class)
                .hasMessageContaining("권한이 없습니다");
    }

    @Test
    @DisplayName("즐겨찾기 토글 성공")
    void toggleFavorite_Success() {
        // given
        given(memoRepository.findById(memoId)).willReturn(Optional.of(memo));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));

        // when
        MemoResponse response = memoService.toggleFavorite(userId, memoId);

        // then
        assertThat(response.getIsFavorite()).isTrue();
        assertThat(memo.getIsFavorite()).isTrue();
    }
}
