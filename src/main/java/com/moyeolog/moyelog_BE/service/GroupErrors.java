package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.exception.BadRequestException;
import com.moyeolog.moyelog_BE.exception.ConflictException;
import com.moyeolog.moyelog_BE.exception.MoyeologException;
import com.moyeolog.moyelog_BE.exception.UnauthorizedAccessException;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * 모임·토픽 기능의 오류. 메시지는 사용자에게 그대로 보여 줄 해요체 문장이며 입력값·ID 를 넣지 않는다.
 * GlobalExceptionHandler 가 MoyeologException 으로 처리한다.
 */
public final class GroupErrors {

    public static final String GROUP_NOT_FOUND = "모임을 찾을 수 없어요.";
    public static final String NOT_MEMBER = "이 모임의 멤버가 아니에요.";
    public static final String INVALID_INVITE_CODE = "초대 코드가 맞지 않거나 만료됐어요.";
    public static final String ALREADY_MEMBER = "이미 참여한 모임이에요.";
    public static final String BLOCKED = "이 모임에 다시 들어갈 수 없어요.";

    private GroupErrors() {
    }

    public static class GroupApiException extends MoyeologException {
        public GroupApiException(String message, HttpStatus status) {
            super(message, status);
        }
    }

    /** 이미 멤버인 모임에 다시 가입하려 할 때 (409, 본문에 groupId 포함) */
    @Getter
    public static class AlreadyMemberException extends ConflictException {
        private final UUID groupId;

        public AlreadyMemberException(UUID groupId) {
            super(ALREADY_MEMBER);
            this.groupId = groupId;
        }
    }

    public static GroupApiException notFound(String message) {
        return new GroupApiException(message, HttpStatus.NOT_FOUND);
    }

    public static UnauthorizedAccessException forbidden(String message) {
        return new UnauthorizedAccessException(message);
    }

    public static BadRequestException badRequest(String message) {
        return new BadRequestException(message);
    }

    public static ConflictException conflict(String message) {
        return new ConflictException(message);
    }

    /** 보이지 않는 문자(U+200B~U+200F, U+2060, U+FEFF, U+00AD 등)와 공백만으로 이뤄졌는지 */
    public static boolean isVisuallyBlank(String value) {
        if (value == null) {
            return true;
        }
        return value.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp)
                || Character.getType(cp) == Character.FORMAT || Character.isISOControl(cp)
                || cp == 0x3164 || cp == 0x115F || cp == 0x1160 || cp == 0xFFA0);
    }
}
