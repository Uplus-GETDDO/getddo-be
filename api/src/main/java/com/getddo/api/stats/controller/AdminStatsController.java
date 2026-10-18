package com.getddo.api.stats.controller;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.api.stats.dto.response.DailyStatsResponse;
import com.getddo.core.stats.service.DailyStatsService;
import com.getddo.core.user.domain.User;

/** R1 관리자 일별 집계 API다. 읽기 전용이다. */
@RestController
@RequestMapping("/api/v1/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {

	private final DailyStatsService service;

	/**
	 * 날짜별 응모자 수·응모 건수·참여 사용자 수·지급 응모권·사용 응모권·어뷰징 탐지 건수와 활성 일반 사용자 수를 한 번에 돌려준다.
	 *
	 * @param user 공통 MVC에서 확인한 요청 사용자. 활성 관리자여야 하며 아니면 403 {@code STATS-002}
	 * @param from KST 시작 날짜(포함)
	 * @param to KST 끝 날짜(포함). 시작 날짜와 같거나 이후이고 두 날짜 사이가 최대 31일이어야 한다. 기간 규칙은 서비스가 검사해 어기면 400 {@code STATS-001}이다
	 */
	@GetMapping("/daily")
	public ResponseEnvelope<DailyStatsResponse> daily(
			@CurrentUser(membershipRequired = false) User user,
			@Parameter(description = "KST 시작 날짜(포함). YYYY-MM-DD", example = "2026-10-11")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@Parameter(description = "KST 끝 날짜(포함). YYYY-MM-DD. 시작 날짜 이후, 최대 31일", example = "2026-10-17")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return ResponseEnvelope.success(DailyStatsResponse.from(service.daily(user, from, to)));
	}
}
