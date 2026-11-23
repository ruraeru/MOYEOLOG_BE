package com.moyeolog.moyelog_BE.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * 404. 메시지는 사용자에게 그대로 보여 줄 수 있는 해요체 문장이며, 입력값·ID 는 넣지 않는다.
 */
public class ResourceNotFoundException extends MoyeologException {

    /** 화면 용어로 바꿀 이름과, 따로 정한 문장 */
    private static final Map<String, String> DISPLAY_NAMES = Map.of(
            "그룹", "모임",
            "그룹 멤버", "모임 멤버"
    );
    private static final Map<String, String> CUSTOM_MESSAGES = Map.of(
            "초대 코드", "초대 코드가 맞지 않거나 만료됐어요."
    );

    /**
     * @param resourceName 사용자에게 보일 대상 이름 (예: "사용자")
     * @param identifier   로그용으로만 받았던 값. 메시지에는 넣지 않는다 (호환을 위해 남겨 둠).
     */
    public ResourceNotFoundException(String resourceName, Object identifier) {
        super(messageFor(resourceName), HttpStatus.NOT_FOUND);
    }

    /** 문장을 직접 정할 때 */
    public ResourceNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND);
    }

    static String messageFor(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            return "요청한 항목을 찾을 수 없어요.";
        }
        String name = resourceName.trim();
        String custom = CUSTOM_MESSAGES.get(name);
        if (custom != null) {
            return custom;
        }
        name = DISPLAY_NAMES.getOrDefault(name, name);
        return name + objectParticle(name) + " 찾을 수 없어요.";
    }

    /** 마지막 글자의 받침 유무로 '을'/'를' 을 고른다. 한글이 아니면 '을(를)' 대신 '를' 로 둔다. */
    static String objectParticle(String word) {
        char last = word.charAt(word.length() - 1);
        if (last >= 0xAC00 && last <= 0xD7A3) {
            return (last - 0xAC00) % 28 == 0 ? "를" : "을";
        }
        return "를";
    }
}
