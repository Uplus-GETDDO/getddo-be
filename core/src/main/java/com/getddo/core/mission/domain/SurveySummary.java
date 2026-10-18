package com.getddo.core.mission.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import lombok.Getter;

/**
 * 설문 미션 응답 집계. 완료된 제출만 센다.
 *
 * <p>날짜는 KST 업무일이다. 응답 없는 날도 0건으로 담고, 응답 없는 선택지도 0건으로 담는다. 자유 서술 문항은 담지 않는다.</p>
 */
@Getter
public final class SurveySummary {

	private final List<DailyCount> dailyResponses;
	private final List<QuestionSummary> questions;

	public SurveySummary(List<DailyCount> dailyResponses, List<QuestionSummary> questions) {
		this.dailyResponses = List.copyOf(Objects.requireNonNull(dailyResponses, "dailyResponses"));
		this.questions = List.copyOf(Objects.requireNonNull(questions, "questions"));
	}

	/** 한 KST 날짜에 완료된 설문 제출 수. */
	public record DailyCount(LocalDate date, long count) {
	}

	/** 선택형 설문 문항과 선택지별 응답 수. 선택지는 표시 순서 오름차순이다. */
	public record QuestionSummary(UUID questionId, String questionText, List<OptionCount> options) {

		public QuestionSummary {
			Objects.requireNonNull(questionId, "questionId");
			options = List.copyOf(Objects.requireNonNull(options, "options"));
		}
	}

	/** 선택지와 그것을 고른 응답 수. */
	public record OptionCount(UUID optionId, String optionText, long count) {
	}
}
