package com.moyeolog.moyelog_BE.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyeolog.moyelog_BE.entity.Memo;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeminiServiceTest {

    @Test
    void ollamaProviderCallsChatEndpointAndParsesResult() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        FileService fileService = mock(FileService.class);
        when(fileService.loadImageForAnalysis(any())).thenReturn(Optional.empty());
        GeminiService service = new GeminiService(restTemplate, fileService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "llmBaseUrl", "https://ai.example/");
        ReflectionTestUtils.setField(service, "llmModel", "qwen3.5:9b");
        ReflectionTestUtils.setField(service, "llmApiKey", " Bearer tok ");

        // charset 없는 응답이어도 한글이 깨지지 않아야 한다
        String reply = "{\"message\":{\"content\":\"{\\\"ocrText\\\":\\\"\\\",\\\"summary\\\":\\\"요약\\\",\\\"keywords\\\":[\\\"회의\\\"]}\"},\"done\":true}";
        server.expect(requestTo("https://ai.example/api/chat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok"))
                .andExpect(jsonPath("$.model").value("qwen3.5:9b"))
                .andExpect(jsonPath("$.stream").value(false))
                .andRespond(withSuccess(reply.getBytes(java.nio.charset.StandardCharsets.UTF_8), MediaType.APPLICATION_JSON));

        Memo memo = Memo.builder().id(UUID.randomUUID()).content("내일 회의").build();
        Map<String, Object> result = service.analyzeMemo(memo);

        server.verify();
        assertThat(result.get("summary")).isEqualTo("요약");
        assertThat(result.get("keywords")).isEqualTo(List.of("회의"));
    }

    @Test
    void fallsBackToGeminiWhenOllamaFails() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        FileService fileService = mock(FileService.class);
        when(fileService.loadImageForAnalysis(any())).thenReturn(Optional.empty());
        GeminiService service = new GeminiService(restTemplate, fileService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "llmBaseUrl", "https://ai.example");
        ReflectionTestUtils.setField(service, "llmModel", "qwen3.5:9b");
        ReflectionTestUtils.setField(service, "llmApiKey", "");
        ReflectionTestUtils.setField(service, "apiKey", "gem-key");

        String reply = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"ocrText\\\":\\\"\\\",\\\"summary\\\":\\\"요약\\\",\\\"keywords\\\":[]}\"}]}}]}";
        server.expect(requestTo("https://ai.example/api/chat")).andRespond(withServerError());
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://generativelanguage.googleapis.com/")))
                .andExpect(header("x-goog-api-key", "gem-key"))
                .andRespond(withSuccess(reply.getBytes(java.nio.charset.StandardCharsets.UTF_8), MediaType.APPLICATION_JSON));

        Memo memo = Memo.builder().id(UUID.randomUUID()).content("내일 회의").build();
        Map<String, Object> result = service.analyzeMemo(memo);

        server.verify();
        assertThat(result.get("summary")).isEqualTo("요약");
    }

    private static String ollamaReply(String ocrText, String summary) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String inner = mapper.writeValueAsString(Map.of("ocrText", ocrText, "summary", summary, "keywords", List.of("회의")));
        return mapper.writeValueAsString(Map.of("message", Map.of("content", inner), "done", true));
    }

    private static GeminiService ollamaOnly(RestTemplate restTemplate, FileService fileService) {
        GeminiService service = new GeminiService(restTemplate, fileService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "llmBaseUrl", "https://ai.example");
        ReflectionTestUtils.setField(service, "llmModel", "qwen3.5:9b");
        ReflectionTestUtils.setField(service, "llmApiKey", "");
        ReflectionTestUtils.setField(service, "apiKey", "");
        return service;
    }

    @Test
    void topicDataUrlImageIsSentToModel() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        GeminiService service = ollamaOnly(restTemplate, new FileService());

        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        String b64 = java.util.Base64.getEncoder().encodeToString(png);
        server.expect(requestTo("https://ai.example/api/chat"))
                .andExpect(jsonPath("$.messages[0].images[0]").value(b64))
                .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.containsString("INSIDE THE ATTACHED IMAGE")))
                .andRespond(withSuccess(ollamaReply("3주차 회의 결과\n발표일 12월 3일", "요약").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        MediaType.APPLICATION_JSON));

        Map<String, Object> result = service.analyze("화이트보드 사진 올려요", "data:image/png;base64," + b64, "t1");

        server.verify();
        assertThat(result.get("ocrText")).isEqualTo("3주차 회의 결과\n발표일 12월 3일");
    }

    @Test
    void ocrTextIsEmptyWithoutImageEvenIfModelCopiesText() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        GeminiService service = ollamaOnly(restTemplate, new FileService());

        server.expect(requestTo("https://ai.example/api/chat"))
                .andExpect(jsonPath("$.messages[0].images").doesNotExist())
                .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.containsString("MUST be an empty string")))
                .andRespond(withSuccess(ollamaReply("지난 회의 화이트보드 사진 올려요", "회식이 있다.학교 앞이다.평점 3.5점이다...좋다.")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8), MediaType.APPLICATION_JSON));

        Map<String, Object> result = service.analyze("지난 회의 화이트보드 사진 올려요", null, "t2");

        server.verify();
        assertThat(result.get("ocrText")).isEqualTo("");
        // 문장 사이 공백 보정 (소수점·말줄임표는 그대로)
        assertThat(result.get("summary")).isEqualTo("회식이 있다. 학교 앞이다. 평점 3.5점이다... 좋다.");
    }

    @Test
    void spaceSentencesKeepsExistingSpacingAndNewlines() {
        assertThat(GeminiService.spaceSentences("첫 문장이다.\n둘째 문장이다. 셋째다!")).isEqualTo("첫 문장이다.\n둘째 문장이다. 셋째다!");
        assertThat(GeminiService.spaceSentences("정말?네.")).isEqualTo("정말? 네.");
        assertThat(GeminiService.spaceSentences("")).isEqualTo("");
    }
}
