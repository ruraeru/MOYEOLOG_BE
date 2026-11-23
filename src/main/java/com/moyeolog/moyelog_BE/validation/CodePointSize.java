package com.moyeolog.moyelog_BE.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * 문자열 길이를 코드 포인트(사람이 보는 글자, 이모지 1개 = 1자) 기준으로 검사한다.
 * Bean Validation 의 @Size 는 UTF-16 코드 유닛으로 세서 이모지가 2자로 계산된다.
 * null 은 통과(필수 여부는 @NotBlank 등으로 따로 검사).
 */
@Documented
@Constraint(validatedBy = CodePointSizeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface CodePointSize {
    int min() default 0;
    int max() default Integer.MAX_VALUE;
    String message() default "글자 수가 허용 범위를 벗어났어요.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
