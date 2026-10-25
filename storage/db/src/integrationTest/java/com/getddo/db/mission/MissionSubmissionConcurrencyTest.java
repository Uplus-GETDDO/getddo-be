package com.getddo.db.mission;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.mysql.MySQLContainer;

import com.getddo.core.mission.domain.MissionAnswerInput;
import com.getddo.core.mission.domain.MissionDetail;
import com.getddo.core.mission.domain.MissionSubmissionCommand;
import com.getddo.core.mission.domain.MissionSubmissionResult;
import com.getddo.core.mission.domain.QuizQuestion;
import com.getddo.core.mission.domain.SurveyQuestion;
import com.getddo.core.mission.exception.MissionErrorCode;
import com.getddo.core.mission.exception.MissionException;
import com.getddo.core.mission.repository.MissionSubmissionRepository;
import com.getddo.core.mission.service.MissionSubmissionService;
import com.getddo.db.ticket.ConcurrentTasks;
import com.getddo.db.ticket.LockWaitProbe;

import static com.getddo.db.ticket.TicketGrantSeeds.bytes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 사용자의 미션 제출이 동시에 와도 완료·보상이 한 번만 반영되는지 실제 MySQL 잠금으로 검증한다.
 *
 * <p>모든 요청을 같은 시작 신호로 동시에 출발시키고 고정 sleep을 쓰지 않는다. 늦은 요청은 유일 제약 위반으로 롤백된 뒤 서비스가
 * 새 트랜잭션에서 다시 처리하며, 먼저 확정된 결과를 보고 끝난다.</p>
 */
class MissionSubmissionConcurrencyTest extends MissionIntegrationTestSupport {

	private static final long WAIT_SECONDS = 30;
	private static final int REPEATS = 15;

	@Autowired
	private MissionSubmissionService submissionService;
	@Autowired
	private MissionSubmissionRepository submissionRepository;
	@Autowired
	private MySQLContainer mysql;

	private Callable<MissionSubmissionResult> submit(UUID user, UUID missionId, UUID submissionId,
			MissionAnswerInput... answers) {
		return () -> submissionService.submit(new MissionSubmissionCommand(user, missionId, submissionId, List.of(answers)));
	}

	private static MissionAnswerInput surveyAnswer(MissionDetail survey) {
		SurveyQuestion required = survey.getSurveyQuestions().get(0);
		return new MissionAnswerInput(required.getId(), required.getOptions().get(0).getId(), null);
	}

	private static MissionAnswerInput[] allCorrect(MissionDetail quiz) {
		List<QuizQuestion> q = quiz.getQuizQuestions();
		return new MissionAnswerInput[] {
				new MissionAnswerInput(q.get(0).getId(), q.get(0).getOptions().get(0).getId(), null),
				new MissionAnswerInput(q.get(1).getId(), null, "겟또"),
				new MissionAnswerInput(q.get(2).getId(), q.get(2).getOptions().get(0).getId(), null)};
	}

	private long count(String table, UUID user) {
		return count("select count(*) from " + table + " where user_id = ?", bytes(user));
	}

	@RepeatedTest(REPEATS)
	@DisplayName("같은 사용자가 다른 제출 ID로 설문을 동시에 완료해도 보상 청구와 응모권 지급은 한 번이고 두 요청 모두 완료 결과를 받는다")
	void sameUserTwoKeysCompleteOnce() throws Exception {
		// given
		MissionDetail survey = saveSurveyMission("설문 미션");
		UUID missionId = survey.getMission().getId();
		MissionAnswerInput answer = surveyAnswer(survey);
		// when
		List<Object> outcomes = runSimultaneously(List.of(
				submit(userId, missionId, UUID.randomUUID(), answer),
				submit(userId, missionId, UUID.randomUUID(), answer)));
		// then
		assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(MissionSubmissionResult.class));
		List<MissionSubmissionResult> results = outcomes.stream().map(MissionSubmissionResult.class::cast).toList();
		assertThat(results).allMatch(MissionSubmissionResult::isCompleted).allMatch(r -> r.getReward() != null);
		assertThat(results.stream().filter(MissionSubmissionResult::isCreated)).hasSize(1);
		assertThat(results.get(0).getSubmissionId()).isEqualTo(results.get(1).getSubmissionId());
		assertThat(count("mission_reward_claims", userId)).isEqualTo(1);
		assertThat(count("tickets", userId)).isEqualTo(1);
		assertThat(count("mission_submissions", userId)).isEqualTo(1);
	}

	@RepeatedTest(REPEATS)
	@DisplayName("같은 제출 ID의 요청이 동시에 와도 제출은 한 건만 저장되고 같은 결과를 돌려준다")
	void sameSubmissionIdTwiceStoresOnce() throws Exception {
		// given
		MissionDetail survey = saveSurveyMission("설문 미션");
		UUID missionId = survey.getMission().getId();
		UUID submissionId = UUID.randomUUID();
		MissionAnswerInput answer = surveyAnswer(survey);
		// when
		List<Object> outcomes = runSimultaneously(List.of(
				submit(userId, missionId, submissionId, answer),
				submit(userId, missionId, submissionId, answer)));
		// then
		assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(MissionSubmissionResult.class));
		List<MissionSubmissionResult> results = outcomes.stream().map(MissionSubmissionResult.class::cast).toList();
		assertThat(results).allMatch(r -> r.getSubmissionId().equals(submissionId)).allMatch(MissionSubmissionResult::isCompleted);
		assertThat(results.stream().filter(MissionSubmissionResult::isCreated)).hasSize(1);
		assertThat(count("mission_submissions", userId)).isEqualTo(1);
		assertThat(count("tickets", userId)).isEqualTo(1);
	}

	@RepeatedTest(REPEATS)
	@DisplayName("같은 사용자가 퀴즈 전 문항에 정답인 제출을 동시에 두 번 보내도 정답 진행은 문항마다 한 번이고 완료와 지급도 한 번이다")
	void sameUserQuizAllCorrectTwiceCompletesOnce() throws Exception {
		// given
		MissionDetail quiz = saveQuizMission("퀴즈 미션");
		UUID missionId = quiz.getMission().getId();
		MissionAnswerInput[] answers = allCorrect(quiz);
		// when
		List<Object> outcomes = runSimultaneously(List.of(
				submit(userId, missionId, UUID.randomUUID(), answers),
				submit(userId, missionId, UUID.randomUUID(), answers)));
		// then
		assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(MissionSubmissionResult.class));
		List<MissionSubmissionResult> results = outcomes.stream().map(MissionSubmissionResult.class::cast).toList();
		assertThat(results).allMatch(MissionSubmissionResult::isCompleted);
		assertThat(results.stream().filter(MissionSubmissionResult::isCreated)).hasSize(1);
		assertThat(count("quiz_question_progress", userId)).isEqualTo(3);
		assertThat(count("mission_reward_claims", userId)).isEqualTo(1);
		assertThat(count("tickets", userId)).isEqualTo(1);
		assertThat(count("mission_submissions", userId)).isEqualTo(1);
	}

	@RepeatedTest(REPEATS)
	@DisplayName("같은 사용자가 같은 문항에 정답을 다른 제출 ID로 동시에 보내면 한 건만 접수하고 나머지는 처리할 답변이 없어 400이다")
	void sameQuestionCorrectTwiceKeepsOneProgress() throws Exception {
		// given
		MissionDetail quiz = saveQuizMission("퀴즈 미션");
		UUID missionId = quiz.getMission().getId();
		QuizQuestion first = quiz.getQuizQuestions().get(0);
		MissionAnswerInput answer = new MissionAnswerInput(first.getId(), first.getOptions().get(0).getId(), null);
		// when
		List<Object> outcomes = runSimultaneously(List.of(
				submit(userId, missionId, UUID.randomUUID(), answer),
				submit(userId, missionId, UUID.randomUUID(), answer)));
		// then
		assertThat(outcomes.stream().filter(MissionSubmissionResult.class::isInstance)).hasSize(1);
		assertThat(outcomes.stream().filter(Throwable.class::isInstance)).hasSize(1).allSatisfy(failure ->
				assertThat(failure).isInstanceOfSatisfying(MissionException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(MissionErrorCode.INVALID_MISSION_ANSWER)));
		assertThat(count("quiz_question_progress", userId)).isEqualTo(1);
		assertThat(count("mission_submissions", userId)).isEqualTo(1);
		assertThat(count("mission_reward_claims", userId)).isZero();
	}

	@RepeatedTest(REPEATS)
	@DisplayName("서로 다른 사용자 여러 명이 같은 미션을 동시에 완료해도 교착 없이 모두 한 번씩 보상받는다")
	void manyUsersCompleteSameMissionWithoutDeadlock() throws Exception {
		// given
		MissionDetail quiz = saveQuizMission("퀴즈 미션");
		UUID missionId = quiz.getMission().getId();
		MissionAnswerInput[] answers = allCorrect(quiz);
		List<UUID> users = new ArrayList<>();
		List<Callable<MissionSubmissionResult>> works = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			UUID user = seeds.user();
			users.add(user);
			works.add(submit(user, missionId, UUID.randomUUID(), answers));
		}
		// when
		List<Object> outcomes = runSimultaneously(works);
		// then
		assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(MissionSubmissionResult.class));
		for (UUID user : users) {
			assertThat(count("mission_reward_claims", user)).isEqualTo(1);
			assertThat(count("tickets", user)).isEqualTo(1);
			assertThat(count("quiz_question_progress", user)).isEqualTo(3);
		}
	}

	@RepeatedTest(REPEATS)
	@DisplayName("같은 사용자가 퀴즈 정답을 문항별로 나눠 동시에 제출해도 오류 없이 한 번만 완료되고 보상은 1회다")
	void sameUserQuizAnswersSplitAcrossRequestsCompletesOnce() throws Exception {
		// given
		MissionDetail quiz = saveQuizMission("퀴즈 미션");
		UUID missionId = quiz.getMission().getId();
		MissionAnswerInput[] perQuestion = allCorrect(quiz); // 원소 하나가 한 문항의 정답이다
		// when
		List<Object> outcomes = runSimultaneously(List.of(
				submit(userId, missionId, UUID.randomUUID(), perQuestion[0]),
				submit(userId, missionId, UUID.randomUUID(), perQuestion[1]),
				submit(userId, missionId, UUID.randomUUID(), perQuestion[2])));
		// then
		assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(MissionSubmissionResult.class));
		List<MissionSubmissionResult> results = outcomes.stream().map(MissionSubmissionResult.class::cast).toList();
		assertThat(results.stream().filter(MissionSubmissionResult::isCompleted)).hasSize(1);
		assertThat(count("quiz_question_progress", userId)).isEqualTo(3);
		assertThat(count("mission_submissions", userId)).isEqualTo(3);
		assertThat(count("mission_reward_claims", userId)).isEqualTo(1);
		assertThat(count("tickets", userId)).isEqualTo(1);
	}

	@RepeatedTest(REPEATS)
	@DisplayName("정답을 문항별로 나눈 제출과 전 문항 제출이 동시에 와도 오류 없이 한 번만 완료되고 보상은 1회다")
	void sameUserSplitAndFullQuizSubmissionsCompleteOnce() throws Exception {
		// given
		MissionDetail quiz = saveQuizMission("퀴즈 미션");
		UUID missionId = quiz.getMission().getId();
		MissionAnswerInput[] perQuestion = allCorrect(quiz); // 원소 하나가 한 문항의 정답이다
		// when
		List<Object> outcomes = runSimultaneously(List.of(
				submit(userId, missionId, UUID.randomUUID(), perQuestion[0]),
				submit(userId, missionId, UUID.randomUUID(), perQuestion[1]),
				submit(userId, missionId, UUID.randomUUID(), perQuestion[2]),
				submit(userId, missionId, UUID.randomUUID(), allCorrect(quiz))));
		// then
		assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(MissionSubmissionResult.class));
		assertThat(count("quiz_question_progress", userId)).isEqualTo(3);
		assertThat(count("mission_reward_claims", userId)).isEqualTo(1);
		assertThat(count("tickets", userId)).isEqualTo(1);
		assertThat(count("select count(*) from mission_submissions where user_id = ? and is_completed = true",
				bytes(userId))).isEqualTo(1);
	}

	@RepeatedTest(REPEATS)
	@DisplayName("문항별 동시 제출이 끝난 뒤 같은 제출 ID로 다시 보내면 같은 결과를 돌려주고 보상은 늘지 않는다")
	void retryingSplitSubmissionsKeepsSingleReward() throws Exception {
		// given
		MissionDetail quiz = saveQuizMission("퀴즈 미션");
		UUID missionId = quiz.getMission().getId();
		MissionAnswerInput[] perQuestion = allCorrect(quiz); // 원소 하나가 한 문항의 정답이다
		List<UUID> submissionIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		List<Object> first = runSimultaneously(List.of(
				submit(userId, missionId, submissionIds.get(0), perQuestion[0]),
				submit(userId, missionId, submissionIds.get(1), perQuestion[1]),
				submit(userId, missionId, submissionIds.get(2), perQuestion[2])));
		assertThat(first).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(MissionSubmissionResult.class));
		// when
		for (int i = 0; i < submissionIds.size(); i++) {
			MissionSubmissionResult retried = submit(userId, missionId, submissionIds.get(i), perQuestion[i]).call();
			// then
			assertThat(retried.getSubmissionId()).isEqualTo(submissionIds.get(i));
			assertThat(retried.isCreated()).isFalse();
		}
		assertThat(count("mission_submissions", userId)).isEqualTo(3);
		assertThat(count("mission_reward_claims", userId)).isEqualTo(1);
		assertThat(count("tickets", userId)).isEqualTo(1);
	}

	@Test
	@DisplayName("퀴즈 제출이 사용자 잠금을 기다리는 사이 종료 시각이 지나도 도착 시각이 운영 기간 안이면 접수한다")
	void acceptsSubmissionArrivedBeforeEndWhenLockWaitCrossesEnd() throws Exception {
		// given: 종료 1초 전에 도착한 제출이, 앞 트랜잭션이 쥔 사용자 행 잠금에서 대기한다
		MissionDetail quiz = saveQuizMission("퀴즈 미션");
		UUID missionId = quiz.getMission().getId();
		MissionAnswerInput answer = allCorrect(quiz)[0];
		Instant arrival = CLOSES.minusSeconds(1);
		clock.set(arrival);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Object> holder = executor.submit(() -> transaction.execute(status -> {
				submissionRepository.lockUser(userId);
				locked.countDown();
				awaitQuietly(release);
				return null;
			}));
			assertThat(locked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
			Future<MissionSubmissionResult> waiting = executor.submit(submit(userId, missionId, UUID.randomUUID(), answer));
			new LockWaitProbe(mysql).awaitLockWaits(1);
			// when: 대기 중에 종료 시각이 지난 뒤 잠금이 풀린다
			clock.set(CLOSES.plusSeconds(1));
			release.countDown();
			holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
			MissionSubmissionResult result = waiting.get(WAIT_SECONDS, TimeUnit.SECONDS);
			// then: 서버가 요청을 받은 시각 기준으로 접수한다
			assertThat(result.isCreated()).isTrue();
			assertThat(result.getReceivedAt()).isEqualTo(arrival);
			assertThat(count("mission_submissions", userId)).isEqualTo(1);
		} finally {
			release.countDown();
			ConcurrentTasks.shutdownAndAwait(executor);
		}
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private List<Object> runSimultaneously(List<? extends Callable<?>> works) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(works.size());
		try {
			List<Future<Object>> futures = new ArrayList<>();
			for (Callable<?> work : works) {
				futures.add(executor.submit(() -> {
					start.await();
					return work.call();
				}));
			}
			start.countDown();
			List<Object> outcomes = new ArrayList<>();
			for (Future<Object> future : futures) {
				try {
					outcomes.add(future.get(WAIT_SECONDS, TimeUnit.SECONDS));
				} catch (ExecutionException e) {
					outcomes.add(e.getCause());
				}
			}
			return outcomes;
		} finally {
			start.countDown();
			ConcurrentTasks.shutdownAndAwait(executor);
		}
	}
}
