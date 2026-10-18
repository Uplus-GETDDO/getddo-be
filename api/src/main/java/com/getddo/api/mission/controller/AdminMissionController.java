package com.getddo.api.mission.controller;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.exception.CommonErrorCode;
import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.api.mission.dto.request.MissionWriteRequest;
import com.getddo.api.mission.dto.response.AdminMissionResponse;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.mission.domain.MissionSearch;
import com.getddo.core.mission.domain.MissionStatus;
import com.getddo.core.mission.domain.MissionType;
import com.getddo.core.mission.service.AdminMissionService;
import com.getddo.core.user.domain.User;

/**
 * AM01~AM03 관리자 미션 API다. 등록한 미션은 바로 운영 상태이며 운영 중인 미션의 문항·조건·보상은 수정하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/admin/missions")
@RequiredArgsConstructor
public class AdminMissionController {

	private final AdminMissionService service;

	/**
	 * AM01 미션 목록을 운영 시작 시각 최신순으로 조회한다. 문항은 담지 않는다.
	 *
	 * @param user 공통 MVC에서 확인한 요청 사용자. 활성 관리자여야 한다
	 * @param missionType 미션 유형 필터. 생략하면 전체
	 * @param status 저장된 운영 상태 필터. 생략하면 전체
	 * @param keyword 제목 부분 일치(대소문자 무시)
	 * @param from 운영 시작 시각이 이 시각 이상(포함)인 미션만
	 * @param to 운영 시작 시각이 이 시각 미만(제외)인 미션만
	 */
	@GetMapping
	public ResponseEnvelope<PageResult<AdminMissionResponse>> missions(
			@CurrentUser(membershipRequired = false) User user,
			@RequestParam(defaultValue = "1") @Min(1) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
			@RequestParam(required = false) MissionType missionType,
			@RequestParam(required = false) MissionStatus status,
			@RequestParam(required = false) String keyword,
			@Parameter(description = "운영 시작 시각 하한(포함). 시간대가 필요하다. UTC는 Z로 쓰고, 오프셋의 +는 %2B로 인코딩한다.",
					example = "2026-10-01T00:00:00Z")
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
			@Parameter(description = "운영 시작 시각 상한(제외). 시간대가 필요하다.", example = "2026-11-01T00:00:00Z")
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
		if (from != null && to != null && !from.toInstant().isBefore(to.toInstant())) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT);
		}
		MissionSearch search = new MissionSearch(missionType, status, keyword,
				from == null ? null : from.toInstant(), to == null ? null : to.toInstant());
		return ResponseEnvelope.success(service.findMissions(user, search, new PageQuery(page, size))
				.map(AdminMissionResponse::summaryOf));
	}

	/**
	 * AM02 미션 한 건을 정답을 포함한 문항·선택지와 함께 조회한다. 없는 미션은 404 {@code MISSION-003}이다.
	 *
	 * @param user 공통 MVC에서 확인한 요청 사용자. 활성 관리자여야 한다
	 */
	@GetMapping("/{missionId}")
	public ResponseEnvelope<AdminMissionResponse> mission(@CurrentUser(membershipRequired = false) User user,
			@PathVariable UUID missionId) {
		return ResponseEnvelope.success(AdminMissionResponse.from(service.findMission(user, missionId)));
	}

	/**
	 * AM03 미션을 문항·선택지·보상 정책과 함께 등록한다.
	 *
	 * @param user 공통 MVC에서 확인한 요청 사용자. 활성 관리자여야 한다
	 */
	@PostMapping
	public ResponseEntity<ResponseEnvelope<AdminMissionResponse>> register(
			@CurrentUser(membershipRequired = false) User user, @Valid @RequestBody MissionWriteRequest request) {
		AdminMissionResponse response = AdminMissionResponse
				.from(service.register(user, request.toRegistration()));
		return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(response));
	}
}
