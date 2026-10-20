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
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.domain.DrawType;
import com.getddo.db.drawing.entity.DrawCandidateEntity;
import com.getddo.db.drawing.entity.DrawRunEntity;
import com.getddo.db.drawing.mapper.DrawSnapshotMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
	private final UUID eventId = UUID.randomUUID();
	private final UUID runId = UUID.randomUUID();
	private final DrawRunEntity run = mock(DrawRunEntity.class);
	private final DrawSnapshot input = mock(DrawSnapshot.class);
	private final DrawSnapshot stored = mock(DrawSnapshot.class);
	private final DrawSnapshot.Candidate inputCandidate = mock(DrawSnapshot.Candidate.class);
	private final DrawCandidateEntity entity = mock(DrawCandidateEntity.class);

	/** 후보·연결 저장과 재조회가 같은 배치 설정 아래 수행되는 저장소 대역을 준비한다. */
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
		when(runs.findByEventIdAndDrawTypeAndRunNumber(eventId, DrawType.INITIAL, 0))
				.thenAnswer(invocation -> {
					assertThat(activeBatchSize.get()).isEqualTo(100);
					return Optional.of(run);
				});
		when(links.findCandidates(runId)).thenReturn(List.of(entity));
		when(mapper.toDomain(run, List.of(entity))).thenReturn(stored);
	}

	/** 기본 설정 상속(null)과 기존 명시 설정을 성공 후 모두 복원한다. */
	@ParameterizedTest
	@NullSource
	@ValueSource(ints = {17, 200})
	@DisplayName("최초 후보·연결은 배치 100으로 저장하고 재조회 결과를 반환한 뒤 이전 설정을 복원한다")
	void restoresCallerBatchSizeAfterSaving(Integer previousBatchSize) {
		// given
		activeBatchSize.set(previousBatchSize);

		// when
		DrawSnapshot result = repository.saveInitial(input);

		// then
		assertThat(result).isSameAs(stored);
		assertThat(activeBatchSize.get()).isEqualTo(previousBatchSize);
	}

	/** 중간 저장·재조회가 실패해도 예외를 삼키지 않고 세션 설정을 되돌린다. */
	@ParameterizedTest
	@ValueSource(strings = {"candidate", "link", "reload"})
	@DisplayName("후보 저장·연결 저장·재조회 실패는 전파하고 호출자의 배치 설정을 복원한다")
	void restoresCallerBatchSizeAndPropagatesFailure(String step) {
		// given
		activeBatchSize.set(17);
		DataIntegrityViolationException failure = new DataIntegrityViolationException("test failure");
		switch (step) {
			case "candidate" -> doThrow(failure).when(candidates).saveAllAndFlush(anyList());
			case "link" -> doThrow(failure).when(links).saveAllAndFlush(anyList());
			case "reload" -> doThrow(failure).when(entityManager).refresh(run);
			default -> throw new IllegalArgumentException(step);
		}

		// when / then
		assertThatThrownBy(() -> repository.saveInitial(input)).isSameAs(failure);
		assertThat(activeBatchSize.get()).isEqualTo(17);
	}
}
