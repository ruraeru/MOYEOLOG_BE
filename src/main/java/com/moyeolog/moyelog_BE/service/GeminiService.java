package com.moyeolog.moyelog_BE.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyeolog.moyelog_BE.entity.GroupTopic;
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

    @Value("${gemini.api.key:}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final FileService fileService;
    private final ObjectMapper objectMapper;

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1/models/gemini-3.1-flash-lite:generateContent";
    private static final String API_KEY_HEADER = "x-goog-api-key";
    private static final String GENERIC_ERROR_MESSAGE = "AI 분석 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.";

    public Map<String, Object> analyzeMemo(Memo memo) {
        return analyze(memo.getContent(), memo.getImageUrl(), memo.getId().toString());
    }

    public Map<String, Object> analyzeTopic(GroupTopic topic) {
        return analyze(topic.getContent(), topic.getImageUrl(), topic.getId().toString());
    }

    private Map<String, Object> analyze(String content, String imageUrl, String id) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new com.moyeolog.moyelog_BE.exception.AiAnalysisException("AI 분석 기능이 설정되지 않았습니다.");
        }
        String prompt = "You are an AI assistant analyzing a user's memo or topic. " +
                "Analyze the following text and image (if provided). " +
                "1. OCR: Extract all readable text from the image. If no image, return empty string. " +
                "2. Summary: Summarize the content (text + OCR) IN KOREAN in exactly 3 lines. Each line must be a complete sentence. " +
                "3. Keywords: Extract up to 5 main keywords IN KOREAN. " +
                "Return the result STRICTLY as a JSON object with keys: ocrText (string), summary (string), keywords (array of strings). " +
                "Text content: " + (content != null ? content : "");

        try {
            Map<String, Object> requestBody = new HashMap<>();
            List<Map<String, Object>> contents = new ArrayList<>();
            Map<String, Object> reqContent = new HashMap<>();
            List<Map<String, Object>> parts = new ArrayList<>();

            // 1. Text Part
            Map<String, Object> textPart = new HashMap<>();
            textPart.put("text", prompt);
            parts.add(textPart);

            // 2. Image Part (if exists)
// 업로드 디렉터리 안의 실제 이미지 파일만 전송한다 (경로 검증은 FileService 가 담당).
            FileService.ImageData image = fileService.loadImage(imageUrl).orElse(null);
            if (image != null) {
                log.info("[Gemini] Including image in analysis for ID: {}", id);
                Map<String, Object> imagePart = new HashMap<>();
                Map<String, Object> inlineData = new HashMap<>();
                imagePart.put("inline_data", inlineData);
                inlineData.put("mime_type", image.mimeType());
                inlineData.put("data", image.base64());
                parts.add(imagePart);
            }

            reqContent.put("parts", parts);
            contents.add(reqContent);
            requestBody.put("contents", contents);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // API 키는 URL(쿼리 스트링)이 아니라 헤더로 보낸다 → 예외 메시지/로그에 키가 남지 않는다.
            headers.set(API_KEY_HEADER, apiKey);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            int maxRetries = 3;
            long waitTime = 2000; // Start with 2 seconds

            String response = null;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    log.info("[Gemini] Calling Gemini API for ID: {} (Attempt {}/{})", id, attempt, maxRetries);
                    response = restTemplate.postForObject(GEMINI_API_URL, entity, String.class);
                    log.info("[Gemini] API Response received successfully");
                    break; // Success, exit retry loop
                } catch (org.springframework.web.client.HttpStatusCodeException e) {
                    int statusCode = e.getStatusCode().value();
                    if (statusCode == 503 || statusCode == 429) {
                        log.warn("[Gemini] API temporarily unavailable or rate limited ({}). Retrying in {}ms...", statusCode, waitTime);
                        if (attempt == maxRetries) {
                            throw e; // Rethrow if max retries reached
                        }
                        try {
                            Thread.sleep(waitTime);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Retry interrupted", ie);
                        }
                        waitTime *= 2; // Exponential backoff (2s -> 4s -> 8s)
                    } else {
                        throw e; // Do not retry for other HTTP errors (like 400 Bad Request)
                    }
                }
            }
            
            return parseGeminiResponse(response);
        } catch (com.moyeolog.moyelog_BE.exception.AiAnalysisException e) {
            throw e;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // 상위 API 의 응답 본문/URL 은 클라이언트에 돌려주지 않고 고정 문구만 사용한다.
            log.error("[Gemini] AI 분석 실패 (ID: {}): upstream status {}", id, e.getStatusCode().value());
            throw new com.moyeolog.moyelog_BE.exception.AiAnalysisException(GENERIC_ERROR_MESSAGE);
        } catch (Exception e) {
            log.error("[Gemini] AI 분석 실패 (ID: {}): {}", id, e.getClass().getSimpleName(), e);
            throw new com.moyeolog.moyelog_BE.exception.AiAnalysisException(GENERIC_ERROR_MESSAGE);
        }
    }

    private Map<String, Object> parseGeminiResponse(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        
        JsonNode candidates = root.path("candidates");
        if (candidates.isMissingNode() || candidates.size() == 0) {
            log.error("[Gemini] No candidates found in response: {}", response);
            throw new com.moyeolog.moyelog_BE.exception.AiAnalysisException("AI가 응답을 생성하지 못했습니다.");
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

            Map<String, Object> parsed = objectMapper.readValue(jsonStr, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            return normalize(parsed);
        } catch (Exception e) {
            log.error("[Gemini] JSON Parsing Failed. Raw text: {}", textResponse);
            throw new com.moyeolog.moyelog_BE.exception.AiAnalysisException("AI 응답 형식이 올바르지 않습니다.");
        }
    }

    /** Gemini 가 돌려준 값의 타입을 고정한다: ocrText/summary 는 문자열, keywords 는 null 이 아닌 문자열 목록. */
    private Map<String, Object> normalize(Map<String, Object> parsed) {
        Map<String, Object> result = new HashMap<>();
        result.put("ocrText", asText(parsed != null ? parsed.get("ocrText") : null));
        result.put("summary", asText(parsed != null ? parsed.get("summary") : null));
        result.put("keywords", toKeywords(parsed != null ? parsed.get("keywords") : null));
        return result;
    }

    private static String asText(Object value) {
        if (value == null) return "";
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(Object::toString)
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
        return value.toString();
    }

    /** null 이거나 목록이 아닌 값은 빈 목록으로 바꾼다. */
    public static List<String> toKeywords(Object value) {
        List<String> keywords = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    keywords.add(item.toString().trim());
                }
            }
        } else if (value instanceof String text && !text.isBlank()) {
            keywords.add(text.trim());
        }
        return keywords;
    }
}
