package com.getddo.api.mission.dto.response;

import java.time.Instant;

import com.getddo.core.mission.domain.SurveyFreeTextAnswer;

/**
 * 설문 자유 서술 답변 한 건. 익명이라 답변 원문과 제출 시각만 담고 사용자·제출 식별자는 담지 않는다.
 *
 * @param answerText 사용자가 쓴 답변 원문. 화면에서 HTML로 해석하지 않는다
 * @param submittedAt 제출을 받은 시각
 */
public record SurveyAnswerResponse(String answerText, Instant submittedAt) {

	public static SurveyAnswerResponse from(SurveyFreeTextAnswer answer) {
		return new SurveyAnswerResponse(answer.getAnswerText(), answer.getSubmittedAt());
	}
}
