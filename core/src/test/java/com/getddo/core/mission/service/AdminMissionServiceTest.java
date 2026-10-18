package com.getddo.core.mission.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.getddo.core.audit.domain.AuditLogCommand;
import com.getddo.core.audit.service.AuditLogService;
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
import com.getddo.core.mission.domain.SurveyQuestion;
import com.getddo.core.mission.domain.SurveyQuestionType;
import com.getddo.core.mission.exception.MissionErrorCode;
import com.getddo.core.mission.exception.MissionException;
import com.getddo.core.mission.repository.MissionRepository;
import com.getddo.core.mission.repository.MissionRewardPolicyRepository;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMissionServiceTest {

	private static final Instant STARTS_AT = Instant.parse("2026-10-20T00:00:00Z");
	private static final Instant ENDS_AT = Instant.parse("2026-10-27T00:00:00Z");
	private static final PageQuery FIRST_PAGE = new PageQuery(1, 20);
	private static final User ADMIN = user(UserRole.ADMIN, UserStatus.ACTIVE);

	@Mock
	private MissionRepository missionRepository;
	@Mock
	private MissionRewardPolicyRepository rewardPolicies;
	@Mock
	private AuditLogService auditLogs;

	private static User user(UserRole role, UserStatus status) {
		return new User(UUID.randomUUID(), "사용자", role, status, null, null, null, null, null, null, null);
	}

	private AdminMissionService service() {
		return new AdminMissionService(missionRepository, rewardPolicies, auditLogs);
	}

	private static void assertError(Throwable thrown, MissionErrorCode expected) {
		assertThat(thrown).isInstanceOfSatisfying(MissionException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(expected));
	}

	private static QuizQuestion choice(int order, QuizOption... options) {
		return QuizQuestion.of(QuizQuestionType.SINGLE_CHOICE, "문제", order, null, List.of(options));
	}

	/** 저장소가 ID를 발급한 것처럼 미션에 ID를 붙인다. */
	private static MissionDetail withId(MissionDetail detail) {
		Mission m = detail.getMission();
		Mission saved = new Mission(UUID.randomUUID(), m.getRewardPolicyId(), m.getCreatedBy(), m.getTitle(),
				m.getDescription(), m.getMissionType(), m.getStartsAt(), m.getEndsAt(), m.getStatus(), m.getImageKey(),
				STARTS_AT, STARTS_AT);
		return new MissionDetail(saved, detail.getQuizQuestions(), detail.getSurveyQuestions());
	}

	private static MissionRegistration quiz(QuizQuestion... questions) {
		return new MissionRegistration("퀴즈", "설명", MissionType.QUIZ, STARTS_AT, ENDS_AT, null,
				List.of(questions), List.of());
	}

	@Test
	@DisplayName("관리자가 아니거나 활성이 아니면 MISSION-006으로 거절하고 아무것도 읽거나 쓰지 않는다")
	void rejectsNonAdmins() {
		MissionRegistration registration = quiz(choice(1, QuizOption.of("가", 1, true), QuizOption.of("나", 2, false)));
		for (User caller : new User[] {null, user(UserRole.USER, UserStatus.ACTIVE),
				user(UserRole.ADMIN, UserStatus.INACTIVE)}) {
			assertThatThrownBy(() -> service().findMissions(caller, MissionSearch.all(), FIRST_PAGE))
					.satisfies(e -> assertError(e, MissionErrorCode.ADMIN_REQUIRED));
			assertThatThrownBy(() -> service().findMission(caller, UUID.randomUUID()))
					.satisfies(e -> assertError(e, MissionErrorCode.ADMIN_REQUIRED));
			assertThatThrownBy(() -> service().register(caller, registration))
					.satisfies(e -> assertError(e, MissionErrorCode.ADMIN_REQUIRED));
		}
		verifyNoInteractions(missionRepository, rewardPolicies, auditLogs);
	}

	@Test
	@DisplayName("목록 조회는 조건을 저장소에 그대로 넘긴다")
	void listsMissions() {
		// given
		MissionSearch search = new MissionSearch(MissionType.QUIZ, MissionStatus.ACTIVE, "가을", STARTS_AT, ENDS_AT);
		PageResult<Mission> page = new PageResult<>(List.of(), 1, 20, 0);
		when(missionRepository.search(search, FIRST_PAGE)).thenReturn(page);
		// when
		// then
		assertThat(service().findMissions(ADMIN, search, FIRST_PAGE)).isSameAs(page);
	}

	@Test
	@DisplayName("상세 조회는 없는 미션이면 MISSION-003이다")
	void missingMissionIsNotFound() {
		// given
		UUID missionId = UUID.randomUUID();
		when(missionRepository.findDetailById(missionId)).thenReturn(Optional.empty());
		// when
		// then
		assertThatThrownBy(() -> service().findMission(ADMIN, missionId))
				.satisfies(e -> assertError(e, MissionErrorCode.MISSION_NOT_FOUND));
	}

	@Test
	@DisplayName("등록은 미션마다 보상 정책을 만들어 연결하고, 운영 상태(ACTIVE)로 저장하며 감사 기록을 남긴다")
	void registersQuizMission() {
		// given
		UUID policyId = UUID.randomUUID();
		when(rewardPolicies.createFixedPolicy(ADMIN.getId(), STARTS_AT)).thenReturn(policyId);
		when(missionRepository.insert(any())).thenAnswer(call -> withId(call.getArgument(0)));
		MissionRegistration registration = quiz(
				choice(1, QuizOption.of("가", 1, true), QuizOption.of("나", 2, false)),
				QuizQuestion.of(QuizQuestionType.SHORT_ANSWER, "주관식", 2, "정답", List.of()));
		// when
		MissionDetail saved = service().register(ADMIN, registration);
		// then
		Mission mission = saved.getMission();
		assertThat(mission.getRewardPolicyId()).isEqualTo(policyId);
		assertThat(mission.getCreatedBy()).isEqualTo(ADMIN.getId());
		assertThat(mission.getStatus()).isEqualTo(MissionStatus.ACTIVE);
		assertThat(saved.getQuizQuestions()).hasSize(2);
		ArgumentCaptor<AuditLogCommand> audit = ArgumentCaptor.forClass(AuditLogCommand.class);
		verify(auditLogs).record(audit.capture());
		assertThat(audit.getValue().getAction()).isEqualTo("MISSION_CREATE");
	}

	@Test
	@DisplayName("설문 미션도 등록할 수 있다")
	void registersSurveyMission() {
		// given
		when(rewardPolicies.createFixedPolicy(any(), any())).thenReturn(UUID.randomUUID());
		when(missionRepository.insert(any())).thenAnswer(call -> withId(call.getArgument(0)));
		MissionRegistration registration = new MissionRegistration("설문", "설명", MissionType.SURVEY, STARTS_AT,
				ENDS_AT, "images/a.png", List.of(), List.of(
						SurveyQuestion.of(SurveyQuestionType.FREE_TEXT, "의견", true, 1, List.of())));
		// when
		MissionDetail saved = service().register(ADMIN, registration);
		// then
		assertThat(saved.getSurveyQuestions()).hasSize(1);
		assertThat(saved.getMission().getImageKey()).isEqualTo("images/a.png");
	}

	@Test
	@DisplayName("선택형 퀴즈 문항의 정답이 없거나 둘 이상이거나 선택지가 2개 미만이면 MISSION-001이고 정책도 만들지 않는다")
	void rejectsUnanswerableChoiceQuestions() {
		List<MissionRegistration> invalid = List.of(
				quiz(choice(1, QuizOption.of("가", 1, false), QuizOption.of("나", 2, false))),
				quiz(choice(1, QuizOption.of("가", 1, true), QuizOption.of("나", 2, true))),
				quiz(choice(1, QuizOption.of("가", 1, true))));
		for (MissionRegistration registration : invalid) {
			assertThatThrownBy(() -> service().register(ADMIN, registration))
					.satisfies(e -> assertError(e, MissionErrorCode.INVALID_MISSION_DEFINITION));
		}
		verifyNoInteractions(rewardPolicies, auditLogs);
		verify(missionRepository, never()).insert(any());
	}

	@Test
	@DisplayName("제목·설명·기간·이미지 경로가 규칙에 어긋나면 등록 정보를 만드는 단계에서 MISSION-001로 거절한다")
	void rejectsInvalidRegistrationAtCreation() {
		List<QuizQuestion> questions = List.of(choice(1, QuizOption.of("가", 1, true), QuizOption.of("나", 2, false)));
		assertThatThrownBy(() -> new MissionRegistration("퀴즈", "설명", MissionType.QUIZ, ENDS_AT, STARTS_AT, null,
				questions, List.of())).satisfies(e -> assertError(e, MissionErrorCode.INVALID_MISSION_DEFINITION));
		assertThatThrownBy(() -> new MissionRegistration("퀴즈", "설명", MissionType.QUIZ, STARTS_AT, STARTS_AT, null,
				questions, List.of())).satisfies(e -> assertError(e, MissionErrorCode.INVALID_MISSION_DEFINITION));
		assertThatThrownBy(() -> new MissionRegistration(" ", "설명", MissionType.QUIZ, STARTS_AT, ENDS_AT, null,
				questions, List.of())).satisfies(e -> assertError(e, MissionErrorCode.INVALID_MISSION_DEFINITION));
		assertThatThrownBy(() -> new MissionRegistration("가".repeat(201), "설명", MissionType.QUIZ, STARTS_AT, ENDS_AT,
				null, questions, List.of())).satisfies(e -> assertError(e, MissionErrorCode.INVALID_MISSION_DEFINITION));
		assertThatThrownBy(() -> new MissionRegistration("퀴즈", "", MissionType.QUIZ, STARTS_AT, ENDS_AT, null,
				questions, List.of())).satisfies(e -> assertError(e, MissionErrorCode.INVALID_MISSION_DEFINITION));
		assertThatThrownBy(() -> new MissionRegistration("퀴즈", "설명", MissionType.QUIZ, STARTS_AT, ENDS_AT,
				"k".repeat(501), questions, List.of()))
				.satisfies(e -> assertError(e, MissionErrorCode.INVALID_MISSION_DEFINITION));
	}

	@Test
	@DisplayName("제목 200자와 이미지 경로 500자는 정확히 허용한다")
	void acceptsBoundaryLengths() {
		List<QuizQuestion> questions = List.of(choice(1, QuizOption.of("가", 1, true), QuizOption.of("나", 2, false)));
		MissionRegistration registration = new MissionRegistration("가".repeat(200), "설명", MissionType.QUIZ,
				STARTS_AT, ENDS_AT, "k".repeat(500), questions, List.of());
		assertThat(registration.getTitle()).hasSize(200);
		assertThat(registration.getImageKey()).hasSize(500);
	}
}
