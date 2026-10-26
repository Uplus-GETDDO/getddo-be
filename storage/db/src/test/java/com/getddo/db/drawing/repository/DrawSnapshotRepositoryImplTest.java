package com.getddo.db.drawing.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.EntityManager;

import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawType;
import com.getddo.db.drawing.entity.DrawCandidateEntity;
import com.getddo.db.drawing.entity.DrawRunCandidateEntity;
import com.getddo.db.drawing.entity.DrawRunEntity;
import com.getddo.db.drawing.mapper.DrawSnapshotMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 세션 배치 설정의 범위·복원과 예외 전파를 검증한다. 실제 DB 롤백 검증은 아니다. */
class DrawSnapshotRepositoryImplTest {
	private final DrawSnapshotSourceReader source = mock(DrawSnapshotSourceReader.class);
	private final DrawRunJpaRepository runs = mock(DrawRunJpaRepository.class);
	private final DrawCandidateJpaRepository candidates = mock(DrawCandidateJpaRepository.class);
	private final DrawRunCandidateJpaRepository links = mock(DrawRunCandidateJpaRepository.class);
	private final DrawSnapshotMapper mapper = mock(DrawSnapshotMapper.class);
	private final EntityManager entityManager = mock(EntityManager.class);
	private final Session session = mock(Session.class);
	private final DrawSnapshotRepositoryImpl repository = new DrawSnapshotRepositoryImpl(
			source, runs, candidates, links, mapper, entityManager);
	private final AtomicReference<Integer> activeBatchSize = new AtomicReference<>();
	private Integer expectedRestoredBatchSize;
	private final UUID eventId = UUID.randomUUID();
	private final UUID runId = UUID.randomUUID();
	private final DrawRunEntity run = mock(DrawRunEntity.class);
	private final DrawSnapshot input = mock(DrawSnapshot.class);
	private final DrawSnapshot stored = mock(DrawSnapshot.class);
	private final DrawSnapshot.Candidate inputCandidate = mock(DrawSnapshot.Candidate.class);
	private final DrawCandidateEntity entity = mock(DrawCandidateEntity.class);

	/** 후보·연결 저장에만 배치 100을 사용하고 이후에는 기존 설정을 사용하는지 검사한다. */
	@BeforeEach
	void setUp() {
		when(entityManager.unwrap(Session.class)).thenReturn(session);
		when(session.getJdbcBatchSize()).thenAnswer(invocation -> activeBatchSize.get());
		doAnswer(invocation -> {
			activeBatchSize.set(invocation.getArgument(0));
			return null;
		}).when(session).setJdbcBatchSize(nullable(Integer.class));

		when(input.eventId()).thenReturn(eventId);
		when(input.candidates()).thenReturn(List.of(inputCandidate));
		when(run.getId()).thenReturn(runId);
		when(entity.getId()).thenReturn(UUID.randomUUID());
		when(runs.saveAndFlush(any(DrawRunEntity.class))).thenReturn(run);
		when(mapper.toEntity(run, inputCandidate)).thenReturn(entity);
		when(candidates.saveAllAndFlush(anyList())).thenAnswer(invocation -> {
			assertThat(activeBatchSize.get()).isEqualTo(100);
			assertThat(invocation.<List<DrawCandidateEntity>>getArgument(0)).containsExactly(entity);
			return List.of(entity);
		});
		when(links.saveAllAndFlush(anyList())).thenAnswer(invocation -> {
			assertThat(activeBatchSize.get()).isEqualTo(100);
			return invocation.getArgument(0);
		});
		doAnswer(invocation -> {
			assertThat(activeBatchSize.get()).isEqualTo(expectedRestoredBatchSize);
			return null;
		}).when(mapper).fixInput(run, input);
		doAnswer(invocation -> {
			assertThat(activeBatchSize.get()).isEqualTo(expectedRestoredBatchSize);
			return null;
		}).when(runs).flush();
		doAnswer(invocation -> {
			assertThat(activeBatchSize.get()).isEqualTo(expectedRestoredBatchSize);
			return null;
		}).when(entityManager).refresh(run);
		when(runs.findByEventIdAndDrawTypeAndRunNumber(eventId, DrawType.INITIAL, 0))
				.thenAnswer(invocation -> {
					assertThat(activeBatchSize.get()).isEqualTo(expectedRestoredBatchSize);
					return Optional.of(run);
				});
		when(links.findCandidates(runId)).thenReturn(List.of(entity));
		when(mapper.toDomain(run, List.of(entity))).thenReturn(stored);
	}

	/** 기본 설정 상속(null)과 기존 명시 설정을 성공 후 모두 복원한다. */
	@ParameterizedTest
	@NullSource
	@ValueSource(ints = {17, 200})
	@DisplayName("최초 후보·연결은 배치 100으로 저장하고 입력 고정·flush·재조회 전에 이전 설정을 복원한다")
	void restoresCallerBatchSizeAfterSaving(Integer previousBatchSize) {
		// given
		expectedRestoredBatchSize = previousBatchSize;
		activeBatchSize.set(previousBatchSize);

		// when
		DrawSnapshot result = repository.saveInitial(input);

		// then
		assertThat(result).isSameAs(stored);
		assertThat(activeBatchSize.get()).isEqualTo(previousBatchSize);
	}

	/** 저장·입력 고정·재조회 실패 시 기본 설정 상속(null)과 명시 설정을 유지하고 예외를 전파한다. */
	@ParameterizedTest
	@CsvSource(value = {
			"candidate, NULL", "candidate, 17",
			"link, NULL", "link, 17",
			"fix, NULL", "fix, 17",
			"flush, NULL", "flush, 17",
			"refresh, NULL", "refresh, 17",
			"lookup, NULL", "lookup, 17"
	}, nullValues = "NULL")
	@DisplayName("후보·연결 저장과 복원 후 입력 고정·flush·재조회 실패는 전파하고 이전 설정을 유지한다")
	void restoresCallerBatchSizeAndPropagatesFailure(String step, Integer previousBatchSize) {
		// given
		expectedRestoredBatchSize = previousBatchSize;
		activeBatchSize.set(previousBatchSize);
		DataIntegrityViolationException failure = new DataIntegrityViolationException("test failure");
		switch (step) {
			case "candidate" -> doThrow(failure).when(candidates).saveAllAndFlush(anyList());
			case "link" -> doThrow(failure).when(links).saveAllAndFlush(anyList());
			case "fix" -> doThrow(failure).when(mapper).fixInput(run, input);
			case "flush" -> doThrow(failure).when(runs).flush();
			case "refresh" -> doThrow(failure).when(entityManager).refresh(run);
			case "lookup" -> doThrow(failure).when(runs)
					.findByEventIdAndDrawTypeAndRunNumber(eventId, DrawType.INITIAL, 0);
			default -> throw new IllegalArgumentException(step);
		}

		// when / then
		assertThatThrownBy(() -> repository.saveInitial(input)).isSameAs(failure);
		assertThat(activeBatchSize.get()).isEqualTo(previousBatchSize);
	}

	/** 최초 후보 자체를 저장하지 않고 같은 후보의 실행별 연결만 배치로 저장한다. */
	@ParameterizedTest
	@NullSource
	@ValueSource(ints = {17, 200})
	@DisplayName("재추첨 연결은 배치 100으로 저장하고 입력 고정·flush·재조회 전에 이전 설정을 복원한다")
	void batchesReplacementLinksAndRestoresCallerSetting(Integer previousBatchSize) {
		// given
		prepareReplacement(previousBatchSize);
		doAnswer(invocation -> {
			assertThat(activeBatchSize.get()).isEqualTo(100);
			List<DrawRunCandidateEntity> savedLinks = invocation.getArgument(0);
			assertThat(savedLinks).hasSize(1);
			assertThat(savedLinks.getFirst().getDrawRun()).isSameAs(run);
			assertThat(savedLinks.getFirst().getCandidate()).isSameAs(entity);
			assertThat(savedLinks.getFirst().getId().getDrawRunId()).isEqualTo(runId);
			assertThat(savedLinks.getFirst().getId().getCandidateId()).isEqualTo(entity.getId());
			return savedLinks;
		}).when(links).saveAllAndFlush(anyList());

		// when
		DrawSnapshot result = repository.saveRegisteredReplacement(input);

		// then
		assertThat(result).isSameAs(stored);
		assertThat(activeBatchSize.get()).isEqualTo(previousBatchSize);
		verify(candidates, never()).saveAllAndFlush(anyList());
		verify(mapper, never()).toEntity(any(), any());
	}

	/** 연결 저장 실패 시 복원하고, 복원 이후 단계의 예외도 숨기지 않는다. */
	@ParameterizedTest
	@CsvSource(value = {
			"link, NULL", "link, 17",
			"fix, NULL", "fix, 17",
			"flush, NULL", "flush, 17",
			"refresh, NULL", "refresh, 17",
			"lookup, NULL", "lookup, 17"
	}, nullValues = "NULL")
	@DisplayName("재추첨 연결 저장·입력 고정·재조회 실패를 전파하며 호출자의 배치 설정을 보존한다")
	void restoresReplacementSettingAndPropagatesFailure(String step, Integer previousBatchSize) {
		// given
		prepareReplacement(previousBatchSize);
		DataIntegrityViolationException failure = new DataIntegrityViolationException("test failure");
		switch (step) {
			case "link" -> doAnswer(invocation -> {
				assertThat(activeBatchSize.get()).isEqualTo(100);
				throw failure;
			}).when(links).saveAllAndFlush(anyList());
			case "fix" -> doThrow(failure).when(mapper).fixInput(run, input);
			case "flush" -> doThrow(failure).when(runs).flush();
			case "refresh" -> doThrow(failure).when(entityManager).refresh(run);
			case "lookup" -> doThrow(failure).when(links).findCandidates(runId);
			default -> throw new IllegalArgumentException(step);
		}

		// when / then
		assertThatThrownBy(() -> repository.saveRegisteredReplacement(input)).isSameAs(failure);
		assertThat(activeBatchSize.get()).isEqualTo(previousBatchSize);
	}

	/** 후보가 소진된 재추첨도 빈 연결을 저장한 뒤 기존 입력 고정 흐름을 따른다. */
	@ParameterizedTest
	@NullSource
	@ValueSource(ints = {17})
	@DisplayName("빈 후보 재추첨도 후보를 새로 만들지 않고 배치 설정을 복원한 뒤 입력을 고정한다")
	void preservesEmptyReplacementInput(Integer previousBatchSize) {
		// given
		prepareReplacement(previousBatchSize);
		when(input.candidates()).thenReturn(List.of());
		doReturn(List.of()).when(candidates).findAllById(anyList());
		when(links.findCandidates(runId)).thenReturn(List.of());
		when(mapper.toDomain(run, List.of())).thenReturn(stored);
		doAnswer(invocation -> {
			assertThat(activeBatchSize.get()).isEqualTo(100);
			assertThat(invocation.<List<DrawRunCandidateEntity>>getArgument(0)).isEmpty();
			return List.of();
		}).when(links).saveAllAndFlush(anyList());

		// when
		DrawSnapshot result = repository.saveRegisteredReplacement(input);

		// then
		assertThat(result).isSameAs(stored);
		assertThat(activeBatchSize.get()).isEqualTo(previousBatchSize);
		verify(mapper).fixInput(run, input);
		verify(candidates, never()).saveAllAndFlush(anyList());
	}

	/** 최초 실행에 속한 후보와 입력 미고정 재추첨 실행을 구성한다. */
	private void prepareReplacement(Integer previousBatchSize) {
		expectedRestoredBatchSize = previousBatchSize;
		activeBatchSize.set(previousBatchSize);
		UUID originalRunId = UUID.randomUUID();
		DrawRunEntity originalRun = mock(DrawRunEntity.class);
		DrawSnapshot.Rules rules = mock(DrawSnapshot.Rules.class);
		when(input.runId()).thenReturn(runId);
		when(input.drawType()).thenReturn(DrawType.REPLACEMENT);
		when(input.status()).thenReturn(DrawRunStatus.READY);
		when(input.rules()).thenReturn(rules);
		when(rules.replacementFormat()).thenReturn(true);
		when(rules.originalRunId()).thenReturn(originalRunId);
		when(run.getEventId()).thenReturn(eventId);
		when(run.getDrawType()).thenReturn(DrawType.REPLACEMENT);
		when(run.getStatus()).thenReturn(DrawRunStatus.PREPARING);
		when(runs.findForUpdate(runId)).thenReturn(Optional.of(run));
		UUID candidateId = entity.getId();
		when(inputCandidate.id()).thenReturn(candidateId);
		when(originalRun.getId()).thenReturn(originalRunId);
		when(entity.getDrawRun()).thenReturn(originalRun);
		when(candidates.findAllById(anyList())).thenAnswer(invocation -> {
			assertThat(invocation.<List<UUID>>getArgument(0)).containsExactly(entity.getId());
			assertThat(activeBatchSize.get()).isEqualTo(previousBatchSize);
			return List.of(entity);
		});
		when(links.findCandidates(runId)).thenAnswer(invocation -> {
			assertThat(activeBatchSize.get()).isEqualTo(previousBatchSize);
			return List.of(entity);
		});
	}
}
