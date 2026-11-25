package com.moyeolog.moyelog_BE.weather;

import com.moyeolog.moyelog_BE.exception.MoyeologException;
import org.springframework.http.HttpStatus;

public class WeatherUnavailableException extends MoyeologException {
    public WeatherUnavailableException() {
        super("지금은 날씨를 불러올 수 없어요.", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
