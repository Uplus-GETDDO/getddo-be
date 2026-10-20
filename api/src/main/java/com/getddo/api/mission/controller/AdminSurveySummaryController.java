package com.getddo.api.mission.controller;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.api.mission.dto.response.SurveySummaryResponse;
import com.getddo.core.mission.service.SurveySummaryService;
import com.getddo.core.user.domain.User;

/** R5 관리자 설문 응답 집계 API다. 읽기 전용이다. */
@RestController
@RequestMapping("/api/v1/admin/missions")
@RequiredArgsConstructor
public class AdminSurveySummaryController {

	private final SurveySummaryService service;

	/**
	 * 설문 미션의 날짜별 완료 제출 수와 선택형 문항의 선택지별 응답 수를 집계한다.
	 *
	 * @param user 공통 MVC에서 확인한 요청 사용자. 활성 관리자여야 한다
	 * @param missionId 설문 미션. 퀴즈 미션이면 400 {@code MISSION-001}, 없으면 404 {@code MISSION-003}
	 * @param from KST 시작 날짜(포함)
	 * @param to KST 끝 날짜(포함). 시작 날짜와 같거나 이후이고 시작·끝 날짜를 모두 포함해 최대 31일이어야 한다. 기간 규칙은 서비스가 검사해 어기면 400 {@code MISSION-001}이다
	 */
	@GetMapping("/{missionId}/survey-summary")
	public ResponseEnvelope<SurveySummaryResponse> surveySummary(
			@CurrentUser(membershipRequired = false) User user,
			@PathVariable UUID missionId,
			@Parameter(description = "KST 시작 날짜(포함). YYYY-MM-DD", example = "2026-10-11")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@Parameter(description = "KST 끝 날짜(포함). YYYY-MM-DD. 시작 날짜와 같거나 이후, 양끝 포함 최대 31일", example = "2026-10-17")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return ResponseEnvelope.success(SurveySummaryResponse.from(service.summarize(user, missionId, from, to)));
	}
}
