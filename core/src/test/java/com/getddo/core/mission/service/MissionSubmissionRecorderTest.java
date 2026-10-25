package com.getddo.core.mission.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.getddo.core.mission.domain.Mission;
import com.getddo.core.mission.domain.MissionAnswerInput;
import com.getddo.core.mission.domain.MissionDetail;
import com.getddo.core.mission.domain.MissionRewardClaim;
import com.getddo.core.mission.domain.MissionStatus;
import com.getddo.core.mission.domain.MissionSubmission;
import com.getddo.core.mission.domain.MissionSubmissionCommand;
import com.getddo.core.mission.domain.MissionSubmissionResult;
import com.getddo.core.mission.domain.MissionType;
import com.getddo.core.mission.domain.QuizAnswer;
import com.getddo.core.mission.domain.QuizOption;
import com.getddo.core.mission.domain.QuizQuestion;
import com.getddo.core.mission.domain.QuizQuestionProgress;
import com.getddo.core.mission.domain.QuizQuestionType;
import com.getddo.core.mission.domain.SurveyAnswer;
import com.getddo.core.mission.domain.SurveyOption;
import com.getddo.core.mission.domain.SurveyQuestion;
import com.getddo.core.mission.domain.SurveyQuestionType;
import com.getddo.core.mission.exception.MissionErrorCode;
import com.getddo.core.mission.exception.MissionException;
import com.getddo.core.mission.repository.MissionRepository;
import com.getddo.core.mission.repository.MissionRewardClaimRepository;
import com.getddo.core.mission.repository.MissionSubmissionRepository;
import com.getddo.core.mission.repository.QuizQuestionProgressRepository;
import com.getddo.core.ticket.domain.GrantCommand;
import com.getddo.core.ticket.domain.GrantResult;
import com.getddo.core.ticket.domain.GrantSourceType;
import com.getddo.core.ticket.domain.TicketGrade;
import com.getddo.core.ticket.service.TicketGrantService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MissionSubmissionRecorderTest {

	private static final String HASH = "0".repeat(64);

	private static final Instant NOW = Instant.parse("2026-10-13T03:00:00Z");
	private static final Instant STARTS = Instant.parse("2026-10-01T00:00:00Z");
	private static final Instant ENDS = Instant.parse("2026-11-01T00:00:00Z");
	private static final Instant GRANTED_AT = Instant.parse("2026-10-13T03:00:00.000001Z");
	private static final Instant EXPIRES_AT = Instant.parse("2026-10-31T15:00:00Z");

	private final UUID userId = UUID.randomUUID();
	private final UUID missionId = UUID.randomUUID();
	private final UUID submissionId = UUID.randomUUID();
	private final UUID claimId = UUID.randomUUID();
	private final UUID q1 = UUID.randomUUID();
	private final UUID q2 = UUID.randomUUID();
	private final UUID q1Ok = UUID.randomUUID();
	private final UUID q1No = UUID.randomUUID();

	@Mock
	private MissionRepository missionRepository;
	@Mock
	private MissionSubmissionRepository submissionRepository;
	@Mock
	private QuizQuestionProgressRepository progressRepository;
	@Mock
	private MissionRewardClaimRepository claimRepository;
	@Mock
	private TicketGrantService grantService;

	private MissionSubmissionRecorder recorder;

	@BeforeEach
	void setUp() {
		recorder = new MissionSubmissionRecorder(missionRepository, submissionRepository, progressRepository,
				claimRepository, grantService, new MissionSubmissionResultReader(claimRepository, grantService));
	}

	private Mission mission(MissionType type, Instant startsAt, Instant endsAt, MissionStatus status) {
		return new Mission(missionId, UUID.randomUUID(), UUID.randomUUID(), "가을 미션", "설명", type, startsAt, endsAt, status,
				null, STARTS, STARTS);
	}

	/** 단일 선택 한 문항과 단답 한 문항짜리 퀴즈. */
	private MissionDetail quiz() {
		QuizQuestion choice = new QuizQuestion(q1, QuizQuestionType.SINGLE_CHOICE, "문제1", 1, null,
				List.of(new QuizOption(q1Ok, "정답", 1, true), new QuizOption(q1No, "오답", 2, false)));
		QuizQuestion shortAnswer = new QuizQuestion(q2, QuizQuestionType.SHORT_ANSWER, "문제2", 2, "겟또", List.of());
		return new MissionDetail(mission(MissionType.QUIZ, STARTS, ENDS, MissionStatus.ACTIVE),
				List.of(choice, shortAnswer), List.of());
	}

	private MissionDetail survey() {
		SurveyQuestion required = new SurveyQuestion(q1, SurveyQuestionType.SINGLE_CHOICE, "필수", true, 1,
				List.of(new SurveyOption(q1Ok, "좋음", 1)));
		return new MissionDetail(mission(MissionType.SURVEY, STARTS, ENDS, MissionStatus.ACTIVE), List.of(),
				List.of(required));
	}

	private MissionSubmissionCommand command(MissionAnswerInput... answers) {
		return new MissionSubmissionCommand(userId, missionId, submissionId, List.of(answers));
	}

	private static MissionAnswerInput option(UUID question, UUID option) {
		return new MissionAnswerInput(question, option, null);
	}

	private static MissionAnswerInput text(UUID question, String text) {
		return new MissionAnswerInput(question, null, text);
	}

	/** 저장소 insert가 답변 ID를 채워 돌려주는 동작을 흉내 낸다. */
	private void stubInsertEchoWithAnswerIds() {
		when(submissionRepository.insert(any())).thenAnswer(invocation -> {
			MissionSubmission given = invocation.getArgument(0);
			return new MissionSubmission(given.getId(), given.getMissionId(), given.getRewardPolicyId(), given.getUserId(),
					given.isCompleted(), given.getCreatedAt(),
					given.getRequestHash(), given.getQuizAnswers().stream()
							.map(a -> new QuizAnswer(UUID.randomUUID(), a.getQuestionId(), a.getSelectedOptionId(),
									a.getAnswerText(), a.isCorrect()))
							.toList(),
					given.getSurveyAnswers().stream()
							.map(a -> new SurveyAnswer(UUID.randomUUID(), a.getQuestionId(), a.getSelectedOptionId(),
									a.getAnswerText()))
							.toList());
		});
	}

	private void stubClaimAndGrant() {
		when(claimRepository.insert(any())).thenAnswer(invocation -> {
			MissionRewardClaim given = invocation.getArgument(0);
			return new MissionRewardClaim(claimId, given.getRewardPolicyId(), given.getMissionId(),
					given.getMissionSubmissionId(), given.getUserId(), given.getSourceKey(), given.getTicketCount(), NOW);
		});
		when(grantService.grant(any())).thenReturn(new GrantResult(1, TicketGrade.SILVER, GRANTED_AT, EXPIRES_AT, false));
	}

	@Test
	@DisplayName("퀴즈의 모든 문항에 정답이면 제출·정답 진행·보상 청구를 저장하고 응모권을 지급해 보상을 돌려준다")
	void completesQuizAndGrantsReward() {
		// given
		when(submissionRepository.findById(submissionId)).thenReturn(Optional.empty());
		when(missionRepository.findDetailById(missionId)).thenReturn(Optional.of(quiz()));
		when(submissionRepository.findCompleted(userId, missionId)).thenReturn(Optional.empty());
		when(progressRepository.findCorrectQuestionIds(any(), any())).thenReturn(Set.of());
		stubInsertEchoWithAnswerIds();
		stubClaimAndGrant();
		// when
		MissionSubmissionResult result = recorder.record(command(option(q1, q1Ok), text(q2, " 겟또 ")), NOW);
		// then
		assertThat(result.isCompleted()).isTrue();
		assertThat(result.isCreated()).isTrue();
		assertThat(result.getSubmissionId()).isEqualTo(submissionId);
		assertThat(result.getReceivedAt()).isEqualTo(NOW);
		assertThat(result.getReward().getClaimId()).isEqualTo(claimId);
		assertThat(result.getReward().getTicketCount()).isEqualTo(1);
		assertThat(result.getReward().getGrade()).isEqualTo(TicketGrade.SILVER);
		assertThat(result.getReward().getExpiresAt()).isEqualTo(EXPIRES_AT);
		ArgumentCaptor<MissionSubmission> inserted = ArgumentCaptor.forClass(MissionSubmission.class);
		verify(submissionRepository).insert(inserted.capture());
		assertThat(inserted.getValue().getRequestHash())
				.isEqualTo(command(option(q1, q1Ok), text(q2, " 겟또 ")).getRequestHash());
		ArgumentCaptor<QuizQuestionProgress> progress = ArgumentCaptor.forClass(QuizQuestionProgress.class);
		verify(progressRepository, org.mockito.Mockito.times(2)).insert(progress.capture());
		assertThat(progress.getAllValues()).extracting(QuizQuestionProgress::getQuestionId)
				.containsExactlyInAnyOrder(q1, q2).isSorted();
		ArgumentCaptor<GrantCommand> grant = ArgumentCaptor.forClass(GrantCommand.class);
		verify(grantService).grant(grant.capture());
		assertThat(grant.getValue().getUserId()).isEqualTo(userId);
		assertThat(grant.getValue().getQuantity()).isEqualTo(1);
		assertThat(grant.getValue().getReason()).isEqualTo("가을 미션");
		assertThat(grant.getValue().getSource().getType()).isEqualTo(GrantSourceType.MISSION);
	}

	@Test
	@DisplayName("오답이 있으면 미완료 제출로 저장하고 맞힌 문항만 정답 진행으로 남기며 보상 청구와 지급은 하지 않는다")
	void keepsIncompleteWhenWrongAnswer() {
		// given
		when(submissionRepository.findById(submissionId)).thenReturn(Optional.empty());
		when(missionRepository.findDetailById(missionId)).thenReturn(Optional.of(quiz()));
		when(submissionRepository.findCompleted(userId, missionId)).thenReturn(Optional.empty());
		when(progressRepository.findCorrectQuestionIds(any(), any())).thenReturn(Set.of());
		stubInsertEchoWithAnswerIds();
		// when
		MissionSubmissionResult result = recorder.record(command(option(q1, q1No), text(q2, "겟또")), NOW);
		// then
		assertThat(result.isCompleted()).isFalse();
		assertThat(result.getReward()).isNull();
		assertThat(result.isCreated()).isTrue();
		ArgumentCaptor<QuizQuestionProgress> progress = ArgumentCaptor.forClass(QuizQuestionProgress.class);
		verify(progressRepository).insert(progress.capture());
		assertThat(progress.getValue().getQuestionId()).isEqualTo(q2);
		verifyNoInteractions(claimRepository, grantService);
	}

	@Test
	@DisplayName("퀴즈는 사용자 행을 먼저 잠근 뒤에 완료 여부와 정답 진행을 조회한다")
	void locksUserBeforeReadingQuizProgress() {
		// given
		when(submissionRepository.findById(submissionId)).thenReturn(Optional.empty());
		when(missionRepository.findDetailById(missionId)).thenReturn(Optional.of(quiz()));
		when(submissionRepository.findCompleted(userId, missionId)).thenReturn(Optional.empty());
		when(progressRepository.findCorrectQuestionIds(any(), any())).thenReturn(Set.of());
		stubInsertEchoWithAnswerIds();
		// when
		recorder.record(command(option(q1, q1No)), NOW);
		// then
		org.mockito.InOrder order = org.mockito.Mockito.inOrder(submissionRepository, progressRepository);
		order.verify(submissionRepository).lockUser(userId);
		order.verify(submissionRepository).findCompleted(userId, missionId);
		order.verify(progressRepository).findCorrectQuestionIds(any(), any());
	}

	@Test
	@DisplayName("넘겨받은 서버 도착 시각이 운영 기간 안이면 접수하고 그 시각을 제출 시각으로 저장한다")
	void usesTheGivenArrivalTimeForThePeriodCheckAndSubmissionTime() {
		// given: 도착 시각은 종료 직전이다. 잠금을 기다린 처리 시각은 판정에 쓰지 않는다
		Instant arrival = ENDS.minusSeconds(1);
		when(submissionRepository.findById(submissionId)).thenReturn(Optional.empty());
		when(missionRepository.findDetailById(missionId)).thenReturn(Optional.of(quiz()));
		when(submissionRepository.findCompleted(userId, missionId)).thenReturn(Optional.empty());
		when(progressRepository.findCorrectQuestionIds(any(), any())).thenReturn(Set.of());
		stubInsertEchoWithAnswerIds();
		// when
		MissionSubmissionResult result = recorder.record(command(option(q1, q1No)), arrival);
		// then
		assertThat(result.isCreated()).isTrue();
		assertThat(result.getReceivedAt()).isEqualTo(arrival);
		ArgumentCaptor<MissionSubmission> inserted = ArgumentCaptor.forClass(MissionSubmission.class);
		verify(submissionRepository).insert(inserted.capture());
		assertThat(inserted.getValue().getCreatedAt()).isEqualTo(arrival);
	}

	@Test
	@DisplayName("설문은 문항을 나눠 보낼 수 없어 사용자 행을 잠그지 않는다")
	void doesNotLockUserForSurvey() {
		// given
		when(submissionRepository.findById(submissionId)).thenReturn(Optional.empty());
		when(missionRepository.findDetailById(missionId)).thenReturn(Optional.of(survey()));
		when(submissionRepository.findCompleted(userId, missionId)).thenReturn(Optional.empty());
		stubInsertEchoWithAnswerIds();
		stubClaimAndGrant();
		// when
		recorder.record(command(option(q1, q1Ok)), NOW);
		// then
		verify(submissionRepository, never()).lockUser(any());
	}

	@Test
	@DisplayName("잠금을 기다리는 동안 같은 제출 ID가 먼저 확정됐으면 새로 저장하지 않고 기존 결과를 돌려준다")
	void replaysWhenSameSubmissionCommittedWhileWaitingForLock() {
		// given: 처음 확인에서는 없었고, 잠금을 얻은 뒤에는 먼저 확정된 같은 제출이 보인다
		MissionSubmission stored = new MissionSubmission(submissionId, missionId, UUID.randomUUID(), userId, false,
				NOW.minusSeconds(1), command(option(q1, q1No)).getRequestHash(),
				List.of(new QuizAnswer(UUID.randomUUID(), q1, q1No, null, false)), List.of());
		when(submissionRepository.findById(submissionId)).thenReturn(Optional.empty(), Optional.of(stored));
		when(missionRepository.findDetailById(missionId)).thenReturn(Optional.of(quiz()));
		// when
		MissionSubmissionResult replayed = recorder.record(command(option(q1, q1No)), NOW);
		// then
		assertThat(replayed.isCreated()).isFalse();
		assertThat(replayed.getSubmissionId()).isEqualTo(submissionId);
		verify(submissionRepository).lockUser(userId);
		verify(submissionRepository, never()).insert(any());
		verifyNoInteractions(progressRepository, claimRepository, grantService);
	}

	@Test
	@DisplayName("이전 제출에서 맞힌 문항은 누적되어 남은 문항만 맞히면 완료한다")
	void completesByAccumulatingPreviousCorrectQuestions() {
		// given
		when(submissionRepository.findById(submissionId)).thenReturn(Optional.empty());
		when(missionRepository.findDetailById(missionId)).thenReturn(Optional.of(quiz()));
		when(submissionRepository.findCompleted(userId, missionId)).thenReturn(Optional.empty());
		when(progressRepository.findCorrectQuestionIds(any(), any())).thenReturn(Set.of(q1));
		stubInsertEchoWithAnswerIds();
		stubClaimAndGrant();
		// when
		MissionSubmissionResult result = recorder.record(command(text(q2, "겟또")), NOW);
		// then
		assertThat(result.isCompleted()).isTrue();
		ArgumentCaptor<QuizQuestionProgress> progress = ArgumentCaptor.forClass(QuizQuestionProgress.class);
		verify(progressRepository).insert(progress.capture());
		assertThat(progress.getValue().getQuestionId()).isEqualTo(q2);
	}

	@Test
	@DisplayName("설문은 필수 문항을 모두 채우면 완료하고 정답 진행 없이 청구·지급한다")
	void completesSurvey() {
		// given
		when(submissionRepository.findById(submissionId)).thenReturn(Optional.empty());
		when(missionRepository.findDetailById(missionId)).thenReturn(Optional.of(survey()));
		when(submissionRepository.findCompleted(userId, missionId)).thenReturn(Optional.empty());
		stubInsertEchoWithAnswerIds();
		stubClaimAndGrant();
		// when
		MissionSubmissionResult result = recorder.record(command(option(q1, q1Ok)), NOW);
		// then
		assertThat(result.isCompleted()).isTrue();
		assertThat(result.getReward()).isNotNull();
		verifyNoInteractions(progressRepository);
	}

	@Test
	@DisplayName("이미 완료한 미션을 다시 제출하면 새로 저장하지 않고 기존 완료 결과와 보상을 돌려준다")
	void returnsExistingCompletionOnResubmission() {
		// given
		UUID earlierId = UUID.randomUUID();
		MissionSubmission earlier = new MissionSubmission(earlierId, missionId, UUID.randomUUID(), userId, true,
				NOW.minusSeconds(60), HASH, List.of(), List.of(new SurveyAnswer(UUID.randomUUID(), q1, q1Ok, null)));
		UUID claimId = UUID.randomUUID();
		when(submissionRepository.findById(submissionId)).thenReturn(Optional.empty());
		when(missionRepository.findDetailById(missionId)).thenReturn(Optional.of(survey()));
		when(submissionRepository.findCompleted(userId, missionId)).thenReturn(Optional.of(earlier));
		when(claimRepository.findByUserAndMission(userId, missionId)).thenReturn(Optional.of(new MissionRewardClaim(claimId,
				earlier.getRewardPolicyId(), missionId, earlierId, userId, "mission:" + missionId, 1, NOW)));
		when(grantService.findGrant(any())).thenReturn(Optional.of(new GrantResult(1, TicketGrade.BRONZE, GRANTED_AT,
				EXPIRES_AT, true)));
		// when
		MissionSubmissionResult result = recorder.record(command(option(q1, q1Ok)), NOW);
		// then
		assertThat(result.isCreated()).isFalse();
		assertThat(result.isCompleted()).isTrue();
		assertThat(result.getSubmissionId()).isEqualTo(earlierId);
		assertThat(result.getReward().getGrade()).isEqualTo(TicketGrade.BRONZE);
		verify(submissionRepository, never()).insert(any());
		verify(grantService, never()).grant(any());
	}

	@Test
	@DisplayName("같은 제출 ID의 같은 요청은 마감 뒤에도 기존 결과를 돌려주고 다른 사용자·미션·답변이면 충돌이다")
	void replaysSameKeyAndRejectsDifferentBody() {
		// given
		MissionSubmission stored = new MissionSubmission(submissionId, missionId, UUID.randomUUID(), userId, false,
				NOW.minusSeconds(60), command(option(q1, q1No)).getRequestHash(),
				List.of(new QuizAnswer(UUID.randomUUID(), q1, q1No, null, false)), List.of());
		when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(stored));
		// when
		MissionSubmissionResult replayed = recorder.record(command(option(q1, q1No)), NOW);
		// then
		assertThat(replayed.isCreated()).isFalse();
		assertThat(replayed.isCompleted()).isFalse();
		assertThat(replayed.getReceivedAt()).isEqualTo(NOW.minusSeconds(60));
		assertConflict(new MissionSubmissionCommand(UUID.randomUUID(), missionId, submissionId, List.of(option(q1, q1No))));
		assertConflict(new MissionSubmissionCommand(userId, UUID.randomUUID(), submissionId, List.of(option(q1, q1No))));
		assertConflict(command(option(q1, q1Ok)));
		assertConflict(command(text(q2, "겟또")));
		verifyNoInteractions(missionRepository, progressRepository, claimRepository, grantService);
	}

	@Test
	@DisplayName("같은 제출 ID로 처음 요청에 없던 답변을 더하면 이미 맞힌 문항이어도 다른 본문이라 409이고, 처음 요청 그대로면 저장하지 않은 답변이 있어도 같은 요청이다")
	void comparesAgainstTheOriginalRequest() {
		// given: 처음 요청은 이미 정답으로 완료한 q1의 답변을 포함했고 그 답변은 저장되지 않았다
		MissionSubmissionCommand original = command(option(q1, q1Ok), text(q2, "틀림"));
		MissionSubmission quizStored = new MissionSubmission(submissionId, missionId, UUID.randomUUID(), userId, false,
				NOW.minusSeconds(60), original.getRequestHash(),
				List.of(new QuizAnswer(UUID.randomUUID(), q2, null, "틀림", false)), List.of());
		MissionSubmission surveyStored = new MissionSubmission(submissionId, missionId, UUID.randomUUID(), userId, true,
				NOW.minusSeconds(60), command(option(q1, q1Ok)).getRequestHash(), List.of(),
				List.of(new SurveyAnswer(UUID.randomUUID(), q1, q1Ok, null)));
		// when
		// then
		when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(quizStored));
		MissionSubmissionResult replayed = recorder.record(original, NOW);
		assertThat(replayed.isCreated()).isFalse();
		assertThat(replayed.isCompleted()).isFalse();
		assertConflict(command(option(q1, q1Ok), text(q2, "틀림"), text(UUID.randomUUID(), "더함")));
		assertConflict(command(text(q2, "틀림")));
		when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(surveyStored));
		assertConflict(command(option(q1, q1Ok), text(q2, "의견")));
		verifyNoInteractions(missionRepository, progressRepository);
	}

	@Test
	@DisplayName("같은 제출 ID의 재요청에 같은 문항이 두 번 있으면 저장된 답변과 같아도 신규 제출처럼 답변 형식 오류로 거절한다")
	void rejectsReplayWithDuplicateQuestions() {
		// given
		MissionSubmission stored = new MissionSubmission(submissionId, missionId, UUID.randomUUID(), userId, false,
				NOW.minusSeconds(60), HASH, List.of(new QuizAnswer(UUID.randomUUID(), q1, q1No, null, false)), List.of());
		when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(stored));
		// when
		// then
		assertThatThrownBy(() -> recorder.record(command(option(q1, q1No), option(q1, q1Ok)), NOW))
				.isInstanceOfSatisfying(MissionException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(MissionErrorCode.INVALID_MISSION_ANSWER));
		verifyNoInteractions(missionRepository, progressRepository, claimRepository, grantService);
	}

	@Test
	@DisplayName("없는 미션은 404, 운영 기간이 아니거나 종료된 미션은 409로 거절하고 아무것도 저장하지 않는다")
	void rejectsMissingAndClosedMission() {
		// given
		when(submissionRepository.findById(submissionId)).thenReturn(Optional.empty());
		when(submissionRepository.findCompleted(userId, missionId)).thenReturn(Optional.empty());
		when(missionRepository.findDetailById(missionId)).thenReturn(Optional.empty());
		// when
		// then
		assertThatThrownBy(() -> recorder.record(command(option(q1, q1Ok)), NOW)).isInstanceOfSatisfying(MissionException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(MissionErrorCode.MISSION_NOT_FOUND));

		MissionDetail notStarted = new MissionDetail(mission(MissionType.SURVEY, NOW.plusSeconds(1), ENDS, MissionStatus.ACTIVE),
				List.of(), survey().getSurveyQuestions());
		when(missionRepository.findDetailById(missionId)).thenReturn(Optional.of(notStarted));
		assertNotOpen();
		MissionDetail endedExactly = new MissionDetail(mission(MissionType.SURVEY, STARTS, NOW, MissionStatus.ACTIVE),
				List.of(), survey().getSurveyQuestions());
		when(missionRepository.findDetailById(missionId)).thenReturn(Optional.of(endedExactly));
		assertNotOpen();
		MissionDetail endedStatus = new MissionDetail(mission(MissionType.SURVEY, STARTS, ENDS, MissionStatus.ENDED),
				List.of(), survey().getSurveyQuestions());
		when(missionRepository.findDetailById(missionId)).thenReturn(Optional.of(endedStatus));
		assertNotOpen();
		verify(submissionRepository, never()).insert(any());
	}

	private void assertNotOpen() {
		assertThatThrownBy(() -> recorder.record(command(option(q1, q1Ok)), NOW)).isInstanceOfSatisfying(MissionException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(MissionErrorCode.MISSION_NOT_OPEN));
	}

	private void assertConflict(MissionSubmissionCommand command) {
		assertThatThrownBy(() -> recorder.record(command, NOW)).isInstanceOfSatisfying(MissionException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(MissionErrorCode.IDEMPOTENCY_CONFLICT));
	}
}
