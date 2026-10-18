package com.getddo.core.mission.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.mission.domain.Mission;
import com.getddo.core.mission.domain.MissionDetail;
import com.getddo.core.mission.domain.MissionStatus;
import com.getddo.core.mission.domain.MissionType;
import com.getddo.core.mission.domain.QuizOption;
import com.getddo.core.mission.domain.QuizQuestion;
import com.getddo.core.mission.domain.QuizQuestionType;
import com.getddo.core.mission.domain.SurveyOption;
import com.getddo.core.mission.domain.SurveyQuestion;
import com.getddo.core.mission.domain.SurveyQuestionType;
import com.getddo.core.mission.domain.SurveySummary;
import com.getddo.core.mission.exception.MissionErrorCode;
import com.getddo.core.mission.exception.MissionException;
import com.getddo.core.mission.repository.MissionRepository;
import com.getddo.core.mission.repository.SurveySummaryRepository;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SurveySummaryServiceTest {

	private static final UUID MISSION_ID = UUID.randomUUID();
	private static final User ADMIN = user(UserRole.ADMIN, UserStatus.ACTIVE);
	private static final LocalDate FROM = LocalDate.of(2026, 10, 11);
	private static final LocalDate TO = LocalDate.of(2026, 10, 13);
	/** KST 2026-10-11 00:00. */
	private static final Instant START = Instant.parse("2026-10-10T15:00:00Z");
	/** KST 2026-10-14 00:00(끝 날짜 다음 날 자정). */
	private static final Instant END = Instant.parse("2026-10-13T15:00:00Z");

	@Mock
	private MissionRepository missionRepository;
	@Mock
	private SurveySummaryRepository summaryRepository;

	private static User user(UserRole role, UserStatus status) {
		return new User(UUID.randomUUID(), "사용자", role, status, null, null, null, null, null, null, null);
	}

	private SurveySummaryService service() {
		return new SurveySummaryService(missionRepository, summaryRepository,
				new TimeProvider(Clock.fixed(START, ZoneOffset.UTC)));
	}

	private static Mission mission(MissionType type) {
		return new Mission(MISSION_ID, UUID.randomUUID(), UUID.randomUUID(), "설문", "설명", type, START,
				START.plusSeconds(86_400 * 30L), MissionStatus.ACTIVE, null, START, START);
	}

	private static void assertError(Throwable thrown, MissionErrorCode expected) {
		assertThat(thrown).isInstanceOfSatisfying(MissionException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(expected));
	}

	@Test
	@DisplayName("관리자가 아니거나 활성이 아니면 MISSION-006으로 거절하고 저장소를 읽지 않는다")
	void rejectsNonAdmins() {
		for (User caller : new User[] {null, user(UserRole.USER, UserStatus.ACTIVE),
				user(UserRole.ADMIN, UserStatus.INACTIVE)}) {
			assertThatThrownBy(() -> service().summarize(caller, MISSION_ID, FROM, TO))
					.satisfies(e -> assertError(e, MissionErrorCode.ADMIN_REQUIRED));
		}
		verifyNoInteractions(missionRepository, summaryRepository);
	}

	@Test
	@DisplayName("시작·끝 날짜가 없거나 끝이 시작보다 앞서거나 32일째로 넘어가면 MISSION-001이다")
	void rejectsInvalidPeriod() {
		LocalDate from = LocalDate.of(2026, 10, 1);
		assertThatThrownBy(() -> service().summarize(ADMIN, MISSION_ID, null, TO))
				.satisfies(e -> assertError(e, MissionErrorCode.INVALID_MISSION_DEFINITION));
		assertThatThrownBy(() -> service().summarize(ADMIN, MISSION_ID, FROM, null))
				.satisfies(e -> assertError(e, MissionErrorCode.INVALID_MISSION_DEFINITION));
		assertThatThrownBy(() -> service().summarize(ADMIN, MISSION_ID, TO, FROM))
				.satisfies(e -> assertError(e, MissionErrorCode.INVALID_MISSION_DEFINITION));
		assertThatThrownBy(() -> service().summarize(ADMIN, MISSION_ID, from, from.plusDays(31)))
				.satisfies(e -> assertError(e, MissionErrorCode.INVALID_MISSION_DEFINITION));
		verifyNoInteractions(missionRepository, summaryRepository);
	}

	@Test
	@DisplayName("정확히 31일 기간은 허용한다")
	void acceptsThirtyOneDays() {
		// given
		LocalDate from = LocalDate.of(2026, 10, 1);
		when(missionRepository.findDetailById(MISSION_ID)).thenReturn(Optional.of(
				new MissionDetail(mission(MissionType.SURVEY), List.of(), List.of(
						SurveyQuestion.of(SurveyQuestionType.FREE_TEXT, "의견", false, 1, List.of())))));
		// when
		SurveySummary summary = service().summarize(ADMIN, MISSION_ID, from, from.plusDays(30));
		// then
		assertThat(summary.getDailyResponses()).hasSize(31);
	}

	@Test
	@DisplayName("없는 미션은 MISSION-003, 퀴즈 미션은 MISSION-001이다")
	void rejectsMissingAndQuizMission() {
		// given
		UUID missing = UUID.randomUUID();
		when(missionRepository.findDetailById(missing)).thenReturn(Optional.empty());
		when(missionRepository.findDetailById(MISSION_ID)).thenReturn(Optional.of(new MissionDetail(
				mission(MissionType.QUIZ), List.of(QuizQuestion.of(QuizQuestionType.SHORT_ANSWER, "문제", 1, "정답",
						List.of())), List.of())));
		// when
		// then
		assertThatThrownBy(() -> service().summarize(ADMIN, missing, FROM, TO))
				.satisfies(e -> assertError(e, MissionErrorCode.MISSION_NOT_FOUND));
		assertThatThrownBy(() -> service().summarize(ADMIN, MISSION_ID, FROM, TO))
				.satisfies(e -> assertError(e, MissionErrorCode.INVALID_MISSION_DEFINITION));
		verifyNoInteractions(summaryRepository);
	}

	@Test
	@DisplayName("기간의 모든 날짜를 0건 포함해 담고 선택형 문항의 모든 선택지를 0건 포함해 담으며 자유 서술은 제외한다")
	void fillsZeroDaysAndOptions() {
		// given
		UUID q1 = UUID.randomUUID();
		UUID o1 = UUID.randomUUID();
		UUID o2 = UUID.randomUUID();
		SurveyQuestion choice = new SurveyQuestion(q1, SurveyQuestionType.SINGLE_CHOICE, "선호", true, 1,
				List.of(new SurveyOption(o1, "A", 1), new SurveyOption(o2, "B", 2)));
		SurveyQuestion free = new SurveyQuestion(UUID.randomUUID(), SurveyQuestionType.FREE_TEXT, "의견", false, 2,
				List.of());
		when(missionRepository.findDetailById(MISSION_ID)).thenReturn(Optional.of(
				new MissionDetail(mission(MissionType.SURVEY), List.of(), List.of(choice, free))));
		when(summaryRepository.countCompletedSubmissionsByDay(MISSION_ID, START, END))
				.thenReturn(Map.of(LocalDate.of(2026, 10, 12), 5L));
		when(summaryRepository.countSelectionsByOption(MISSION_ID, START, END)).thenReturn(Map.of(o1, 3L));
		// when
		SurveySummary summary = service().summarize(ADMIN, MISSION_ID, FROM, TO);
		// then
		assertThat(summary.getDailyResponses()).extracting(SurveySummary.DailyCount::date)
				.containsExactly(FROM, FROM.plusDays(1), TO);
		assertThat(summary.getDailyResponses()).extracting(SurveySummary.DailyCount::count)
				.containsExactly(0L, 5L, 0L);
		assertThat(summary.getQuestions()).hasSize(1);
		SurveySummary.QuestionSummary question = summary.getQuestions().get(0);
		assertThat(question.questionId()).isEqualTo(q1);
		assertThat(question.options()).extracting(SurveySummary.OptionCount::optionId).containsExactly(o1, o2);
		assertThat(question.options()).extracting(SurveySummary.OptionCount::count).containsExactly(3L, 0L);
	}
}
