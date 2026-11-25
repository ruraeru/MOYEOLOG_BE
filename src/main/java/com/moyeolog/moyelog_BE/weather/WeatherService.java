package com.moyeolog.moyelog_BE.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyeolog.moyelog_BE.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 기상청 API허브의 단기예보(격자, 시간별 3~4일)와 중기예보(지역, 일별 10일까지)를 합쳐 준다.
 * 예보는 발표 시각마다만 바뀌므로 격자·지역별로 다음 발표까지 메모리에 둔다.
 */
@Service
@Slf4j
public class WeatherService {

    public record Hour(LocalDateTime time, String sky, Integer temp, Integer pop) {}

    public record Day(LocalDate date, String sky, Integer pop, Integer min, Integer max) {}

    public record Forecast(List<Hour> hourly, List<Day> daily) {}

    private record ShortTerm(List<Hour> hourly, List<Day> daily) {}

    private record Cached<T>(String issued, T value) {}

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmm");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    /** 나쁜 날씨일수록 뒤. 하루를 아이콘 하나로 줄일 때 뒤쪽이 이긴다. */
    private static final List<String> SEVERITY = List.of("CLEAR", "PARTLY", "CLOUDY", "RAIN", "SLEET", "SNOW");
    private static final int CACHE_MAX = 5000;

    /** 비어 있으면 날씨 요청은 503. */
    @Value("${kma.service-key:}")
    private String serviceKey;

    @Value("${kma.base-url:https://apihub.kma.go.kr/api/typ02/openApi}")
    private String baseUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, Cached<ShortTerm>> shortCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<List<Day>>> midCache = new ConcurrentHashMap<>();

    @Autowired
    public WeatherService(ObjectMapper objectMapper) {
        // 공용 RestTemplate 은 AI 분석용이라 150초를 기다린다. 날씨는 화면을 붙잡지 않게 짧게 끊는다.
        this(shortTimeoutRestTemplate(), objectMapper, Clock.system(KST));
    }

    WeatherService(RestTemplate restTemplate, ObjectMapper objectMapper, Clock clock) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private static RestTemplate shortTimeoutRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(8));
        return new RestTemplate(factory);
    }

    public Forecast forecast(double lat, double lng) {
        // NaN 도 여기서 걸린다
        if (!(lat >= 32 && lat <= 39.5 && lng >= 123 && lng <= 132.5)) {
            throw new BadRequestException("날씨는 국내 장소만 볼 수 있어요.");
        }
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new WeatherUnavailableException();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        ShortTerm shortTerm = shortTerm(KmaGrid.of(lat, lng), now);
        List<Day> mid = midTerm(MidRegion.nearest(lat, lng), now);
        if (shortTerm == null && mid == null) {
            throw new WeatherUnavailableException();
        }
        // 같은 날짜는 단기예보(격자 단위, 더 정확)가 중기예보를 덮는다
        TreeMap<LocalDate, Day> days = new TreeMap<>();
        if (mid != null) mid.forEach(d -> days.put(d.date(), d));
        if (shortTerm != null) shortTerm.daily().forEach(d -> days.put(d.date(), d));
        days.headMap(now.toLocalDate()).clear();
        return new Forecast(shortTerm == null ? List.of() : shortTerm.hourly(), List.copyOf(days.values()));
    }

    // ---- 단기예보 ----

    /** 02·05·…·23시에 발표하고 10분쯤 뒤부터 받을 수 있다. */
    static LocalDateTime shortBase(LocalDateTime now) {
        LocalDateTime t = now.minusMinutes(10);
        int h = t.getHour();
        return h < 2 ? t.toLocalDate().minusDays(1).atTime(23, 0) : t.toLocalDate().atTime(h - (h - 2) % 3, 0);
    }

    private ShortTerm shortTerm(KmaGrid grid, LocalDateTime now) {
        LocalDateTime base = shortBase(now);
        String key = grid.nx() + "," + grid.ny();
        Cached<ShortTerm> cached = shortCache.get(key);
        if (cached != null && cached.issued().equals(base.format(STAMP))) {
            return cached.value();
        }
        try {
            ShortTerm value = parseShort(fetch("/VilageFcstInfoService_2.0/getVilageFcst", Map.of(
                    "numOfRows", "1500",
                    "base_date", base.format(DATE),
                    "base_time", base.format(TIME),
                    "nx", String.valueOf(grid.nx()),
                    "ny", String.valueOf(grid.ny()))));
            put(shortCache, key, new Cached<>(base.format(STAMP), value));
            return value;
        } catch (Exception e) {
            log.warn("[weather] 단기예보 실패 ({}): {}", key, describe(e));
            return null;
        }
    }

    static ShortTerm parseShort(JsonNode items) {
        // 시각별로 항목(TMP 기온, POP 강수확률, SKY 하늘, PTY 강수형태, TMN·TMX 최저·최고)을 모은다
        TreeMap<LocalDateTime, Map<String, String>> byTime = new TreeMap<>();
        for (JsonNode item : items) {
            LocalDateTime time = LocalDateTime.parse(
                    item.path("fcstDate").asText() + item.path("fcstTime").asText(), STAMP);
            byTime.computeIfAbsent(time, k -> new HashMap<>())
                    .put(item.path("category").asText(), item.path("fcstValue").asText());
        }
        List<Hour> hours = new ArrayList<>();
        TreeMap<LocalDate, List<Hour>> byDate = new TreeMap<>();
        Map<LocalDate, Integer> tmn = new HashMap<>();
        Map<LocalDate, Integer> tmx = new HashMap<>();
        byTime.forEach((time, v) -> {
            if (number(v.get("TMN")) != null) tmn.put(time.toLocalDate(), number(v.get("TMN")));
            if (number(v.get("TMX")) != null) tmx.put(time.toLocalDate(), number(v.get("TMX")));
            if (number(v.get("TMP")) == null) return;
            Hour hour = new Hour(time, hourSky(v.get("PTY"), v.get("SKY")), number(v.get("TMP")), number(v.get("POP")));
            hours.add(hour);
            byDate.computeIfAbsent(time.toLocalDate(), k -> new ArrayList<>()).add(hour);
        });
        List<Day> days = new ArrayList<>();
        byDate.forEach((date, list) -> {
            // 새벽 소나기 한 번으로 하루를 '비'로 만들지 않게 낮 시간(06~21시)만 본다. 남은 시간이 밤뿐이면 전부 본다.
            List<Hour> daytime = list.stream().filter(h -> h.time().getHour() >= 6 && h.time().getHour() <= 21).toList();
            // 예보의 마지막 날은 자정 한 시각만 온다. 그걸로 하루를 요약하지 않고 중기예보에 맡긴다(오늘은 밤만 남아도 보여 준다).
            if (daytime.isEmpty() && !date.equals(byDate.firstKey())) return;
            // 아이콘과 강수확률은 같은 시간대에서 뽑는다(해 아이콘 옆에 새벽의 60% 가 붙지 않게)
            List<Hour> shown = daytime.isEmpty() ? list : daytime;
            days.add(new Day(date,
                    daySky(shown),
                    shown.stream().map(Hour::pop).filter(Objects::nonNull).max(Integer::compare).orElse(null),
                    tmn.getOrDefault(date, list.stream().map(Hour::temp).min(Integer::compare).orElse(null)),
                    tmx.getOrDefault(date, list.stream().map(Hour::temp).max(Integer::compare).orElse(null))));
        });
        return new ShortTerm(List.copyOf(hours), List.copyOf(days));
    }

    private static String hourSky(String pty, String sky) {
        if ("1".equals(pty) || "4".equals(pty)) return "RAIN";
        if ("2".equals(pty)) return "SLEET";
        if ("3".equals(pty)) return "SNOW";
        if ("1".equals(sky)) return "CLEAR";
        return "3".equals(sky) ? "PARTLY" : "CLOUDY";
    }

    /** 비·눈이 한 시간이라도 있으면 그것, 없으면 구름 양의 평균. */
    private static String daySky(List<Hour> hours) {
        String worst = hours.stream().map(Hour::sky).reduce(WeatherService::worse).orElse("CLOUDY");
        if (SEVERITY.indexOf(worst) >= SEVERITY.indexOf("RAIN")) return worst;
        double cloud = hours.stream().mapToInt(h -> SEVERITY.indexOf(h.sky())).average().orElse(2);
        return cloud < 0.5 ? "CLEAR" : cloud < 1.5 ? "PARTLY" : "CLOUDY";
    }

    // ---- 중기예보 ----

    /** 06·18시에 발표한다. */
    static LocalDateTime midBase(LocalDateTime now) {
        LocalDateTime t = now.minusMinutes(30);
        if (t.getHour() < 6) return t.toLocalDate().minusDays(1).atTime(18, 0);
        return t.toLocalDate().atTime(t.getHour() < 18 ? 6 : 18, 0);
    }

    private List<Day> midTerm(MidRegion region, LocalDateTime now) {
        LocalDateTime base = midBase(now);
        Cached<List<Day>> cached = midCache.get(region.taRegId());
        if (cached != null && cached.issued().equals(base.format(STAMP))) {
            return cached.value();
        }
        // 새 발표가 아직 안 올라왔으면(NO_DATA) 직전 발표를 쓴다(기상청은 하루치만 보관한다).
        // 직전 발표로 답한 결과는 캐시하지 않아, 다음 요청에서 새 발표를 다시 찾는다.
        for (LocalDateTime issued : List.of(base, base.minusHours(12))) {
            try {
                Map<String, String> when = Map.of("numOfRows", "10", "tmFc", issued.format(STAMP));
                JsonNode land = fetch("/MidFcstInfoService/getMidLandFcst", with(when, "regId", region.landRegId())).path(0);
                JsonNode ta = fetch("/MidFcstInfoService/getMidTa", with(when, "regId", region.taRegId())).path(0);
                List<Day> value = parseMid(land, ta, issued.toLocalDate());
                if (issued.equals(base)) {
                    put(midCache, region.taRegId(), new Cached<>(base.format(STAMP), value));
                }
                return value;
            } catch (Exception e) {
                log.warn("[weather] 중기예보 실패 ({}, {} 발표): {}", region.name(), issued.format(STAMP), describe(e));
            }
        }
        return null;
    }

    /** n일 뒤 값이 wf{n}Am·wf{n}Pm(오전·오후) 또는 wf{n}(하루) 필드로 온다. */
    static List<Day> parseMid(JsonNode land, JsonNode ta, LocalDate issued) {
        List<Day> days = new ArrayList<>();
        for (int n = 1; n <= 10; n++) {
            int day = n;
            String sky = Stream.of("Am", "Pm", "")
                    .map(half -> midSky(land.path("wf" + day + half).asText(null)))
                    .filter(Objects::nonNull).reduce(WeatherService::worse).orElse(null);
            if (sky == null) continue;
            Integer pop = Stream.of("Am", "Pm", "")
                    .map(half -> number(land.path("rnSt" + day + half).asText(null)))
                    .filter(Objects::nonNull).max(Integer::compare).orElse(null);
            days.add(new Day(issued.plusDays(n), sky, pop,
                    number(ta.path("taMin" + n).asText(null)), number(ta.path("taMax" + n).asText(null))));
        }
        return List.copyOf(days);
    }

    /** "맑음", "구름많음", "구름많고 비", "흐리고 비/눈", "흐리고 소나기" … */
    private static String midSky(String wf) {
        if (wf == null || wf.isBlank()) return null;
        if (wf.contains("비/눈")) return "SLEET";
        if (wf.contains("눈")) return "SNOW";
        if (wf.contains("비") || wf.contains("소나기")) return "RAIN";
        if (wf.contains("흐림") || wf.contains("흐리")) return "CLOUDY";
        return wf.contains("구름") ? "PARTLY" : "CLEAR";
    }

    // ---- 공통 ----

    private static String worse(String a, String b) {
        return SEVERITY.indexOf(a) >= SEVERITY.indexOf(b) ? a : b;
    }

    /** 기상청은 값이 없을 때 빈 문자열이나 -999 를 준다. */
    private static Integer number(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            double d = Double.parseDouble(value.trim());
            return d < -900 || d > 900 ? null : (int) Math.round(d);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, String> with(Map<String, String> base, String key, String value) {
        Map<String, String> map = new HashMap<>(base);
        map.put(key, value);
        return map;
    }

    private static <T> void put(Map<String, Cached<T>> cache, String key, Cached<T> value) {
        if (cache.size() >= CACHE_MAX) cache.clear();
        cache.put(key, value);
    }

    /** response.body.items.item 배열을 돌려준다. 결과 코드가 정상(00)이 아니면 예외. */
    private JsonNode fetch(String path, Map<String, String> params) throws Exception {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(baseUrl.replaceAll("/+$", "") + path)
                .queryParam("pageNo", "1")
                .queryParam("dataType", "JSON")
                .queryParam("authKey", serviceKey.trim());
        params.forEach(uri::queryParam);
        byte[] body = restTemplate.getForObject(uri.build().encode().toUri(), byte[].class);
        JsonNode response = objectMapper.readTree(body).path("response");
        String code = response.path("header").path("resultCode").asText();
        JsonNode items = response.path("body").path("items").path("item");
        if (!"00".equals(code) || !items.isArray() || items.isEmpty()) {
            throw new IllegalStateException("resultCode=" + code);
        }
        return items;
    }

    /** 요청 주소에는 인증키가 들어 있으므로 예외 메시지를 그대로 남기지 않는다. */
    private static String describe(Exception e) {
        if (e instanceof RestClientResponseException http) {
            return "HTTP " + http.getStatusCode().value();
        }
        return e instanceof IllegalStateException ? e.getMessage() : e.getClass().getSimpleName();
    }
}
