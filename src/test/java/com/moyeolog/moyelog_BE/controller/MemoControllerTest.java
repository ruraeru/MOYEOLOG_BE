package com.moyeolog.moyelog_BE.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyeolog.moyelog_BE.dto.MemoRequest;
import com.moyeolog.moyelog_BE.dto.MemoResponse;
import com.moyeolog.moyelog_BE.security.JwtProvider;
import com.moyeolog.moyelog_BE.service.MemoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.security.Principal;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class MemoControllerTest {

    private MockMvc mockMvc;

    @Mock
    private MemoService memoService;

    @InjectMocks
    private MemoController memoController;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        this.mockMvc = MockMvcBuilders.standaloneSetup(memoController)
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                        Principal principal = webRequest.getUserPrincipal();
                        return principal != null ? principal.getName() : null;
                    }
                })
                .build();
    }

    @Test
    @DisplayName("인증된 사용자는 메모 목록을 조회할 수 있다")
    void getMemosWithAuth() throws Exception {
        given(memoService.getMyMemos(any(UUID.class))).willReturn(new ArrayList<>());

        mockMvc.perform(get("/api/memos")
                        .principal(() -> "ac61d5c9-fc9d-4922-9afc-b9aadfb09ee8"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("메모 생성 테스트")
    void createMemo() throws Exception {
        UUID userId = UUID.fromString("ac61d5c9-fc9d-4922-9afc-b9aadfb09ee8");
        MemoRequest request = MemoRequest.builder()
                .title("테스트 제목")
                .content("테스트 내용")
                .build();

        String requestJson = objectMapper.writeValueAsString(request);
        MockMultipartFile memoPart = new MockMultipartFile(
                "memo",
                "",
                "application/json",
                requestJson.getBytes(StandardCharsets.UTF_8)
        );

        given(memoService.createMemo(eq(userId), any(MemoRequest.class), any())).willReturn(new MemoResponse());

        mockMvc.perform(multipart("/api/memos")
                        .file(memoPart)
                        .principal(() -> "ac61d5c9-fc9d-4922-9afc-b9aadfb09ee8")
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("메모 상세 조회 테스트")
    void getMemoDetail() throws Exception {
        UUID memoId = UUID.randomUUID();
        given(memoService.getMemo(any(UUID.class), eq(memoId))).willReturn(new MemoResponse());

        mockMvc.perform(get("/api/memos/" + memoId)
                        .principal(() -> "ac61d5c9-fc9d-4922-9afc-b9aadfb09ee8"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("메모 삭제 테스트")
    void deleteMemo() throws Exception {
        UUID memoId = UUID.randomUUID();

        mockMvc.perform(delete("/api/memos/" + memoId)
                        .principal(() -> "ac61d5c9-fc9d-4922-9afc-b9aadfb09ee8"))
                .andExpect(status().isNoContent());
    }
}
