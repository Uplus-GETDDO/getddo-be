package com.getddo.core.mission.service;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.mission.domain.MissionAnswerInput;
import com.getddo.core.mission.domain.MissionSubmissionCommand;
import com.getddo.core.mission.domain.MissionSubmissionResult;
import com.getddo.core.mission.exception.MissionErrorCode;
import com.getddo.core.mission.exception.MissionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MissionSubmissionServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-13T03:00:00Z");
	private static final UUID USER_ID = UUID.randomUUID();
	private static final UUID MISSION_ID = UUID.randomUUID();
	private static final UUID SUBMISSION_ID = UUID.randomUUID();

	@Mock
	private MissionSubmissionRecorder recorder;

	private MissionSubmissionService service;

	@BeforeEach
	void setUp() {
		service = new MissionSubmissionService(recorder, new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));
	}

	private static MissionSubmissionCommand command() {
		return new MissionSubmissionCommand(USER_ID, MISSION_ID, SUBMISSION_ID,
				List.of(new MissionAnswerInput(UUID.randomUUID(), UUID.randomUUID(), null)));
	}

	private static MissionSubmissionResult result() {
		return new MissionSubmissionResult(SUBMISSION_ID, MISSION_ID, Instant.parse("2026-10-13T03:00:00Z"), false, null,
				true);
	}

	private static SQLException duplicateKey() {
		return new SQLIntegrityConstraintViolationException("Duplicate entry", "23000", 1062);
	}

	@Test
	@DisplayName("정상 처리는 한 번만 처리하고 결과를 그대로 돌려준다")
	void processesOnce() {
		// given
		MissionSubmissionCommand command = command();
		MissionSubmissionResult result = result();
		when(recorder.record(command, NOW)).thenReturn(result);
		// when
		MissionSubmissionResult actual = service.submit(command);
		// then
		assertThat(actual).isSameAs(result);
		verify(recorder, times(1)).record(command, NOW);
	}

	@Test
	@DisplayName("UNIQUE 위반이나 잠금 실패로 롤백되면 새 트랜잭션에서 한 번 다시 처리해 먼저 확정된 결과를 읽는다")
	void retriesOnceOnConcurrentConflict() {
		// given
		MissionSubmissionCommand command = command();
		MissionSubmissionResult result = result();
		when(recorder.record(command, NOW))
				.thenThrow(new DataIntegrityViolationException("duplicate", duplicateKey()))
				.thenReturn(result);
		// when
		MissionSubmissionResult actual = service.submit(command);
		// then
		assertThat(actual).isSameAs(result);
		verify(recorder, times(2)).record(command, NOW);
	}

	@Test
	@DisplayName("잠금 교착도 한 번 다시 처리하고 두 번째도 실패하면 그 예외를 그대로 던진다")
	void propagatesSecondFailure() {
		// given
		MissionSubmissionCommand command = command();
		when(recorder.record(command, NOW))
				.thenThrow(new CannotAcquireLockException("deadlock"))
				.thenThrow(new CannotAcquireLockException("deadlock again"));
		// when
		// then
		assertThatThrownBy(() -> service.submit(command))
				.isInstanceOf(CannotAcquireLockException.class)
				.hasMessageContaining("again");
		verify(recorder, times(2)).record(command, NOW);
	}

	@Test
	@DisplayName("UNIQUE 위반이 아닌 무결성 오류와 업무 규칙 위반은 다시 처리하지 않고 그대로 던진다")
	void doesNotRetryOtherFailures() {
		// given
		MissionSubmissionCommand command = command();
		when(recorder.record(command, NOW))
				.thenThrow(new DataIntegrityViolationException("foreign key",
						new SQLIntegrityConstraintViolationException("Cannot add or update a child row", "23000", 1452)))
				.thenThrow(new MissionException(MissionErrorCode.MISSION_NOT_OPEN));
		// when
		// then
		assertThatThrownBy(() -> service.submit(command)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> service.submit(command)).isInstanceOf(MissionException.class);
		verify(recorder, times(2)).record(command, NOW);
	}

	@Test
	@DisplayName("요청·사용자·미션·제출 ID·답변 목록이 없으면 처리하지 않고 답변 형식 오류다")
	void rejectsMissingIdentifiers() {
		// given
		List<MissionAnswerInput> answers = List.of();
		// when
		// then
		for (MissionSubmissionCommand invalid : new MissionSubmissionCommand[] {
				null,
				new MissionSubmissionCommand(null, MISSION_ID, SUBMISSION_ID, answers),
				new MissionSubmissionCommand(USER_ID, null, SUBMISSION_ID, answers),
				new MissionSubmissionCommand(USER_ID, MISSION_ID, null, answers),
				new MissionSubmissionCommand(USER_ID, MISSION_ID, SUBMISSION_ID, null)}) {
			assertThatThrownBy(() -> service.submit(invalid)).isInstanceOfSatisfying(MissionException.class,
					e -> assertThat(e.getErrorCode()).isEqualTo(MissionErrorCode.INVALID_MISSION_ANSWER));
		}
		verifyNoInteractions(recorder);
	}

	@Test
	@DisplayName("서버 도착 시각은 처리 시작 전에 한 번만 정하고, 시간이 지난 뒤의 재처리에도 같은 시각을 넘긴다")
	void passesTheSameArrivalTimeToTheRetry() {
		// given: 시계가 호출마다 1초씩 흐른다. 재처리 시점에 시각을 다시 구하면 첫 도착 시각과 달라진다.
		Instant[] ticks = {NOW, NOW.plusSeconds(1), NOW.plusSeconds(2)};
		int[] next = {0};
		Clock advancing = new Clock() {
			@Override
			public ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return ticks[Math.min(next[0]++, ticks.length - 1)];
			}
		};
		MissionSubmissionService advancingService = new MissionSubmissionService(recorder, new TimeProvider(advancing));
		MissionSubmissionCommand command = command();
		MissionSubmissionResult result = result();
		when(recorder.record(command, NOW))
				.thenThrow(new DataIntegrityViolationException("duplicate", duplicateKey()))
				.thenReturn(result);
		// when
		MissionSubmissionResult actual = advancingService.submit(command);
		// then
		assertThat(actual).isSameAs(result);
		verify(recorder, times(2)).record(command, NOW);
		assertThat(next[0]).isEqualTo(1);
	}
}
