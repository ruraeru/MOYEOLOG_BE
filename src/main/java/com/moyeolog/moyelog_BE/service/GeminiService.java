package com.moyeolog.moyelog_BE.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyeolog.moyelog_BE.entity.Memo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiService {

    @Value("${gemini.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final FileService fileService;
    private final ObjectMapper objectMapper;

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=";

    public Map<String, Object> analyzeMemo(Memo memo) {
        String prompt = "You are an AI assistant analyzing a user's memo. " +
                "Analyze the following text and image (if provided). " +
                "1. OCR: Extract all readable text from the image. If no image, return empty string. " +
                "2. Summary: Summarize the content (text + OCR) in exactly 3 lines IN KOREAN. " +
                "3. Emotion: Evaluate the overall emotion as one of: 긍정, 부정, 중립. " +
                "4. Keywords: Extract up to 5 main keywords IN KOREAN. " +
                "Return the result STRICTLY as a JSON object with keys: ocrText, summary, emotion, keywords (array of strings). " +
                "Text content: " + memo.getContent();

        try {
            Map<String, Object> requestBody = new HashMap<>();
            List<Map<String, Object>> contents = new ArrayList<>();
            Map<String, Object> content = new HashMap<>();
            List<Map<String, Object>> parts = new ArrayList<>();

            // 1. Text Part
            Map<String, Object> textPart = new HashMap<>();
            textPart.put("text", prompt);
            parts.add(textPart);

            // 2. Image Part (if exists)
            String base64Image = fileService.getFileAsBase64(memo.getImageUrl());
            if (base64Image != null) {
                Map<String, Object> imagePart = new HashMap<>();
                Map<String, Object> inlineData = new HashMap<>();
                imagePart.put("inline_data", inlineData);
                inlineData.put("mime_type", "image/png"); // 기본적으로 png로 가정, 필요시 동적 처리 가능
                inlineData.put("data", base64Image);
                parts.add(imagePart);
            }

            content.put("parts", parts);
            contents.add(content);
            requestBody.put("contents", contents);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            String response = restTemplate.postForObject(GEMINI_API_URL + apiKey, entity, String.class);
            return parseGeminiResponse(response);

        } catch (Exception e) {
            log.error("Gemini API call failed", e);
            throw new RuntimeException("AI 분석에 실패했습니다: " + e.getMessage());
        }
    }

    private Map<String, Object> parseGeminiResponse(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        String textResponse = root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
        
        // JSON 부분만 추출 (마크다운 코드 블록 제거)
        String jsonStr = textResponse;
        if (textResponse.contains("```json")) {
            jsonStr = textResponse.substring(textResponse.indexOf("```json") + 7, textResponse.lastIndexOf("```"));
        } else if (textResponse.contains("```")) {
            jsonStr = textResponse.substring(textResponse.indexOf("```") + 3, textResponse.lastIndexOf("```"));
        }

        return objectMapper.readValue(jsonStr, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }
}
