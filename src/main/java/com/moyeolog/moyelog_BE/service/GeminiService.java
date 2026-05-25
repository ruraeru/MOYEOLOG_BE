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

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1/models/gemini-3.1-flash:generateContent?key=";

    public Map<String, Object> analyzeMemo(Memo memo) {
        String prompt = "You are an AI assistant analyzing a user's memo. " +
                "Analyze the following text and image (if provided). " +
                "1. OCR: Extract all readable text from the image. If no image, return empty string. " +
                "2. Summary: Summarize the content (text + OCR) in exactly 3 lines IN KOREAN. " +
                "3. Emotion: Evaluate the overall emotion as one of: 긍정, 부정, 중립. " +
                "4. Keywords: Extract up to 5 main keywords IN KOREAN. " +
                "Return the result STRICTLY as a JSON object with keys: ocrText (string), summary (string), emotion (string), keywords (array of strings). " +
                "Text content: " + (memo.getContent() != null ? memo.getContent() : "");

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
                log.info("[Gemini] Including image in analysis: {}", memo.getImageUrl());
                Map<String, Object> imagePart = new HashMap<>();
                Map<String, Object> inlineData = new HashMap<>();
                imagePart.put("inline_data", inlineData);
                
                String mimeType = "image/png";
                if (memo.getImageUrl().toLowerCase().endsWith(".jpg") || memo.getImageUrl().toLowerCase().endsWith(".jpeg")) {
                    mimeType = "image/jpeg";
                }
                
                inlineData.put("mime_type", mimeType);
                inlineData.put("data", base64Image);
                parts.add(imagePart);
            }

            content.put("parts", parts);
            contents.add(content);
            requestBody.put("contents", contents);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.info("[Gemini] Calling Gemini API for memo: {}", memo.getId());
            String response = restTemplate.postForObject(GEMINI_API_URL + apiKey, entity, String.class);
            log.info("[Gemini] API Response received successfully");
            
            return parseGeminiResponse(response);

        } catch (Exception e) {
            log.error("[Gemini] AI 분석 실패: {}", e.getMessage(), e);
            throw new RuntimeException("AI 분석 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    private Map<String, Object> parseGeminiResponse(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        
        JsonNode candidates = root.path("candidates");
        if (candidates.isMissingNode() || candidates.size() == 0) {
            log.error("[Gemini] No candidates found in response: {}", response);
            throw new RuntimeException("AI가 응답을 생성하지 못했습니다.");
        }

        String textResponse = candidates.get(0).path("content").path("parts").get(0).path("text").asText();
        log.debug("[Gemini] Raw Text Response: {}", textResponse);
        
        try {
            // JSON 부분만 추출 (마크다운 코드 블록 제거)
            String jsonStr = textResponse.trim();
            if (jsonStr.contains("```json")) {
                jsonStr = jsonStr.substring(jsonStr.indexOf("```json") + 7);
                jsonStr = jsonStr.substring(0, jsonStr.lastIndexOf("```")).trim();
            } else if (jsonStr.contains("```")) {
                jsonStr = jsonStr.substring(jsonStr.indexOf("```") + 3);
                jsonStr = jsonStr.substring(0, jsonStr.lastIndexOf("```")).trim();
            }

            return objectMapper.readValue(jsonStr, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("[Gemini] JSON Parsing Failed. Raw text: {}", textResponse);
            throw new RuntimeException("AI 응답 형식이 올바르지 않습니다.");
        }
    }
}
