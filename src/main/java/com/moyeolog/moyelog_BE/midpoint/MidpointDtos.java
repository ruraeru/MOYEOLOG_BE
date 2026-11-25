package com.moyeolog.moyelog_BE.midpoint;

import com.moyeolog.moyelog_BE.validation.CodePointSize;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** 중간 지점 API 의 요청·응답 모양 */
public final class MidpointDtos {

    private MidpointDtos() {
    }

    public record DepartureRequest(
            @NotBlank(message = "출발지를 골라 주세요.")
            @CodePointSize(max = 100, message = "출발지 이름은 100자까지 쓸 수 있어요.")
            String name,
            @NotNull(message = "출발지를 지도에서 골라 주세요.")
            @DecimalMin(value = "-90", message = "위도가 올바르지 않아요.")
            @DecimalMax(value = "90", message = "위도가 올바르지 않아요.")
            Double latitude,
            @NotNull(message = "출발지를 지도에서 골라 주세요.")
            @DecimalMin(value = "-180", message = "경도가 올바르지 않아요.")
            @DecimalMax(value = "180", message = "경도가 올바르지 않아요.")
            Double longitude) {
    }

    public record ConfirmRequest(
            @NotBlank(message = "장소를 골라 주세요.")
            @CodePointSize(max = 255, message = "장소는 255자까지 쓸 수 있어요.")
            String name,
            // 고른 장소의 좌표(날씨 조회용). 둘 다 있을 때만 저장한다.
            @DecimalMin(value = "-90", message = "위도가 올바르지 않아요.")
            @DecimalMax(value = "90", message = "위도가 올바르지 않아요.")
            Double latitude,
            @DecimalMin(value = "-180", message = "경도가 올바르지 않아요.")
            @DecimalMax(value = "180", message = "경도가 올바르지 않아요.")
            Double longitude) {
    }

    /** 다른 사람의 출발지는 이름만 준다 (좌표 없음) */
    public record Entry(UUID userId, String nickname, String profileImage, String name, boolean mine) {
    }

    public record Place(String name, double latitude, double longitude) {
    }

    public record Center(double latitude, double longitude) {
    }

    /**
     * @param departures     출발지를 적은 사람들 (이름만)
     * @param myDeparture    내가 이 일정에 적은 출발지 (좌표 포함), 없으면 null
     * @param lastDeparture  내가 다른 일정에 마지막으로 적은 출발지, 없으면 null
     * @param center         출발지들의 가운데 좌표. 두 명 이상 적었을 때만
     * @param expectedCount  출발지를 적을 수 있는 사람 수 (모임 일정은 모임 멤버, 개인 일정은 만든 사람 + 참여자)
     * @param canConfirm     내가 장소를 확정할 수 있는지 (일정을 만든 사람)
     * @param location       지금 일정에 적힌 장소
     */
    public record MidpointResponse(List<Entry> departures, Place myDeparture, Place lastDeparture, Center center,
                                   int enteredCount, int expectedCount, boolean canConfirm, String location) {
    }

    public record RequestResult(int sent) {
    }
}
