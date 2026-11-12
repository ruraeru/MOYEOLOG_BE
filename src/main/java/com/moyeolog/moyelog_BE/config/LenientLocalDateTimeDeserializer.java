package com.moyeolog.moyelog_BE.config;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 일정 시간용 LocalDateTime 역직렬화기.
 * <ul>
 *   <li>오프셋 없는 값(yyyy-MM-dd'T'HH:mm[:ss[.SSS]])은 한국 시간 벽시계 값으로 그대로 사용합니다.</li>
 *   <li>오프셋이 있는 예전 형식(…Z, …+09:00)은 오류 없이 받아 Asia/Seoul 시각으로 변환합니다.</li>
 * </ul>
 */
public class LenientLocalDateTimeDeserializer extends ValueDeserializer<LocalDateTime> {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) {
        String text = p.getValueAsString();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return parse(text.trim());
        } catch (DateTimeParseException e) {
            return (LocalDateTime) ctxt.handleWeirdStringValue(LocalDateTime.class, text,
                    "expected yyyy-MM-dd'T'HH:mm:ss");
        }
    }

    public static LocalDateTime parse(String text) {
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .atZoneSameInstant(ZONE)
                    .toLocalDateTime();
        }
    }
}
