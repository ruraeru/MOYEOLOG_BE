package com.moyeolog.moyelog_BE.weather;

/** 위경도 → 기상청 단기예보 격자(nx, ny). 기상청이 공개한 Lambert 정각원추 변환식이다. */
public record KmaGrid(int nx, int ny) {

    private static final double RE = 6371.00877 / 5.0; // 지구 반경(km) / 격자 간격(km)
    private static final double SLAT1 = Math.toRadians(30.0);
    private static final double SLAT2 = Math.toRadians(60.0);
    private static final double OLON = Math.toRadians(126.0);
    private static final double OLAT = Math.toRadians(38.0);
    private static final double XO = 43;
    private static final double YO = 136;

    public static KmaGrid of(double lat, double lng) {
        double sn = Math.log(Math.cos(SLAT1) / Math.cos(SLAT2))
                / Math.log(Math.tan(Math.PI * 0.25 + SLAT2 * 0.5) / Math.tan(Math.PI * 0.25 + SLAT1 * 0.5));
        double sf = Math.pow(Math.tan(Math.PI * 0.25 + SLAT1 * 0.5), sn) * Math.cos(SLAT1) / sn;
        double ro = RE * sf / Math.pow(Math.tan(Math.PI * 0.25 + OLAT * 0.5), sn);
        double ra = RE * sf / Math.pow(Math.tan(Math.PI * 0.25 + Math.toRadians(lat) * 0.5), sn);
        double theta = Math.toRadians(lng) - OLON;
        if (theta > Math.PI) theta -= 2.0 * Math.PI;
        if (theta < -Math.PI) theta += 2.0 * Math.PI;
        theta *= sn;
        return new KmaGrid(
                (int) Math.floor(ra * Math.sin(theta) + XO + 0.5),
                (int) Math.floor(ro - ra * Math.cos(theta) + YO + 0.5));
    }
}
