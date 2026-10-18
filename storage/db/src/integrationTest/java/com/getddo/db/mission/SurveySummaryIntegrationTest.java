package com.getddo.db.mission;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.getddo.core.mission.domain.MissionAnswerInput;
import com.getddo.core.mission.domain.MissionDetail;
import com.getddo.core.mission.domain.MissionSubmissionCommand;
import com.getddo.core.mission.domain.SurveyQuestion;
import com.getddo.core.mission.domain.SurveySummary;
import com.getddo.core.mission.exception.MissionErrorCode;
import com.getddo.core.mission.service.MissionSubmissionService;
import com.getddo.core.mission.service.SurveySummaryService;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;

import static com.getddo.db.ticket.TicketGrantSeeds.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 MySQL에서 설문 응답 집계(R5)의 KST 날짜 경계, 완료된 제출만 세기, 선택지별 건수를 검증한다. */
class SurveySummaryIntegrationTest extends MissionIntegrationTestSupport {

	/** KST 2026-10-11 23:59:59. */
	private static final Instant KST_LAST_SECOND_OF_11TH = Instant.parse("2026-10-11T14:59:59Z");
	/** KST 2026-10-12 00:00:00. */
	private static final Instant KST_FIRST_SECOND_OF_12TH = Instant.parse("2026-10-11T15:00:00Z");
	private static final LocalDate DAY_11 = LocalDate.of(2026, 10, 11);
	private static final LocalDate DAY_12 = LocalDate.of(2026, 10, 12);

	@Autowired
	private MissionSubmissionService submissionService;
	@Autowired
	private SurveySummaryService summaryService;

	private User admin() {
		return new User(adminId, "관리자", UserRole.ADMIN, UserStatus.ACTIVE, null, null, null, null, null, null, null);
	}

	private void submitChoice(UUID user, MissionDetail survey, int optionIndex, Instant at) {
		clock.set(at);
		SurveyQuestion choice = survey.getSurveyQuestions().get(0);
		submissionService.submit(new MissionSubmissionCommand(user, survey.getMission().getId(), UUID.randomUUID(),
				List.of(new MissionAnswerInput(choice.getId(), choice.getOptions().get(optionIndex).getId(), null))));
	}

	@Test
	@DisplayName("KST 자정 직전·직후 제출은 서로 다른 날짜로 세고, 기간 밖 제출은 세지 않는다")
	void groupsByKstDateAtMidnightBoundary() {
		// given
		MissionDetail survey = saveSurveyMission("경계 설문");
		submitChoice(userId, survey, 0, KST_LAST_SECOND_OF_11TH);
		submitChoice(seeds.user(), survey, 0, KST_FIRST_SECOND_OF_12TH);
		submitChoice(seeds.user(), survey, 1, KST_FIRST_SECOND_OF_12TH.plusSeconds(3_600));
		submitChoice(seeds.user(), survey, 0, Instant.parse("2026-10-14T03:00:00Z"));
		// when
		SurveySummary summary = summaryService.summarize(admin(), survey.getMission().getId(), DAY_11, DAY_12);
		// then
		assertThat(summary.getDailyResponses()).extracting(SurveySummary.DailyCount::date)
				.containsExactly(DAY_11, DAY_12);
		assertThat(summary.getDailyResponses()).extracting(SurveySummary.DailyCount::count).containsExactly(1L, 2L);
		SurveySummary.QuestionSummary question = summary.getQuestions().get(0);
		assertThat(question.options()).extracting(SurveySummary.OptionCount::optionText).containsExactly("좋음", "보통");
		assertThat(question.options()).extracting(SurveySummary.OptionCount::count).containsExactly(2L, 1L);
	}

	@Test
	@DisplayName("미완료 제출의 답변은 날짜별 수와 선택지별 수에 세지 않는다")
	void excludesIncompleteSubmissions() {
		// given
		MissionDetail survey = saveSurveyMission("미완료 설문");
		submitChoice(userId, survey, 0, KST_FIRST_SECOND_OF_12TH);
		UUID incomplete = UUID.randomUUID();
		SurveyQuestion choice = survey.getSurveyQuestions().get(0);
		LocalDateTime at = LocalDateTime.ofInstant(KST_FIRST_SECOND_OF_12TH, ZoneOffset.UTC);
		jdbc.update("""
				insert into mission_submissions (id, mission_id, reward_policy_id, user_id, is_completed, created_at)
				values (?, ?, ?, ?, false, ?)
				""", bytes(incomplete), bytes(survey.getMission().getId()), bytes(rewardPolicyId),
				bytes(seeds.user()), at);
		jdbc.update("""
				insert into survey_answers (id, submission_id, mission_id, survey_question_id, selected_option_id,
				  created_at) values (?, ?, ?, ?, ?, ?)
				""", bytes(UUID.randomUUID()), bytes(incomplete), bytes(survey.getMission().getId()),
				bytes(choice.getId()), bytes(choice.getOptions().get(1).getId()), at);
		// 미완료 제출이 조회 범위 안에 저장됐는지 먼저 확인해, 아래 제외가 완료 여부 조건 때문임을 보장한다.
		Long storedInRange = jdbc.queryForObject("""
				select count(*) from mission_submissions
				where mission_id = ? and created_at >= ? and created_at < ?
				""", Long.class, bytes(survey.getMission().getId()),
				LocalDateTime.ofInstant(KST_FIRST_SECOND_OF_12TH, ZoneOffset.UTC),
				LocalDateTime.ofInstant(KST_FIRST_SECOND_OF_12TH.plusSeconds(86_400), ZoneOffset.UTC));
		assertThat(storedInRange).isEqualTo(2L);
		// when
		SurveySummary summary = summaryService.summarize(admin(), survey.getMission().getId(), DAY_12, DAY_12);
		// then
		assertThat(summary.getDailyResponses()).extracting(SurveySummary.DailyCount::count).containsExactly(1L);
		assertThat(summary.getQuestions().get(0).options()).extracting(SurveySummary.OptionCount::count)
				.containsExactly(1L, 0L);
	}

	@Test
	@DisplayName("다른 설문의 응답은 섞이지 않고, 퀴즈 미션과 없는 미션은 거절한다")
	void isolatesMissionsAndRejectsNonSurvey() {
		// given
		MissionDetail first = saveSurveyMission("설문 하나");
		MissionDetail second = saveSurveyMission("설문 둘");
		MissionDetail quiz = saveQuizMission("퀴즈");
		submitChoice(userId, first, 0, KST_FIRST_SECOND_OF_12TH);
		// when
		SurveySummary other = summaryService.summarize(admin(), second.getMission().getId(), DAY_12, DAY_12);
		// then
		assertThat(other.getDailyResponses()).extracting(SurveySummary.DailyCount::count).containsExactly(0L);
		assertThat(other.getQuestions().get(0).options()).extracting(SurveySummary.OptionCount::count)
				.containsExactly(0L, 0L);
		assertThatThrownBy(() -> summaryService.summarize(admin(), quiz.getMission().getId(), DAY_12, DAY_12))
				.hasFieldOrPropertyWithValue("errorCode", MissionErrorCode.INVALID_MISSION_DEFINITION);
		assertThatThrownBy(() -> summaryService.summarize(admin(), UUID.randomUUID(), DAY_12, DAY_12))
				.hasFieldOrPropertyWithValue("errorCode", MissionErrorCode.MISSION_NOT_FOUND);
	}

	@Test
	@DisplayName("시작 날짜 00:00은 포함하고 그 직전은 제외하며, 끝 날짜 다음 날 00:00은 제외하고 그 직전은 포함한다")
	void rangeIncludesStartAndExcludesEndBoundary() {
		// given
		MissionDetail survey = saveSurveyMission("경계 설문");
		UUID missionId = survey.getMission().getId();
		submitChoice(seeds.user(), survey, 0, KST_FIRST_SECOND_OF_12TH.minusSeconds(1));
		submitChoice(seeds.user(), survey, 0, KST_FIRST_SECOND_OF_12TH);
		submitChoice(seeds.user(), survey, 0, KST_FIRST_SECOND_OF_12TH.plusSeconds(86_400).minusSeconds(1));
		submitChoice(seeds.user(), survey, 0, KST_FIRST_SECOND_OF_12TH.plusSeconds(86_400));
		// when
		SurveySummary onlyTwelfth = summaryService.summarize(admin(), missionId, DAY_12, DAY_12);
		SurveySummary onlyEleventh = summaryService.summarize(admin(), missionId, DAY_11, DAY_11);
		// then
		assertThat(onlyTwelfth.getDailyResponses()).extracting(SurveySummary.DailyCount::count).containsExactly(2L);
		assertThat(onlyTwelfth.getQuestions().get(0).options()).extracting(SurveySummary.OptionCount::count)
				.containsExactly(2L, 0L);
		assertThat(onlyEleventh.getDailyResponses()).extracting(SurveySummary.DailyCount::count).containsExactly(1L);
	}

	@Test
	@DisplayName("31일 기간은 허용해 모든 날짜를 0건 포함해 담고, 32일째로 넘어가면 거절한다")
	void allowsThirtyOneDaysAndRejectsMore() {
		// given
		MissionDetail survey = saveSurveyMission("긴 기간 설문");
		UUID missionId = survey.getMission().getId();
		submitChoice(userId, survey, 0, KST_FIRST_SECOND_OF_12TH);
		LocalDate from = LocalDate.of(2026, 10, 1);
		// when
		SurveySummary month = summaryService.summarize(admin(), missionId, from, from.plusDays(30));
		// then
		assertThat(month.getDailyResponses()).hasSize(31);
		assertThat(month.getDailyResponses().stream().mapToLong(SurveySummary.DailyCount::count).sum()).isEqualTo(1L);
		assertThat(month.getDailyResponses().get(11).date()).isEqualTo(DAY_12);
		assertThat(month.getDailyResponses().get(11).count()).isEqualTo(1L);
		assertThatThrownBy(() -> summaryService.summarize(admin(), missionId, from, from.plusDays(31)))
				.hasFieldOrPropertyWithValue("errorCode", MissionErrorCode.INVALID_MISSION_DEFINITION);
	}
}
