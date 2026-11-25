package com.moyeolog.moyelog_BE.weather;

import java.util.Comparator;
import java.util.List;

/**
 * 중기예보 구역. 기온은 도시(taRegId), 날씨는 광역(landRegId) 단위로 나온다.
 * shortcut: 가장 가까운 대표 도시의 예보를 쓴다(울릉도·서해5도는 가까운 육지 값), 정확한 구역이 필요하면 행정구역 매핑으로 바꾼다.
 */
public record MidRegion(String name, double lat, double lng, String taRegId, String landRegId) {

    private static final List<MidRegion> ALL = List.of(
            new MidRegion("서울", 37.57, 126.98, "11B10101", "11B00000"),
            new MidRegion("인천", 37.46, 126.71, "11B20201", "11B00000"),
            new MidRegion("수원", 37.26, 127.03, "11B20601", "11B00000"),
            new MidRegion("파주", 37.76, 126.78, "11B20305", "11B00000"),
            new MidRegion("춘천", 37.88, 127.73, "11D10301", "11D10000"),
            new MidRegion("원주", 37.34, 127.92, "11D10401", "11D10000"),
            new MidRegion("강릉", 37.75, 128.88, "11D20501", "11D20000"),
            new MidRegion("속초", 38.21, 128.59, "11D20401", "11D20000"),
            new MidRegion("동해", 37.52, 129.11, "11D20601", "11D20000"),
            new MidRegion("대전", 36.35, 127.38, "11C20401", "11C20000"),
            new MidRegion("세종", 36.48, 127.29, "11C20404", "11C20000"),
            new MidRegion("서산", 36.78, 126.45, "11C20101", "11C20000"),
            new MidRegion("청주", 36.64, 127.49, "11C10301", "11C10000"),
            new MidRegion("충주", 36.99, 127.93, "11C10101", "11C10000"),
            new MidRegion("광주", 35.16, 126.85, "11F20501", "11F20000"),
            new MidRegion("목포", 34.81, 126.39, "21F20801", "11F20000"),
            new MidRegion("여수", 34.76, 127.66, "11F20401", "11F20000"),
            new MidRegion("전주", 35.82, 127.15, "11F10201", "11F10000"),
            new MidRegion("군산", 35.97, 126.74, "21F10501", "11F10000"),
            new MidRegion("대구", 35.87, 128.60, "11H10701", "11H10000"),
            new MidRegion("안동", 36.57, 128.73, "11H10501", "11H10000"),
            new MidRegion("포항", 36.02, 129.34, "11H10201", "11H10000"),
            new MidRegion("부산", 35.18, 129.08, "11H20201", "11H20000"),
            new MidRegion("울산", 35.54, 129.31, "11H20101", "11H20000"),
            new MidRegion("창원", 35.23, 128.68, "11H20301", "11H20000"),
            new MidRegion("진주", 35.18, 128.11, "11H20701", "11H20000"),
            new MidRegion("제주", 33.50, 126.53, "11G00201", "11G00000"),
            new MidRegion("서귀포", 33.25, 126.56, "11G00401", "11G00000"));

    public static MidRegion nearest(double lat, double lng) {
        double cos = Math.cos(Math.toRadians(lat)); // 경도 1도는 위도 1도보다 짧다
        return ALL.stream()
                .min(Comparator.comparingDouble(r -> Math.pow(r.lat - lat, 2) + Math.pow((r.lng - lng) * cos, 2)))
                .orElseThrow();
    }
}
