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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeminiServiceTest {

    @Test
    void ollamaProviderCallsChatEndpointAndParsesResult() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        FileService fileService = mock(FileService.class);
        when(fileService.loadImage(any())).thenReturn(Optional.empty());
        GeminiService service = new GeminiService(restTemplate, fileService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "provider", "ollama");
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
}
