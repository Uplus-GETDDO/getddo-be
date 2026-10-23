package com.getddo.core.mission.domain;

import java.time.Instant;
import java.util.Objects;

import lombok.Getter;

/**
 * 설문 자유 서술 문항에 완료된 제출이 남긴 답변 한 건.
 *
 * <p>관리자가 답변 내용을 모아 볼 때 쓰며 익명이다. 누가 썼는지 알 수 있는 정보(사용자·제출 식별자)는 담지 않는다.</p>
 */
@Getter
public final class SurveyFreeTextAnswer {

	/** 사용자가 쓴 답변 원문. */
	private final String answerText;
	/** 제출을 받은 시각. */
	private final Instant submittedAt;

	public SurveyFreeTextAnswer(String answerText, Instant submittedAt) {
		this.answerText = Objects.requireNonNull(answerText, "answerText");
		this.submittedAt = Objects.requireNonNull(submittedAt, "submittedAt");
	}
}
