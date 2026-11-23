package com.moyeolog.moyelog_BE.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CodePointSizeValidator implements ConstraintValidator<CodePointSize, String> {
    private int min;
    private int max;

    @Override
    public void initialize(CodePointSize annotation) {
        this.min = annotation.min();
        this.max = annotation.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) return true;
        int length = value.codePointCount(0, value.length());
        return length >= min && length <= max;
    }

    /** 서비스 코드에서 직접 쓸 때: 코드 포인트 기준 길이 */
    public static int length(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    /** 서로게이트 쌍을 쪼개지 않고 앞에서 maxCodePoints 글자까지 자른다 */
    public static String truncate(String value, int maxCodePoints) {
        if (value == null || length(value) <= maxCodePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }
}
