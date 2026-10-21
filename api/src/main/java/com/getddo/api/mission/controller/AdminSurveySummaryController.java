package com.getddo.api.mission.controller;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.api.mission.dto.response.SurveyAnswerResponse;
import com.getddo.api.mission.dto.response.SurveySummaryResponse;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.mission.service.SurveySummaryService;
import com.getddo.core.user.domain.User;

/** R5 관리자 설문 응답 집계 API와 자유 서술 답변 조회 API다. 읽기 전용이다. */
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

	/**
	 * 설문 미션의 자유 서술 문항에 남은 답변을 제출 시각 최신순으로 조회한다. 익명이라 답변 원문과 제출 시각만 돌려준다.
	 *
	 * @param user 공통 MVC에서 확인한 요청 사용자. 활성 관리자여야 한다
	 * @param missionId 설문 미션. 퀴즈 미션이면 400 {@code MISSION-001}, 없으면 404 {@code MISSION-003}
	 * @param questionId 이 미션의 자유 서술 문항. 아니면 400 {@code MISSION-001}
	 * @param from KST 시작 날짜(포함)
	 * @param to KST 끝 날짜(포함). 집계(R5)와 같은 기간 규칙이다
	 * @param page 1부터 시작하는 페이지 번호
	 * @param size 페이지 크기(1~100)
	 */
	@GetMapping("/{missionId}/survey-answers")
	public ResponseEnvelope<PageResult<SurveyAnswerResponse>> surveyAnswers(
			@CurrentUser(membershipRequired = false) User user,
			@PathVariable UUID missionId,
			@RequestParam UUID questionId,
			@Parameter(description = "KST 시작 날짜(포함). YYYY-MM-DD", example = "2026-10-11")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@Parameter(description = "KST 끝 날짜(포함). YYYY-MM-DD. 시작 날짜와 같거나 이후, 양끝 포함 최대 31일", example = "2026-10-17")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(defaultValue = "1") @Min(1) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ResponseEnvelope.success(service
				.findFreeTextAnswers(user, missionId, questionId, from, to, new PageQuery(page, size))
				.map(SurveyAnswerResponse::from));
	}
}
