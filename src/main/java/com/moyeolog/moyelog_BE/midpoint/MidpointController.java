package com.moyeolog.moyelog_BE.midpoint;

import com.moyeolog.moyelog_BE.midpoint.MidpointDtos.ConfirmRequest;
import com.moyeolog.moyelog_BE.midpoint.MidpointDtos.DepartureRequest;
import com.moyeolog.moyelog_BE.midpoint.MidpointDtos.MidpointResponse;
import com.moyeolog.moyelog_BE.midpoint.MidpointDtos.RequestResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 일정의 중간 지점 찾기: 각자 출발지를 적으면 가운데 좌표를 돌려준다. 근처 지하철역 검색은 프론트(Kakao 지도 SDK)가 한다. */
@RestController
@RequestMapping("/api/schedules/{scheduleId}/midpoint")
@RequiredArgsConstructor
public class MidpointController {

    private final MidpointService midpointService;

    @GetMapping
    public ResponseEntity<MidpointResponse> get(@AuthenticationPrincipal String userId, @PathVariable UUID scheduleId) {
        return ResponseEntity.ok(midpointService.get(UUID.fromString(userId), scheduleId));
    }

    @PutMapping("/departure")
    public ResponseEntity<MidpointResponse> saveDeparture(@AuthenticationPrincipal String userId,
                                                          @PathVariable UUID scheduleId,
                                                          @Valid @RequestBody DepartureRequest request) {
        return ResponseEntity.ok(midpointService.saveDeparture(UUID.fromString(userId), scheduleId, request));
    }

    @DeleteMapping("/departure")
    public ResponseEntity<MidpointResponse> removeDeparture(@AuthenticationPrincipal String userId,
                                                            @PathVariable UUID scheduleId) {
        return ResponseEntity.ok(midpointService.removeDeparture(UUID.fromString(userId), scheduleId));
    }

    /** 아직 출발지를 적지 않은 사람에게 알림을 보낸다 (일정을 만든 사람만) */
    @PostMapping("/request")
    public ResponseEntity<RequestResult> request(@AuthenticationPrincipal String userId, @PathVariable UUID scheduleId) {
        return ResponseEntity.ok(midpointService.requestDepartures(UUID.fromString(userId), scheduleId));
    }

    /** 고른 장소를 일정 장소로 저장한다 (일정을 만든 사람만) */
    @PostMapping("/confirm")
    public ResponseEntity<MidpointResponse> confirm(@AuthenticationPrincipal String userId,
                                                    @PathVariable UUID scheduleId,
                                                    @Valid @RequestBody ConfirmRequest request) {
        return ResponseEntity.ok(midpointService.confirm(UUID.fromString(userId), scheduleId, request));
    }
}
