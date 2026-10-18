package com.getddo.api.mission.controller;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.getddo.api.common.context.CurrentUserArgumentResolver;
import com.getddo.api.common.exception.GlobalExceptionHandler;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.mission.domain.Mission;
import com.getddo.core.mission.domain.MissionDetail;
import com.getddo.core.mission.domain.MissionRegistration;
import com.getddo.core.mission.domain.MissionSearch;
import com.getddo.core.mission.domain.MissionStatus;
import com.getddo.core.mission.domain.MissionType;
import com.getddo.core.mission.domain.QuizOption;
import com.getddo.core.mission.domain.QuizQuestion;
import com.getddo.core.mission.domain.QuizQuestionType;
import com.getddo.core.mission.domain.SurveyOption;
import com.getddo.core.mission.domain.SurveyQuestion;
import com.getddo.core.mission.domain.SurveyQuestionType;
import com.getddo.core.mission.exception.MissionErrorCode;
import com.getddo.core.mission.exception.MissionException;
import com.getddo.core.mission.service.AdminMissionService;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.service.UserService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminMissionControllerTest {

	private static final UUID ADMIN_ID = UUID.randomUUID();
	private static final User ADMIN = new User(ADMIN_ID, "관리자", UserRole.ADMIN, UserStatus.ACTIVE, null, null, null,
			null, null, null, null);
	private static final Instant STARTS_AT = Instant.parse("2026-10-20T00:00:00Z");
	private static final Instant ENDS_AT = Instant.parse("2026-10-27T00:00:00Z");
	private static final UUID MISSION_ID = UUID.randomUUID();
	private static final String URL = "/api/v1/admin/missions";

	private final AdminMissionService service = mock(AdminMissionService.class);
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new AdminMissionController(service))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new CurrentUserArgumentResolver(
						new UserService(id -> Optional.of(ADMIN).filter(user -> user.getId().equals(id)))))
				.build();
	}

	private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
		return request.header("X-User-ID", ADMIN_ID).header("X-User-Role", "ADMIN");
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return asAdmin(request).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static Mission mission(MissionType type) {
		return new Mission(MISSION_ID, UUID.randomUUID(), ADMIN_ID, "가을 퀴즈", "설명", type, STARTS_AT, ENDS_AT,
				MissionStatus.ACTIVE, null, STARTS_AT, STARTS_AT);
	}

	private static MissionDetail quizDetail() {
		QuizQuestion choice = new QuizQuestion(UUID.randomUUID(), QuizQuestionType.SINGLE_CHOICE, "문제", 1, null,
				List.of(new QuizOption(UUID.randomUUID(), "가", 1, true), new QuizOption(UUID.randomUUID(), "나", 2, false)));
		QuizQuestion shortAnswer = new QuizQuestion(UUID.randomUUID(), QuizQuestionType.SHORT_ANSWER, "주관식", 2,
				"정답", List.of());
		return new MissionDetail(mission(MissionType.QUIZ), List.of(choice, shortAnswer), List.of());
	}

	private static String quizBody(String extra) {
		return "{\"title\":\"가을 퀴즈\",\"description\":\"설명\",\"missionType\":\"QUIZ\","
				+ "\"startsAt\":\"2026-10-20T00:00:00Z\",\"endsAt\":\"2026-10-27T00:00:00Z\"" + extra + ","
				+ "\"questions\":[{\"questionType\":\"SINGLE_CHOICE\",\"questionText\":\"문제\",\"displayOrder\":1,"
				+ "\"options\":[{\"optionText\":\"가\",\"displayOrder\":1,\"isCorrect\":true},"
				+ "{\"optionText\":\"나\",\"displayOrder\":2,\"isCorrect\":false}]},"
				+ "{\"questionType\":\"SHORT_ANSWER\",\"questionText\":\"주관식\",\"displayOrder\":2,"
				+ "\"correctAnswer\":\"정답\"}]}";
	}

	@Test
	@DisplayName("AM01은 문항 없이 미션 요약을 돌려주고 보상 수량은 항상 1이다")
	void returnsMissionList() throws Exception {
		// given
		when(service.findMissions(any(), any(), eq(new PageQuery(1, 20))))
				.thenReturn(new PageResult<>(List.of(mission(MissionType.QUIZ)), 1, 20, 1));
		// when
		// then
		mvc.perform(asAdmin(get(URL)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.items[0].id").value(MISSION_ID.toString()))
				.andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"))
				.andExpect(jsonPath("$.data.items[0].rewardTicketCount").value(1))
				.andExpect(jsonPath("$.data.items[0].rewardPolicyId").isNotEmpty())
				.andExpect(jsonPath("$.data.items[0].questions").doesNotExist());
	}

	@Test
	@DisplayName("AM01은 필터를 검색 조건으로 바꿔 서비스에 넘긴다")
	void passesFilters() throws Exception {
		// given
		when(service.findMissions(any(), any(), any())).thenReturn(new PageResult<>(List.of(), 1, 20, 0));
		// when
		mvc.perform(asAdmin(get(URL).param("missionType", "SURVEY").param("status", "ENDED")
				.param("keyword", " 가을 ").param("from", "2026-10-01T00:00:00Z").param("to", "2026-11-01T00:00:00Z")))
				.andExpect(status().isOk());
		// then
		ArgumentCaptor<MissionSearch> search = ArgumentCaptor.forClass(MissionSearch.class);
		verify(service).findMissions(any(), search.capture(), any());
		assertThat(search.getValue().getMissionType()).isEqualTo(MissionType.SURVEY);
		assertThat(search.getValue().getStatus()).isEqualTo(MissionStatus.ENDED);
		assertThat(search.getValue().getKeyword()).isEqualTo("가을");
		assertThat(search.getValue().getStartsFrom()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
		assertThat(search.getValue().getStartsBefore()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
	}

	@Test
	@DisplayName("AM01은 잘못된 기간·크기·유형을 400으로 거절하고 서비스를 부르지 않는다")
	void rejectsInvalidListParameters() throws Exception {
		mvc.perform(asAdmin(get(URL).param("from", "2026-11-01T00:00:00Z").param("to", "2026-10-01T00:00:00Z")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
		mvc.perform(asAdmin(get(URL).param("size", "101")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
		mvc.perform(asAdmin(get(URL).param("missionType", "UNKNOWN")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-005"));
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("AM02는 정답 정보를 포함한 문항·선택지를 돌려주고 필드는 유형에 맞게만 나온다")
	void returnsMissionDetailWithAnswers() throws Exception {
		// given
		when(service.findMission(any(), eq(MISSION_ID))).thenReturn(quizDetail());
		// when
		// then
		mvc.perform(asAdmin(get(URL + "/{id}", MISSION_ID)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.questions[0].questionType").value("SINGLE_CHOICE"))
				.andExpect(jsonPath("$.data.questions[0].required").doesNotExist())
				.andExpect(jsonPath("$.data.questions[0].options[0].isCorrect").value(true))
				.andExpect(jsonPath("$.data.questions[0].options[1].isCorrect").value(false))
				.andExpect(jsonPath("$.data.questions[1].correctAnswer").value("정답"))
				.andExpect(jsonPath("$.data.questions[1].options").isEmpty());
	}

	@Test
	@DisplayName("AM02는 설문 문항의 필수 여부를 돌려주고 선택지에 정답 필드가 없다")
	void returnsSurveyDetail() throws Exception {
		// given
		SurveyQuestion survey = new SurveyQuestion(UUID.randomUUID(), SurveyQuestionType.SINGLE_CHOICE, "선호", true, 1,
				List.of(new SurveyOption(UUID.randomUUID(), "A", 1)));
		when(service.findMission(any(), eq(MISSION_ID)))
				.thenReturn(new MissionDetail(mission(MissionType.SURVEY), List.of(), List.of(survey)));
		// when
		// then
		mvc.perform(asAdmin(get(URL + "/{id}", MISSION_ID)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.questions[0].required").value(true))
				.andExpect(jsonPath("$.data.questions[0].correctAnswer").doesNotExist())
				.andExpect(jsonPath("$.data.questions[0].options[0].isCorrect").doesNotExist());
	}

	@Test
	@DisplayName("AM02의 없는 미션은 404 MISSION-003, 관리자 아님은 403 MISSION-006이다")
	void mapsDetailErrors() throws Exception {
		when(service.findMission(any(), eq(MISSION_ID)))
				.thenThrow(new MissionException(MissionErrorCode.MISSION_NOT_FOUND))
				.thenThrow(new MissionException(MissionErrorCode.ADMIN_REQUIRED));
		mvc.perform(asAdmin(get(URL + "/{id}", MISSION_ID)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MISSION-003"));
		mvc.perform(asAdmin(get(URL + "/{id}", MISSION_ID)))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MISSION-006"));
	}

	@Test
	@DisplayName("AM03은 요청을 미션 등록 정보로 바꿔 넘기고 201과 생성된 ID를 돌려준다")
	void registersMission() throws Exception {
		// given
		when(service.register(any(), any())).thenReturn(quizDetail());
		// when
		mvc.perform(json(post(URL), quizBody(",\"rewardTicketCount\":1")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.id").value(MISSION_ID.toString()))
				.andExpect(jsonPath("$.data.rewardTicketCount").value(1))
				.andExpect(jsonPath("$.data.questions[0].id").isNotEmpty())
				.andExpect(jsonPath("$.data.questions[0].options[0].id").isNotEmpty());
		// then
		ArgumentCaptor<MissionRegistration> registration = ArgumentCaptor.forClass(MissionRegistration.class);
		verify(service).register(any(), registration.capture());
		MissionRegistration captured = registration.getValue();
		assertThat(captured.getMissionType()).isEqualTo(MissionType.QUIZ);
		assertThat(captured.getStartsAt()).isEqualTo(STARTS_AT);
		assertThat(captured.getQuizQuestions()).hasSize(2);
		assertThat(captured.getQuizQuestions().get(0).getOptions().get(0).isCorrect()).isTrue();
		assertThat(captured.getQuizQuestions().get(1).getCorrectAnswer()).isEqualTo("정답");
		assertThat(captured.getSurveyQuestions()).isEmpty();
	}

	@Test
	@DisplayName("AM03은 rewardTicketCount를 생략해도 등록한다")
	void registersWithoutRewardTicketCount() throws Exception {
		when(service.register(any(), any())).thenReturn(quizDetail());
		mvc.perform(json(post(URL), quizBody(""))).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("AM03은 보상 수량이 1이 아니면 400 MISSION-001이고 서비스를 부르지 않는다")
	void rejectsRewardTicketCountOtherThanOne() throws Exception {
		mvc.perform(json(post(URL), quizBody(",\"rewardTicketCount\":3")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MISSION-001"));
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("AM03은 필수값 누락·빈 문항·200자 초과 제목을 400으로 거절한다")
	void rejectsInvalidBody() throws Exception {
		mvc.perform(json(post(URL), "{}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
		mvc.perform(json(post(URL), "{\"title\":\"t\",\"description\":\"d\",\"missionType\":\"QUIZ\","
				+ "\"startsAt\":\"2026-10-20T00:00:00Z\",\"endsAt\":\"2026-10-27T00:00:00Z\",\"questions\":[]}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
		mvc.perform(json(post(URL), quizBody("").replace("가을 퀴즈", "가".repeat(201))))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("AM03은 알 수 없는 문항 유형이나 유형에 맞지 않는 필드를 400 MISSION-001로 거절한다")
	void rejectsInconsistentQuestions() throws Exception {
		String unknownType = quizBody("").replace("SHORT_ANSWER", "ESSAY");
		String quizWithRequired = quizBody("").replace("\"displayOrder\":2,\"correctAnswer\"",
				"\"displayOrder\":2,\"required\":true,\"correctAnswer\"");
		String surveyWithoutRequired = quizBody("").replace("\"QUIZ\"", "\"SURVEY\"");
		for (String body : List.of(unknownType, quizWithRequired, surveyWithoutRequired)) {
			mvc.perform(json(post(URL), body))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MISSION-001"));
		}
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("AM03의 서비스 거절(구성 오류·관리자 아님)은 400 MISSION-001, 403 MISSION-006으로 내려간다")
	void mapsRegisterErrors() throws Exception {
		when(service.register(any(), any()))
				.thenThrow(new MissionException(MissionErrorCode.INVALID_MISSION_DEFINITION))
				.thenThrow(new MissionException(MissionErrorCode.ADMIN_REQUIRED));
		mvc.perform(json(post(URL), quizBody(""))).andExpect(status().isBadRequest());
		mvc.perform(json(post(URL), quizBody("")))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MISSION-006"));
	}
}
