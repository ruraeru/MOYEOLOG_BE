package com.moyeolog.moyelog_BE.weather;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;

    /** 좌표의 예보: 시간별(단기, 3~4일)과 일별(단기 + 중기, 10일까지). */
    @GetMapping
    public WeatherService.Forecast forecast(@RequestParam double lat, @RequestParam double lng) {
        return weatherService.forecast(lat, lng);
    }
}
