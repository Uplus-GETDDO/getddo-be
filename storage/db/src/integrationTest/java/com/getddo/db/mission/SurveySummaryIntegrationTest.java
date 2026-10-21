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

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.mission.domain.MissionAnswerInput;
import com.getddo.core.mission.domain.MissionDetail;
import com.getddo.core.mission.domain.MissionSubmissionCommand;
import com.getddo.core.mission.domain.SurveyFreeTextAnswer;
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

	private void submitChoiceAndText(UUID user, MissionDetail survey, String text, Instant at) {
		clock.set(at);
		SurveyQuestion choice = survey.getSurveyQuestions().get(0);
		SurveyQuestion free = survey.getSurveyQuestions().get(1);
		submissionService.submit(new MissionSubmissionCommand(user, survey.getMission().getId(), UUID.randomUUID(),
				List.of(new MissionAnswerInput(choice.getId(), choice.getOptions().get(0).getId(), null),
						new MissionAnswerInput(free.getId(), null, text))));
	}

	@Test
	@DisplayName("자유 서술 답변은 KST 날짜 경계로 걸러 최신순으로 돌려주고 답변 원문과 제출 시각만 담는다")
	void listsFreeTextAnswersNewestFirstWithinKstRange() {
		// given
		MissionDetail survey = saveSurveyMission("서술 설문");
		UUID missionId = survey.getMission().getId();
		UUID freeId = survey.getSurveyQuestions().get(1).getId();
		submitChoiceAndText(seeds.user(), survey, "전날 마지막 초", KST_FIRST_SECOND_OF_12TH.minusSeconds(1));
		submitChoiceAndText(seeds.user(), survey, "당일 첫 초", KST_FIRST_SECOND_OF_12TH);
		submitChoiceAndText(seeds.user(), survey, "당일 한 시간 뒤", KST_FIRST_SECOND_OF_12TH.plusSeconds(3_600));
		submitChoiceAndText(seeds.user(), survey, "다음 날 첫 초", KST_FIRST_SECOND_OF_12TH.plusSeconds(86_400));
		// when
		PageResult<SurveyFreeTextAnswer> day12 = summaryService.findFreeTextAnswers(admin(), missionId, freeId, DAY_12,
				DAY_12, new PageQuery(1, 10));
		// then
		assertThat(day12.getTotalElements()).isEqualTo(2L);
		assertThat(day12.getItems()).extracting(SurveyFreeTextAnswer::getAnswerText)
				.containsExactly("당일 한 시간 뒤", "당일 첫 초");
		assertThat(day12.getItems()).extracting(SurveyFreeTextAnswer::getSubmittedAt)
				.containsExactly(KST_FIRST_SECOND_OF_12TH.plusSeconds(3_600), KST_FIRST_SECOND_OF_12TH);
	}

	@Test
	@DisplayName("자유 서술 답변은 페이지로 나누고 범위를 넘는 페이지는 빈 목록에 전체 건수를 돌려준다")
	void pagesFreeTextAnswers() {
		// given
		MissionDetail survey = saveSurveyMission("서술 설문");
		UUID missionId = survey.getMission().getId();
		UUID freeId = survey.getSurveyQuestions().get(1).getId();
		for (int i = 0; i < 3; i++) {
			submitChoiceAndText(seeds.user(), survey, "답변 " + i, KST_FIRST_SECOND_OF_12TH.plusSeconds(60L * i));
		}
		// when
		PageResult<SurveyFreeTextAnswer> second = summaryService.findFreeTextAnswers(admin(), missionId, freeId, DAY_12,
				DAY_12, new PageQuery(2, 2));
		PageResult<SurveyFreeTextAnswer> beyond = summaryService.findFreeTextAnswers(admin(), missionId, freeId,
				DAY_12, DAY_12, new PageQuery(3, 2));
		// then
		assertThat(second.getTotalElements()).isEqualTo(3L);
		assertThat(second.getItems()).extracting(SurveyFreeTextAnswer::getAnswerText).containsExactly("답변 0");
		assertThat(beyond.getTotalElements()).isEqualTo(3L);
		assertThat(beyond.getItems()).isEmpty();
	}

	@Test
	@DisplayName("제출 시각이 같은 답변은 답변 ID 내림차순으로 페이지 경계에서도 겹치지 않고, 서술 답변이 없거나 answer_text가 빈 완료 제출은 담지 않는다")
	void ordersTiesByAnswerIdAndSkipsEmptyAnswers() {
		// given
		MissionDetail survey = saveSurveyMission("동률 설문");
		UUID missionId = survey.getMission().getId();
		UUID freeId = survey.getSurveyQuestions().get(1).getId();
		Instant sameInstant = KST_FIRST_SECOND_OF_12TH.plusSeconds(10);
		submitChoiceAndText(seeds.user(), survey, "동시 답변 A", sameInstant);
		submitChoiceAndText(seeds.user(), survey, "동시 답변 B", sameInstant);
		submitChoice(seeds.user(), survey, 0, sameInstant);
		submitChoice(seeds.user(), survey, 1, sameInstant);
		// 선택 문항이라 서술 답변 행이 없는 완료 제출에, answer_text가 null인 행을 직접 만들어 SQL 조건을 보호한다.
		byte[] withoutText = jdbc.queryForObject("""
				select s.id from mission_submissions s
				where s.mission_id = ? and not exists (
				  select 1 from survey_answers a where a.submission_id = s.id and a.survey_question_id = ?)
				limit 1
				""", byte[].class, bytes(missionId), bytes(freeId));
		jdbc.update("""
				insert into survey_answers (id, submission_id, mission_id, survey_question_id, answer_text, created_at)
				values (?, ?, ?, ?, null, ?)
				""", bytes(UUID.randomUUID()), withoutText, bytes(missionId), bytes(freeId),
				LocalDateTime.ofInstant(sameInstant, ZoneOffset.UTC));
		List<String> byIdDescending = jdbc.queryForList("""
				select answer_text from survey_answers
				where survey_question_id = ? and answer_text is not null order by id desc
				""", String.class, bytes(freeId));
		// when
		PageResult<SurveyFreeTextAnswer> first = summaryService.findFreeTextAnswers(admin(), missionId, freeId, DAY_12,
				DAY_12, new PageQuery(1, 1));
		PageResult<SurveyFreeTextAnswer> second = summaryService.findFreeTextAnswers(admin(), missionId, freeId, DAY_12,
				DAY_12, new PageQuery(2, 1));
		// then
		assertThat(byIdDescending).containsExactlyInAnyOrder("동시 답변 A", "동시 답변 B");
		assertThat(first.getTotalElements()).isEqualTo(2L);
		assertThat(second.getTotalElements()).isEqualTo(2L);
		assertThat(first.getItems()).extracting(SurveyFreeTextAnswer::getAnswerText).containsExactly(byIdDescending.get(0));
		assertThat(second.getItems()).extracting(SurveyFreeTextAnswer::getAnswerText)
				.containsExactly(byIdDescending.get(1));
	}

	@Test
	@DisplayName("미완료 제출의 서술 답변과 다른 설문의 답변은 섞이지 않고, 선택형 문항은 거절한다")
	void excludesIncompleteAndOtherMissionAnswers() {
		// given
		MissionDetail survey = saveSurveyMission("서술 설문");
		MissionDetail other = saveSurveyMission("다른 서술 설문");
		UUID missionId = survey.getMission().getId();
		SurveyQuestion free = survey.getSurveyQuestions().get(1);
		submitChoiceAndText(userId, survey, "완료된 답변", KST_FIRST_SECOND_OF_12TH);
		submitChoiceAndText(seeds.user(), other, "다른 설문 답변", KST_FIRST_SECOND_OF_12TH);
		UUID incomplete = UUID.randomUUID();
		LocalDateTime at = LocalDateTime.ofInstant(KST_FIRST_SECOND_OF_12TH, ZoneOffset.UTC);
		jdbc.update("""
				insert into mission_submissions (id, mission_id, reward_policy_id, user_id, is_completed, created_at)
				values (?, ?, ?, ?, false, ?)
				""", bytes(incomplete), bytes(missionId), bytes(rewardPolicyId), bytes(seeds.user()), at);
		jdbc.update("""
				insert into survey_answers (id, submission_id, mission_id, survey_question_id, answer_text, created_at)
				values (?, ?, ?, ?, ?, ?)
				""", bytes(UUID.randomUUID()), bytes(incomplete), bytes(missionId), bytes(free.getId()),
				"미완료 답변", at);
		// when
		PageResult<SurveyFreeTextAnswer> answers = summaryService.findFreeTextAnswers(admin(), missionId, free.getId(),
				DAY_12, DAY_12, new PageQuery(1, 10));
		// then
		assertThat(answers.getItems()).extracting(SurveyFreeTextAnswer::getAnswerText).containsExactly("완료된 답변");
		assertThatThrownBy(() -> summaryService.findFreeTextAnswers(admin(), missionId,
				survey.getSurveyQuestions().get(0).getId(), DAY_12, DAY_12, new PageQuery(1, 10)))
				.hasFieldOrPropertyWithValue("errorCode", MissionErrorCode.INVALID_MISSION_DEFINITION);
		assertThatThrownBy(() -> summaryService.findFreeTextAnswers(admin(), missionId, other.getSurveyQuestions().get(1)
				.getId(), DAY_12, DAY_12, new PageQuery(1, 10)))
				.hasFieldOrPropertyWithValue("errorCode", MissionErrorCode.INVALID_MISSION_DEFINITION);
	}
}
