package com.moyeolog.moyelog_BE.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WeatherServiceTest {

    // 2026-11-25 09:00 KST → 단기 발표 08:00, 중기 발표 06:00
    private final Clock clock = Clock.fixed(
            LocalDateTime.of(2026, 11, 25, 9, 0).atZone(WeatherService.KST).toInstant(), WeatherService.KST);
    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
    private final WeatherService service = service("key");

    private WeatherService service(String key) {
        WeatherService s = new WeatherService(restTemplate, new ObjectMapper(), clock);
        ReflectionTestUtils.setField(s, "serviceKey", key);
        ReflectionTestUtils.setField(s, "baseUrl", "https://kma.example/");
        return s;
    }

    private static String wrap(String items) {
        return "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":[" + items + "]}}}}";
    }

    private static String item(String date, String time, String category, String value) {
        return "{\"category\":\"" + category + "\",\"fcstDate\":\"" + date + "\",\"fcstTime\":\"" + time
                + "\",\"fcstValue\":\"" + value + "\"}";
    }

    private static final String SHORT = wrap(String.join(",",
            // 25일: 12시 맑음 10도, 15시 비 8도
            item("20261125", "1200", "TMP", "10"), item("20261125", "1200", "SKY", "1"),
            item("20261125", "1200", "PTY", "0"), item("20261125", "1200", "POP", "20"),
            item("20261125", "1500", "TMP", "8"), item("20261125", "1500", "SKY", "4"),
            item("20261125", "1500", "PTY", "1"), item("20261125", "1500", "POP", "70"),
            item("20261125", "1500", "TMX", "11.0"),
            // 26일: 03시에만 눈(밤), 낮은 맑음
            item("20261126", "0300", "TMP", "-2"), item("20261126", "0300", "SKY", "4"),
            item("20261126", "0300", "PTY", "3"), item("20261126", "0300", "POP", "60"),
            item("20261126", "0600", "TMN", "-3.0"),
            item("20261126", "1200", "TMP", "5"), item("20261126", "1200", "SKY", "1"),
            item("20261126", "1200", "PTY", "0"), item("20261126", "1200", "POP", "0"),
            // 27일: 자정 한 시각뿐(실제 응답의 마지막 날) → 시간별에는 있고 일별 요약은 만들지 않는다
            item("20261127", "0000", "TMP", "1"), item("20261127", "0000", "SKY", "4"),
            item("20261127", "0000", "PTY", "0"), item("20261127", "0000", "POP", "30")));

    // 중기: 26일(1일 뒤)은 단기와 겹쳐 단기가 이긴다. 29일(4일 뒤) 오전 맑음·오후 비, 12월 3일(8일 뒤) 하루 값.
    private static final String LAND = wrap("{\"regId\":\"11B00000\",\"wf1Am\":\"흐림\",\"wf1Pm\":\"흐림\",\"rnSt1Am\":30,\"rnSt1Pm\":30,"
            + "\"wf4Am\":\"맑음\",\"wf4Pm\":\"흐리고 비\",\"rnSt4Am\":10,\"rnSt4Pm\":80,\"wf8\":\"구름많음\",\"rnSt8\":20}");
    private static final String TA = wrap("{\"regId\":\"11B10101\",\"taMin4\":1,\"taMax4\":9,\"taMin8\":-1,\"taMax8\":6}");

    private void expectMid() {
        server.expect(ExpectedCount.once(), requestTo(containsString("/MidFcstInfoService/getMidLandFcst")))
                .andExpect(queryParam("regId", "11B00000")).andExpect(queryParam("tmFc", "202611250600"))
                .andRespond(withSuccess(LAND, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(containsString("/MidFcstInfoService/getMidTa")))
                .andExpect(queryParam("regId", "11B10101"))
                .andRespond(withSuccess(TA, MediaType.APPLICATION_JSON));
    }

    @Test
    void gridMatchesKmaReferencePoints() {
        assertThat(KmaGrid.of(37.5665, 126.9780)).isEqualTo(new KmaGrid(60, 127)); // 서울
        assertThat(KmaGrid.of(35.1796, 129.0756)).isEqualTo(new KmaGrid(98, 76));  // 부산
        assertThat(KmaGrid.of(33.4996, 126.5312)).isEqualTo(new KmaGrid(53, 38));  // 제주
    }

    @Test
    void nearestMidRegion() {
        assertThat(MidRegion.nearest(37.5, 127.03).name()).isEqualTo("서울");
        assertThat(MidRegion.nearest(35.16, 129.16).name()).isEqualTo("부산");
        assertThat(MidRegion.nearest(33.25, 126.41).landRegId()).isEqualTo("11G00000");
    }

    @Test
    void baseTimes() {
        assertThat(WeatherService.shortBase(LocalDateTime.of(2026, 11, 25, 2, 5))).isEqualTo(LocalDateTime.of(2026, 11, 24, 23, 0));
        assertThat(WeatherService.shortBase(LocalDateTime.of(2026, 11, 25, 2, 10))).isEqualTo(LocalDateTime.of(2026, 11, 25, 2, 0));
        assertThat(WeatherService.shortBase(LocalDateTime.of(2026, 11, 25, 16, 59))).isEqualTo(LocalDateTime.of(2026, 11, 25, 14, 0));
        assertThat(WeatherService.midBase(LocalDateTime.of(2026, 11, 25, 6, 10))).isEqualTo(LocalDateTime.of(2026, 11, 24, 18, 0));
        assertThat(WeatherService.midBase(LocalDateTime.of(2026, 11, 25, 18, 30))).isEqualTo(LocalDateTime.of(2026, 11, 25, 18, 0));
    }

    @Test
    void mergesShortAndMidAndCachesUntilNextIssue() {
        server.expect(ExpectedCount.once(), requestTo(containsString("/VilageFcstInfoService_2.0/getVilageFcst")))
                .andExpect(queryParam("authKey", "key")).andExpect(queryParam("base_date", "20261125"))
                .andExpect(queryParam("base_time", "0800")).andExpect(queryParam("nx", "60")).andExpect(queryParam("ny", "127"))
                .andRespond(withSuccess(SHORT, MediaType.APPLICATION_JSON));
        expectMid();

        WeatherService.Forecast forecast = service.forecast(37.5665, 126.9780);
        service.forecast(37.5665, 126.9780); // 두 번째는 캐시 (once 로 검증)
        server.verify();

        assertThat(forecast.hourly()).hasSize(5);
        assertThat(forecast.hourly().get(1))
                .isEqualTo(new WeatherService.Hour(LocalDateTime.of(2026, 11, 25, 15, 0), "RAIN", 8, 70));
        assertThat(forecast.daily()).containsExactly(
                new WeatherService.Day(LocalDate.of(2026, 11, 25), "RAIN", 70, 8, 11),
                // 밤 눈은 낮 아이콘·강수확률에 반영하지 않는다. 중기의 '흐림'은 단기가 덮는다.
                new WeatherService.Day(LocalDate.of(2026, 11, 26), "CLEAR", 0, -3, 5),
                new WeatherService.Day(LocalDate.of(2026, 11, 29), "RAIN", 80, 1, 9),
                new WeatherService.Day(LocalDate.of(2026, 12, 3), "PARTLY", 20, -1, 6));
    }

    @Test
    void shortTermFailureStillReturnsMid() {
        server.expect(requestTo(containsString("getVilageFcst"))).andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN));
        expectMid();

        WeatherService.Forecast forecast = service.forecast(37.5665, 126.9780);

        assertThat(forecast.hourly()).isEmpty();
        assertThat(forecast.daily()).extracting(WeatherService.Day::date)
                .containsExactly(LocalDate.of(2026, 11, 26), LocalDate.of(2026, 11, 29), LocalDate.of(2026, 12, 3));
    }

    @Test
    void midFallsBackToPreviousIssueWhenLatestIsNotPublishedYet() {
        String noData = "{\"response\":{\"header\":{\"resultCode\":\"03\",\"resultMsg\":\"NO_DATA\"}}}";
        server.expect(requestTo(containsString("getVilageFcst"))).andRespond(withSuccess(SHORT, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("getMidLandFcst"))).andExpect(queryParam("tmFc", "202611250600"))
                .andRespond(withSuccess(noData, MediaType.APPLICATION_JSON));
        // 직전 발표(24일 18시)는 날짜를 그 발표일부터 센다: wf4 → 28일
        server.expect(requestTo(containsString("getMidLandFcst"))).andExpect(queryParam("tmFc", "202611241800"))
                .andRespond(withSuccess(LAND, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("getMidTa"))).andExpect(queryParam("tmFc", "202611241800"))
                .andRespond(withSuccess(TA, MediaType.APPLICATION_JSON));

        assertThat(service.forecast(37.5665, 126.9780).daily()).extracting(WeatherService.Day::date)
                .containsExactly(LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 26),
                        LocalDate.of(2026, 11, 28), LocalDate.of(2026, 12, 2));
        server.verify();
    }

    @Test
    void unavailableWhenEverythingFailsOrNoKey() {
        server.expect(ExpectedCount.manyTimes(), requestTo(containsString("kma.example")))
                .andRespond(withSuccess("{\"response\":{\"header\":{\"resultCode\":\"03\"}}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.forecast(37.5665, 126.9780)).isInstanceOf(WeatherUnavailableException.class);
        assertThatThrownBy(() -> service(" ").forecast(37.5665, 126.9780)).isInstanceOf(WeatherUnavailableException.class);
    }

    @Test
    void rejectsPlacesOutsideKorea() {
        assertThatThrownBy(() -> service.forecast(35.68, 139.69)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.forecast(Double.NaN, 127)).isInstanceOf(BadRequestException.class);
    }
}
