package com.getddo.core.mission.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.mission.domain.SurveyFreeTextAnswer;

/** 설문 응답 집계 조회. 읽기 전용이며 아무것도 잠그지 않는다. 완료된 제출만 센다. */
public interface SurveySummaryRepository {

	/**
	 * 기간 안에 완료된 설문 제출 수를 KST 날짜별로 센다. 제출이 없는 날은 담지 않는다.
	 *
	 * @param from 제출 시각 하한(포함)
	 * @param to   제출 시각 상한(제외)
	 */
	Map<LocalDate, Long> countCompletedSubmissionsByDay(UUID missionId, Instant from, Instant to);

	/**
	 * 기간 안에 완료된 제출에서 선택지를 고른 답변 수를 선택지별로 센다. 고르지 않은 선택지는 담지 않는다.
	 *
	 * @param from 제출 시각 하한(포함)
	 * @param to   제출 시각 상한(제외)
	 */
	Map<UUID, Long> countSelectionsByOption(UUID missionId, Instant from, Instant to);

	/**
	 * 기간 안에 완료된 제출이 한 자유 서술 문항에 남긴 답변을 제출 시각 최신순(같으면 답변 ID 내림차순)으로 조회한다. 답변이
	 * 없는 제출은 담지 않는다. 사용자를 알 수 있는 정보는 읽지 않는다.
	 *
	 * @param questionId 설문 문항 ID. 문항이 미션에 속하는지는 호출하는 서비스가 확인한다
	 * @param from       제출 시각 하한(포함)
	 * @param to         제출 시각 상한(제외)
	 */
	PageResult<SurveyFreeTextAnswer> findFreeTextAnswers(UUID questionId, Instant from, Instant to, PageQuery page);
}
