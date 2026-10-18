package com.getddo.api.mission.dto.response;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.getddo.core.mission.domain.SurveySummary;

/**
 * 설문 응답 집계(R5). 날짜는 KST 업무일이며 응답 없는 날도 0건으로 담는다. 선택형 문항만 담고 자유 서술은 제외한다.
 */
public record SurveySummaryResponse(List<DailyResponse> dailyResponses, List<Question> questions) {

	public record DailyResponse(LocalDate date, long count) {
	}

	public record Question(UUID questionId, String questionText, List<Option> options) {
	}

	public record Option(UUID optionId, String optionText, long count) {
	}

	public static SurveySummaryResponse from(SurveySummary summary) {
		return new SurveySummaryResponse(
				summary.getDailyResponses().stream()
						.map(day -> new DailyResponse(day.date(), day.count())).toList(),
				summary.getQuestions().stream()
						.map(question -> new Question(question.questionId(), question.questionText(),
								question.options().stream()
										.map(option -> new Option(option.optionId(), option.optionText(),
												option.count()))
										.toList()))
						.toList());
	}
}
