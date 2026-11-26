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

    /** 비어 있으면 Ollama 실패 시 Gemini 로 넘기지 않는다. */
    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${llm.base-url:https://ai.ruraeru.xyz}")
    private String llmBaseUrl;

    @Value("${llm.model:qwen3.5:9b}")
    private String llmModel;

    @Value("${llm.api-key:}")
    private String llmApiKey;

    private final RestTemplate restTemplate;
    private final FileService fileService;
    private final ObjectMapper objectMapper;

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1/models/gemini-3.1-flash-lite:generateContent";
    private static final String API_KEY_HEADER = "x-goog-api-key";
    private static final String GENERIC_ERROR_MESSAGE = "AI 분석 중 문제가 생겼어요. 잠시 후 다시 시도해 주세요.";

    public Map<String, Object> analyzeMemo(Memo memo) {
        return analyze(memo.getContent(), memo.getImageUrl(), memo.getId().toString());
    }

    public Map<String, Object> analyzeTopic(GroupTopic topic) {
        return analyze(topic.getContent(), topic.getImageUrl(), topic.getId().toString());
    }

    /**
     * 본문과 이미지(업로드 파일 "/uploads/..." 또는 data URL)를 분석한다.
     * DB 트랜잭션 밖에서 부르도록 엔티티 대신 값만 받는다.
     * 결과: ocrText(이미지가 없으면 빈 문자열), summary, keywords.
     */
    public Map<String, Object> analyze(String content, String imageUrl, String id) {
        try {
            // 업로드 디렉터리 안의 실제 이미지 파일 또는 data URL 이미지만 전송한다 (검증은 FileService 가 담당).
            FileService.ImageData image = fileService.loadImageForAnalysis(imageUrl).orElse(null);
            if (image != null) {
                log.info("[AI] Including image in analysis for ID: {}", id);
            }
            String prompt = buildPrompt(content, image != null);
            Map<String, Object> result = complete(prompt, ANALYSIS_SCHEMA, image, id, this::parseResult);
            if (image == null) {
                // 이미지가 없으면 모델이 본문을 옮겨 적어도 OCR 결과는 비운다.
                result.put("ocrText", "");
            }
            return result;
        } catch (com.moyeolog.moyelog_BE.exception.AiAnalysisException e) {
            throw e;
        } catch (Exception e) {
            // 상위 API 의 응답 본문/URL 은 클라이언트에 돌려주지 않고 고정 문구만 사용한다.
            log.error("[AI] AI 분석 실패 (ID: {}): {}", id, describe(e), e);
            throw new com.moyeolog.moyelog_BE.exception.AiAnalysisException(GENERIC_ERROR_MESSAGE);
        }
    }

    /**
     * 자유 프롬프트로 JSON 객체 하나를 받는다(이미지 없음). schema 는 Ollama format 에 넣는 JSON Schema.
     * 실패 흐름은 analyze 와 같다: Ollama 실패 → (키가 있으면) Gemini, 그래도 실패하면 AiAnalysisException(502).
     * 결과 값의 타입 검사는 부르는 쪽이 한다.
     */
    public Map<String, Object> generateJson(String prompt, Map<String, Object> schema, String id) {
        try {
            return complete(prompt, schema, null, id, this::parseJson);
        } catch (com.moyeolog.moyelog_BE.exception.AiAnalysisException e) {
            throw e;
        } catch (Exception e) {
            log.error("[AI] AI 생성 실패 (ID: {}): {}", id, describe(e), e);
            throw new com.moyeolog.moyelog_BE.exception.AiAnalysisException(GENERIC_ERROR_MESSAGE);
        }
    }

    private static final Map<String, Object> ANALYSIS_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "ocrText", Map.of("type", "string"),
                    "summary", Map.of("type", "string"),
                    "keywords", Map.of("type", "array", "items", Map.of("type", "string"))),
            "required", List.of("ocrText", "summary", "keywords"));

    /** Ollama 에 보내고, 실패하면(연결·시간 초과·HTTP 오류·빈 응답·형식 오류) Gemini 키가 있을 때만 Gemini 로 다시 보낸다. */
    private Map<String, Object> complete(String prompt, Map<String, Object> schema, FileService.ImageData image,
                                         String id, java.util.function.Function<String, Map<String, Object>> parser)
            throws Exception {
        try {
            return call(true, llmBaseUrl.replaceAll("/+$", "") + "/api/chat", ollamaRequest(prompt, schema, image), id, parser);
        } catch (Exception e) {
            if (apiKey == null || apiKey.isBlank()) {
                throw e;
            }
            log.warn("[AI] Ollama 실패 (ID: {}): {}. Gemini 로 재시도", id, describe(e));
            return call(false, GEMINI_API_URL, geminiRequest(prompt, image), id, parser);
        }
    }

    static String buildPrompt(String content, boolean hasImage) {
        String ocrRule = hasImage
                ? "1. ocrText: Transcribe all readable text that appears INSIDE THE ATTACHED IMAGE, line by line. "
                        + "Do NOT copy the text content given below into ocrText. If the image has no readable text, return an empty string. "
                : "1. ocrText: No image is attached, so ocrText MUST be an empty string. ";
        String summaryRule = hasImage
                ? "2. summary: Summarize the text content AND the information in the image (including its text) IN KOREAN "
                : "2. summary: Summarize the text content IN KOREAN ";
        return "You are an AI assistant analyzing a user's memo or topic. "
                + ocrRule
                + summaryRule
                + "in exactly 3 complete sentences. Separate the sentences with a newline character. "
                + "3. keywords: Extract up to 5 main keywords IN KOREAN. "
                + "Return the result STRICTLY as a JSON object with keys: ocrText (string), summary (string), keywords (array of strings). "
                + "Text content: " + (content != null ? content : "");
    }

    private static String describe(Exception e) {
        if (e instanceof org.springframework.web.client.HttpStatusCodeException h) {
            return "upstream status " + h.getStatusCode().value();
        }
        return e.getClass().getSimpleName();
    }

    /** 한 공급자에 요청하고 결과를 파싱한다. 503/429 는 최대 3번까지 다시 보낸다. */
    private Map<String, Object> call(boolean ollama, String url, HttpEntity<?> entity, String id,
                                     java.util.function.Function<String, Map<String, Object>> parser) throws Exception {
        int maxRetries = 3;
        long waitTime = 2000; // Start with 2 seconds

        byte[] response = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("[AI] Calling {} for ID: {} (Attempt {}/{})", ollama ? llmModel : "Gemini", id, attempt, maxRetries);
                // byte[] 로 받아 UTF-8 로 푼다. Ollama 는 charset 없이 응답할 때가 있어 String 으로 받으면 한글이 깨진다.
                response = restTemplate.postForObject(url, entity, byte[].class);
                log.info("[AI] API Response received successfully");
                break; // Success, exit retry loop
            } catch (org.springframework.web.client.HttpStatusCodeException e) {
                int statusCode = e.getStatusCode().value();
                if (statusCode == 503 || statusCode == 429) {
                    log.warn("[AI] API temporarily unavailable or rate limited ({}). Retrying in {}ms...", statusCode, waitTime);
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

        String body = response == null ? "" : new String(response, java.nio.charset.StandardCharsets.UTF_8);
        return parser.apply(ollama ? ollamaText(body) : geminiText(body));
    }

    /** Ollama /api/chat 요청. format 에 JSON Schema 를 줘서 결과 형식을 고정한다. */
    private HttpEntity<Map<String, Object>> ollamaRequest(String prompt, Map<String, Object> schema, FileService.ImageData image) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        if (image != null) {
            message.put("images", List.of(image.base64()));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("model", llmModel);
        body.put("messages", List.of(message));
        body.put("format", schema);
        body.put("think", false);
        body.put("stream", false);
        body.put("keep_alive", "30m");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 환경변수에 붙여넣을 때 섞이는 공백·따옴표·"Bearer " 를 걷어낸다
        String key = llmApiKey == null ? "" : llmApiKey.trim().replaceAll("^[\"']|[\"']$", "").replaceFirst("(?i)^Bearer\\s+", "");
        if (!key.isEmpty()) {
            headers.setBearerAuth(key);
        }
        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<Map<String, Object>> geminiRequest(String prompt, FileService.ImageData image) {
        Map<String, Object> requestBody = new HashMap<>();
        List<Map<String, Object>> contents = new ArrayList<>();
        Map<String, Object> reqContent = new HashMap<>();
        List<Map<String, Object>> parts = new ArrayList<>();

        // 1. Text Part
        Map<String, Object> textPart = new HashMap<>();
        textPart.put("text", prompt);
        parts.add(textPart);

        // 2. Image Part (if exists)
        if (image != null) {
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
        return new HttpEntity<>(requestBody, headers);
    }

    private String ollamaText(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        String text = root.path("message").path("content").asText("");
        if (text.isBlank()) {
            log.error("[AI] Empty Ollama response (error: {}, done_reason: {})",
                    root.path("error").asText(""), root.path("done_reason").asText(""));
            throw new com.moyeolog.moyelog_BE.exception.AiAnalysisException("AI가 답을 만들지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
        return text;
    }

    private String geminiText(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        
        JsonNode candidates = root.path("candidates");
        if (candidates.isMissingNode() || candidates.size() == 0) {
            log.error("[Gemini] No candidates found in response: {}", response);
            throw new com.moyeolog.moyelog_BE.exception.AiAnalysisException("AI가 답을 만들지 못했어요. 잠시 후 다시 시도해 주세요.");
        }

        return candidates.get(0).path("content").path("parts").get(0).path("text").asText();
    }

    private Map<String, Object> parseResult(String textResponse) {
        return normalize(parseJson(textResponse));
    }

    /** 응답 글에서 JSON 객체를 꺼낸다(마크다운 코드 블록 허용). 아니면 AiAnalysisException. */
    private Map<String, Object> parseJson(String textResponse) {
        log.debug("[AI] Raw Text Response: {}", textResponse);

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
            if (parsed == null) throw new IllegalStateException("null JSON");
            return parsed;
        } catch (Exception e) {
            log.error("[AI] JSON Parsing Failed. Raw text: {}", textResponse);
            throw new com.moyeolog.moyelog_BE.exception.AiAnalysisException("AI 응답 형식이 올바르지 않아요. 잠시 후 다시 시도해 주세요.");
        }
    }

    /** Gemini 가 돌려준 값의 타입을 고정한다: ocrText/summary 는 문자열, keywords 는 null 이 아닌 문자열 목록. */
    private Map<String, Object> normalize(Map<String, Object> parsed) {
        Map<String, Object> result = new HashMap<>();
        result.put("ocrText", asText(parsed != null ? parsed.get("ocrText") : null));
        result.put("summary", spaceSentences(asText(parsed != null ? parsed.get("summary") : null)));
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

    /** 한글 뒤 문장부호 바로 다음에 공백 없이 다음 문장이 붙으면("있다.학교") 공백을 넣는다. 소수점("3.5")은 그대로. */
    private static final java.util.regex.Pattern GLUED_SENTENCE =
            java.util.regex.Pattern.compile("(?<=[가-힣](?:\\.{1,3}|[!?]))(?=[^\\s.!?,;:)\\]}'\"”’])");

    static String spaceSentences(String text) {
        if (text == null || text.isEmpty()) return text;
        return GLUED_SENTENCE.matcher(text).replaceAll(" ");
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
