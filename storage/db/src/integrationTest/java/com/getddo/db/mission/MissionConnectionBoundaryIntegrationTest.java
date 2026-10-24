package com.getddo.db.mission;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.getddo.core.mission.domain.MissionAnswerInput;
import com.getddo.core.mission.domain.MissionDetail;
import com.getddo.core.mission.domain.MissionSubmissionCommand;
import com.getddo.core.mission.domain.MissionSubmissionResult;
import com.getddo.core.mission.exception.MissionErrorCode;
import com.getddo.core.mission.exception.MissionException;
import com.getddo.core.mission.service.MissionSubmissionService;
import com.getddo.core.ticket.service.TicketGrantService;

import static com.getddo.db.ticket.TicketGrantSeeds.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 실제 MySQL 커밋 전·후에 연결 예외를 주입한다. 물리 단절이나 운영 장애 시험은 수행하지 않는다. */
class MissionConnectionBoundaryIntegrationTest extends MissionIntegrationTestSupport {

	@Autowired
	private MissionSubmissionService submissions;
	@MockitoSpyBean
	private TicketGrantService grants;

	/** 실제 커밋 전·후의 제출·보상 상태를 확인하고 같은 키 재전송이 추가 지급 없이 기존 결과를 반환하는지 검증한다. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("미션 커밋 전 실패는 기록·보상을 롤백하고 커밋 후 유실은 같은 키로 보상을 재지급하지 않는다")
	void missionBoundaryPreservesAtomicityAndReplay(boolean committed) {
		// given
		MissionDetail survey = saveSurveyMission("연결 경계 설문");
		UUID question = survey.getSurveyQuestions().get(0).getId();
		UUID option = survey.getSurveyQuestions().get(0).getOptions().get(0).getId();
		UUID key = UUID.randomUUID();
		MissionSubmissionCommand request = new MissionSubmissionCommand(userId, survey.getMission().getId(), key,
				List.of(new MissionAnswerInput(question, option, null)));
		TicketGrantService target = AopTestUtils.getUltimateTargetObject(grants);
		doAnswer(invocation -> {
			Object result = invocation.callRealMethod();
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void beforeCommit(boolean readOnly) {
					if (!committed) {
						throw connectionFailure();
					}
				}

				@Override
				public void afterCommit() {
					if (committed) {
						throw connectionFailure();
					}
				}
			});
			return result;
		}).when(target).grant(any());
		// when / then
		assertThatThrownBy(() -> submissions.submit(request)).isInstanceOf(DataAccessResourceFailureException.class);
		verify(target, times(1)).grant(any());
		assertThat(count("select count(*) from mission_submissions where id = ?", bytes(key))).isEqualTo(committed ? 1 : 0);
		assertThat(count("select count(*) from mission_reward_claims where user_id = ?", bytes(userId))).isEqualTo(committed ? 1 : 0);
		assertThat(count("select count(*) from tickets where user_id = ?", bytes(userId))).isEqualTo(committed ? 1 : 0);

		// given: 복구 후 같은 제출을 재전송한다.
		reset(target);
		// when
		MissionSubmissionResult replay = submissions.submit(request);
		MissionSubmissionResult again = submissions.submit(request);
		// then
		assertThat(replay.isCreated()).isEqualTo(!committed);
		assertThat(again.isCreated()).isFalse();
		assertThat(replay.getSubmissionId()).isEqualTo(key);
		assertThat(again.getReward().getGrantedAt()).isEqualTo(replay.getReward().getGrantedAt());
		assertThat(count("select count(*) from mission_reward_claims where user_id = ?", bytes(userId))).isEqualTo(1);
		assertThat(count("select count(*) from tickets where user_id = ?", bytes(userId))).isEqualTo(1);
		UUID other = survey.getSurveyQuestions().get(0).getOptions().get(1).getId();
		assertThatThrownBy(() -> submissions.submit(new MissionSubmissionCommand(userId, survey.getMission().getId(), key,
				List.of(new MissionAnswerInput(question, other, null)))))
				.isInstanceOfSatisfying(MissionException.class,
						failure -> assertThat(failure.getErrorCode()).isEqualTo(MissionErrorCode.IDEMPOTENCY_CONFLICT));
	}

	/** 운영 연결을 차단하지 않고 테스트의 커밋 경계에 주입할 연결 오류를 만든다. */
	private static DataAccessResourceFailureException connectionFailure() {
		return new DataAccessResourceFailureException("test connection boundary", new SQLException("test", "08007"));
	}
}
