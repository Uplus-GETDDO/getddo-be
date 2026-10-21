package com.getddo.api.mission.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.getddo.core.mission.domain.Mission;
import com.getddo.core.mission.domain.MissionDetail;
import com.getddo.core.mission.domain.MissionStatus;
import com.getddo.core.mission.domain.MissionType;
import com.getddo.core.mission.domain.QuizOption;
import com.getddo.core.mission.domain.QuizQuestion;
import com.getddo.core.mission.domain.SurveyOption;
import com.getddo.core.mission.domain.SurveyQuestion;
import com.getddo.core.user.domain.NamedValue;

/**
 * 관리자 미션(AM01~AM03). 사용자 응답과 달리 퀴즈의 정답 정보를 포함한다.
 *
 * <p>상태는 {@code ACTIVE}·{@code ENDED}뿐이며 초안 상태는 없다. 등록한 미션은 바로 {@code ACTIVE}이고 사용자가 제출할 수
 * 있는 기간은 {@code startsAt}·{@code endsAt}이 정한다. {@code rewardTicketCount}는 미션 보상이 응모권 1장으로 고정이라 항상
 * 1이다. 목록(AM01)은 문항을 담지 않아 {@code questions}가 없다. {@code createdByName}은 작성한 관리자의 현재 이름이며
 * 표시용이다.</p>
 */
public record AdminMissionResponse(
		UUID id,
		String title,
		String description,
		MissionType missionType,
		Instant startsAt,
		Instant endsAt,
		int rewardTicketCount,
		String imageKey,
		MissionStatus status,
		UUID rewardPolicyId,
		UUID createdBy,
		String createdByName,
		Instant createdAt,
		Instant updatedAt,
		@JsonInclude(JsonInclude.Include.NON_NULL) List<Question> questions) {

	private static final int FIXED_REWARD_TICKET_COUNT = 1;

	/**
	 * @param required 설문 문항의 필수 여부. 퀴즈 문항에는 없다
	 * @param correctAnswer 단답형 퀴즈의 정답. 그 밖의 문항에는 없다
	 */
	public record Question(
			UUID id,
			String questionType,
			String questionText,
			int displayOrder,
			@JsonInclude(JsonInclude.Include.NON_NULL) Boolean required,
			@JsonInclude(JsonInclude.Include.NON_NULL) String correctAnswer,
			List<Option> options) {
	}

	/**
	 * @param isCorrect 퀴즈 선택지의 정답 여부. 설문 선택지에는 없다
	 */
	public record Option(
			UUID id,
			String optionText,
			int displayOrder,
			@JsonInclude(JsonInclude.Include.NON_NULL) Boolean isCorrect) {
	}

	/** 문항 없이 미션 한 건을 옮긴다(목록용). */
	public static AdminMissionResponse summaryOf(NamedValue<Mission> named) {
		return of(named.getValue(), named.getName(), null);
	}

	/** 정답을 포함한 문항·선택지와 함께 미션 한 건을 옮긴다. */
	public static AdminMissionResponse from(NamedValue<MissionDetail> named) {
		MissionDetail detail = named.getValue();
		List<Question> quiz = detail.getQuizQuestions().stream().map(AdminMissionResponse::question).toList();
		List<Question> survey = detail.getSurveyQuestions().stream().map(AdminMissionResponse::question).toList();
		return of(detail.getMission(), named.getName(), quiz.isEmpty() ? survey : quiz);
	}

	private static AdminMissionResponse of(Mission mission, String createdByName, List<Question> questions) {
		return new AdminMissionResponse(mission.getId(), mission.getTitle(), mission.getDescription(),
				mission.getMissionType(), mission.getStartsAt(), mission.getEndsAt(), FIXED_REWARD_TICKET_COUNT,
				mission.getImageKey(), mission.getStatus(), mission.getRewardPolicyId(), mission.getCreatedBy(),
				createdByName, mission.getCreatedAt(), mission.getUpdatedAt(), questions);
	}

	private static Question question(QuizQuestion question) {
		return new Question(question.getId(), question.getQuestionType().name(), question.getQuestionText(),
				question.getDisplayOrder(), null, question.getCorrectAnswer(),
				question.getOptions().stream().map(AdminMissionResponse::option).toList());
	}

	private static Question question(SurveyQuestion question) {
		return new Question(question.getId(), question.getQuestionType().name(), question.getQuestionText(),
				question.getDisplayOrder(), question.isRequired(), null,
				question.getOptions().stream().map(AdminMissionResponse::option).toList());
	}

	private static Option option(QuizOption option) {
		return new Option(option.getId(), option.getOptionText(), option.getDisplayOrder(), option.isCorrect());
	}

	private static Option option(SurveyOption option) {
		return new Option(option.getId(), option.getOptionText(), option.getDisplayOrder(), null);
	}
}
