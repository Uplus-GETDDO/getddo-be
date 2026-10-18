package com.getddo.api.attendance.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.getddo.api.attendance.dto.request.DailyAttendancePolicyRequest;
import com.getddo.api.attendance.dto.request.StreakPolicyRequest;
import com.getddo.api.attendance.dto.response.DailyAttendancePolicyResponse;
import com.getddo.api.attendance.dto.response.StreakPolicySetResponse;
import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.core.attendance.service.AdminAttendancePolicyService;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.user.domain.User;

/**
 * AP01~AP04 관리자 출석 정책 API다. 일일 정책은 다음 KST 자정, 연속 출석 정책은 다음 KST 기준월 1일부터 적용하며
 * 적용 시점은 입력받지 않는다.
 */
@RestController
@RequestMapping("/api/v1/admin/policies/attendance")
@RequiredArgsConstructor
public class AdminAttendancePolicyController {

	private final AdminAttendancePolicyService service;

	/**
	 * AP01 일일 출석 정책을 적용 시작 시각 최신순으로 조회한다.
	 *
	 * @param user 공통 MVC에서 확인한 요청 사용자. 활성 관리자여야 한다
	 */
	@GetMapping("/daily")
	public ResponseEnvelope<PageResult<DailyAttendancePolicyResponse>> dailyPolicies(
			@CurrentUser(membershipRequired = false) User user,
			@RequestParam(defaultValue = "1") @Min(1) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ResponseEnvelope.success(service.findDailyPolicies(user, new PageQuery(page, size))
				.map(DailyAttendancePolicyResponse::from));
	}

	/**
	 * AP02 다음 KST 자정부터 적용할 일일 출석 정책을 등록한다. 같은 적용일의 예약 정책이 있으면 409다.
	 *
	 * @param user 공통 MVC에서 확인한 요청 사용자. 활성 관리자여야 한다
	 */
	@PostMapping("/daily")
	public ResponseEntity<ResponseEnvelope<DailyAttendancePolicyResponse>> createDailyPolicy(
			@CurrentUser(membershipRequired = false) User user,
			@Valid @RequestBody DailyAttendancePolicyRequest request) {
		DailyAttendancePolicyResponse response = DailyAttendancePolicyResponse
				.from(service.createDailyPolicy(user, request.rewardTicketCount()));
		return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(response));
	}

	/**
	 * AP03 연속 출석 정책 묶음을 적용월 최신순으로 조회한다.
	 *
	 * @param user 공통 MVC에서 확인한 요청 사용자. 활성 관리자여야 한다
	 */
	@GetMapping("/streak")
	public ResponseEnvelope<PageResult<StreakPolicySetResponse>> streakPolicies(
			@CurrentUser(membershipRequired = false) User user,
			@RequestParam(defaultValue = "1") @Min(1) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ResponseEnvelope.success(service.findStreakPolicies(user, new PageQuery(page, size))
				.map(StreakPolicySetResponse::from));
	}

	/**
	 * AP04 다음 KST 기준월 1일부터 적용할 연속 출석 정책 묶음을 등록한다. 같은 적용월의 예약 정책이 있으면 409다.
	 *
	 * @param user 공통 MVC에서 확인한 요청 사용자. 활성 관리자여야 한다
	 */
	@PostMapping("/streak")
	public ResponseEntity<ResponseEnvelope<StreakPolicySetResponse>> createStreakPolicy(
			@CurrentUser(membershipRequired = false) User user,
			@Valid @RequestBody StreakPolicyRequest request) {
		StreakPolicySetResponse response = StreakPolicySetResponse
				.from(service.createStreakPolicy(user, request.toDrafts()));
		return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(response));
	}
}
