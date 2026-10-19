package com.getddo.core.mission.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

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
}
