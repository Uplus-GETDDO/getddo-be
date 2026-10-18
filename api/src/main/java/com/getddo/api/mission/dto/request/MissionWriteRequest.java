package com.getddo.api.mission.dto.request;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.getddo.core.mission.domain.MissionRegistration;
import com.getddo.core.mission.domain.MissionType;
import com.getddo.core.mission.domain.QuizOption;
import com.getddo.core.mission.domain.QuizQuestion;
import com.getddo.core.mission.domain.QuizQuestionType;
import com.getddo.core.mission.domain.SurveyOption;
import com.getddo.core.mission.domain.SurveyQuestion;
import com.getddo.core.mission.domain.SurveyQuestionType;
import com.getddo.core.mission.exception.MissionErrorCode;
import com.getddo.core.mission.exception.MissionException;

/**
 * 관리자 미션 등록(AM03) 요청. 미션 보상은 응모권 1장으로 고정이라 {@code rewardTicketCount}는 생략하거나 1만 보낼 수 있다.
 *
 * @param questions 퀴즈 미션은 퀴즈 문항, 설문 미션은 설문 문항
 */
public record MissionWriteRequest(
		@NotBlank @Size(max = 200) String title,
		@NotBlank String description,
		@NotNull MissionType missionType,
		@NotNull Instant startsAt,
		@NotNull Instant endsAt,
		Integer rewardTicketCount,
		@Size(max = 500) String imageKey,
		@NotEmpty List<@NotNull @Valid QuestionWrite> questions) {

	private static final int FIXED_REWARD_TICKET_COUNT = 1;

	/**
	 * @param questionType 퀴즈는 OX·SINGLE_CHOICE·SHORT_ANSWER, 설문은 SINGLE_CHOICE·FREE_TEXT
	 * @param required 설문 문항의 필수 여부. 퀴즈에서는 쓰지 않는다
	 * @param options 선택형 문항의 선택지
	 * @param correctAnswer 단답형 퀴즈의 정답
	 */
	public record QuestionWrite(
			@NotBlank String questionType,
			@NotBlank String questionText,
			@NotNull Integer displayOrder,
			Boolean required,
			List<@NotNull @Valid OptionWrite> options,
			String correctAnswer) {
	}

	/**
	 * @param isCorrect 퀴즈 선택지의 정답 여부. 설문에서는 보내지 않는다
	 */
	public record OptionWrite(
			@NotBlank String optionText,
			@NotNull Integer displayOrder,
			Boolean isCorrect) {
	}

	/**
	 * 요청을 서비스가 등록할 미션으로 바꾼다.
	 *
	 * @throws MissionException 보상 수량이 1이 아니거나, 문항 유형을 알 수 없거나, 미션 유형에 맞지 않는 필드가 있을 때
	 *         ({@code MISSION-001})
	 */
	public MissionRegistration toRegistration() {
		if (rewardTicketCount != null && rewardTicketCount != FIXED_REWARD_TICKET_COUNT) {
			throw invalid();
		}
		List<QuizQuestion> quiz = missionType == MissionType.QUIZ
				? questions.stream().map(MissionWriteRequest::toQuiz).toList() : List.of();
		List<SurveyQuestion> survey = missionType == MissionType.SURVEY
				? questions.stream().map(MissionWriteRequest::toSurvey).toList() : List.of();
		return new MissionRegistration(title, description, missionType, startsAt, endsAt, imageKey, quiz, survey);
	}

	private static QuizQuestion toQuiz(QuestionWrite question) {
		if (question.required() != null) {
			throw invalid();
		}
		List<QuizOption> options = optionsOf(question).stream()
				.map(option -> QuizOption.of(option.optionText(), option.displayOrder(),
						Boolean.TRUE.equals(option.isCorrect())))
				.toList();
		return QuizQuestion.of(parse(QuizQuestionType.class, question.questionType()), question.questionText(),
				question.displayOrder(), question.correctAnswer(), options);
	}

	private static SurveyQuestion toSurvey(QuestionWrite question) {
		if (question.required() == null || question.correctAnswer() != null) {
			throw invalid();
		}
		List<SurveyOption> options = optionsOf(question).stream()
				.map(option -> {
					if (option.isCorrect() != null) {
						throw invalid();
					}
					return SurveyOption.of(option.optionText(), option.displayOrder());
				})
				.toList();
		return SurveyQuestion.of(parse(SurveyQuestionType.class, question.questionType()), question.questionText(),
				question.required(), question.displayOrder(), options);
	}

	private static List<OptionWrite> optionsOf(QuestionWrite question) {
		return question.options() == null ? List.of() : question.options();
	}

	private static <E extends Enum<E>> E parse(Class<E> type, String name) {
		try {
			return Enum.valueOf(type, name);
		} catch (IllegalArgumentException unknownType) {
			throw invalid();
		}
	}

	private static MissionException invalid() {
		return new MissionException(MissionErrorCode.INVALID_MISSION_DEFINITION);
	}
}
