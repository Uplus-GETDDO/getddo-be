package com.getddo.core.drawing.service;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.drawing.domain.DrawConfirmation;
import com.getddo.core.drawing.domain.DrawPublication;
import com.getddo.core.drawing.domain.DrawPublicationRun;
import com.getddo.core.drawing.domain.DrawPublicationType;
import com.getddo.core.drawing.domain.DrawResult;
import com.getddo.core.drawing.domain.DrawResultConfirmation;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSelection;
import com.getddo.core.drawing.domain.DrawSelectionResultType;
import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.domain.DrawSnapshotSource;
import com.getddo.core.drawing.repository.DrawPublicationRepository;
import com.getddo.core.drawing.repository.DrawResultRepository;
import com.getddo.core.drawing.repository.DrawSnapshotRepository;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.domain.EventType;
import com.getddo.core.user.domain.Membership;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/** 실제 서비스와 Spring 트랜잭션 프록시를 검증한다. 저장소·이벤트 잠금은 메모리 대역이며 MySQL 검증이 아니다. */
class DrawPublicationReplacementServiceTest {
	private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
	private final UUID eventId = UUID.randomUUID(), initialRun = UUID.randomUUID(), prizeId = UUID.randomUUID();
	private final UUID actorId = UUID.randomUUID(), userA = UUID.randomUUID(), userB = UUID.randomUUID();
	private final UUID userC = UUID.randomUUID(), userD = UUID.randomUUID();
	private final Map<UUID, UUID> candidates = new HashMap<>();
	private final DrawPublicationRepository repository = mock(DrawPublicationRepository.class);
	private final DrawSnapshotRepository snapshots = mock(DrawSnapshotRepository.class);
	private final DrawResultRepository results = mock(DrawResultRepository.class);
	private final UserRepository users = mock(UserRepository.class);
	private final Clock clock = mock(Clock.class);
	private final Map<UUID, DrawResultConfirmation> checked = new HashMap<>();
	private final List<DrawPublication> history = new ArrayList<>();
	private final List<DrawPublicationRun> runs = new ArrayList<>();
	private final List<DrawPublication.Cancellation> cancellations = new ArrayList<>();
	private final MemoryTransactions transactions = new MemoryTransactions();
	private final DrawPublicationNotificationService notifications = mock(DrawPublicationNotificationService.class);
	private DrawPublicationService publications;
	private DrawResultConfirmationService confirmations;
	private DrawPublication latest;
	private DrawResult resultA, resultB;
	private DrawSnapshot initial;
	private EventStatus eventStatus = EventStatus.CLOSED;
	private Instant endsAt = NOW.minusSeconds(60);

	/** 각 시나리오에 사용할 공개·확인 서비스와 저장·사용자 대역을 준비한다. */
	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(NOW);
		TimeProvider time = new TimeProvider(clock);
		publications = proxy(new DrawPublicationService(repository, snapshots, results, time, notifications, mock(com.getddo.core.event.service.EventStatusChangeService.class)));
		confirmations = proxy(new DrawResultConfirmationService(repository, publications, new DrawAdminAuthorization(users), time));
		for (UUID user : List.of(userA, userB, userC, userD)) candidates.put(user, UUID.randomUUID());
		List<DrawSnapshot.Candidate> inputs = candidates.entrySet().stream().map(entry -> new DrawSnapshot.Candidate(
				entry.getValue(), UUID.randomUUID(), entry.getKey(), 0, 1, null, null)).toList();
		initial = new DrawSnapshot(initialRun, eventId, DrawRunStatus.CONFIRMED, NOW.minusSeconds(2), "test",
				new DrawSnapshot.Rules(1, false, "NO_TICKET", "VIP", 1, 3, 5,
						List.of(new DrawSnapshotSource.Prize(prizeId, 1, 2))), inputs);
		resultA = selected(1, userA); resultB = selected(2, userB);
		when(snapshots.lockEvent(eventId)).thenAnswer(invocation -> {
			transactions.lockEvent();
			return Optional.of(new DrawSnapshotSource.Event(eventId, EventType.NO_TICKET, false,
					endsAt, eventStatus, false, "VIP", List.of()));
		});
		when(snapshots.findInitial(eventId)).thenReturn(Optional.of(initial));
		when(results.lockSnapshot(initialRun)).thenReturn(Optional.of(initial));
		when(results.findConfirmed(initial)).thenReturn(new DrawConfirmation(initialRun, eventId,
				NOW.minusSeconds(1), List.of(resultA, resultB)));
		when(users.findById(actorId)).thenReturn(Optional.of(actor(UserRole.ADMIN, UserStatus.ACTIVE)));
		when(repository.findLatest(eventId)).thenAnswer(invocation -> Optional.ofNullable(latest));
		when(repository.hasCancellationOrReplacement(eventId)).thenAnswer(invocation -> !cancellations.isEmpty() || !runs.isEmpty());
		when(repository.findEventId(any())).thenReturn(Optional.of(eventId));
		when(repository.findReplacementRuns(eventId)).thenAnswer(invocation -> runs.stream().map(run ->
				new DrawPublicationRun(run.getRunId(), run.getEventId(), run.getRunNumber(), run.getStatus(), run.getConfirmedAt(),
						run.getResults(), checked.get(run.getRunId()))).toList());
		when(repository.findCancellations(eventId)).thenAnswer(invocation -> List.copyOf(cancellations));
		when(repository.saveConfirmation(any(), any(), any())).thenAnswer(invocation -> {
			DrawResultConfirmation confirmation = new DrawResultConfirmation(UUID.randomUUID(), invocation.getArgument(0),
					invocation.getArgument(1), invocation.getArgument(2));
			checked.put(confirmation.getRunId(), confirmation);
			return confirmation;
		});
		when(repository.saveInitial(eq(eventId), eq(initialRun), anyList(), any())).thenAnswer(invocation -> {
			List<UUID> ids = invocation.getArgument(2);
			return save(initialRun, 1, null, null, ids.stream()
					.map(id -> new DrawPublication.Entry(id, initialRun, null)).toList(), invocation.getArgument(3));
		});
		when(repository.savePublication(eq(eventId), any(), anyInt(), any(), any(), anyList(), any())).thenAnswer(invocation ->
				save(invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3),
						invocation.getArgument(4), invocation.getArgument(5), invocation.getArgument(6)));
		when(repository.wasPublished(any())).thenAnswer(invocation -> {
			UUID id = invocation.getArgument(0);
			return history.stream().flatMap(publication -> publication.getEntries().stream()).anyMatch(entry -> entry.getResultId().equals(id));
		});
	}

	/** 최초 공개 전 취소 결과를 제거하고 유지 결과와 확인된 새 결과를 한 번 공개한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("최초 공개 전 취소 결과를 제거하고 유지 결과와 확인된 새 결과를 한 번 공개한다")
	void confirmsAndMergesInitialList() {
		// given
		DrawResult replacement = selected(1, userC);
		UUID run = addRun(1, resultA, DrawRunStatus.CONFIRMED, List.of(replacement));
		assertThat(publications.publishInitial(eventId)).isEmpty();
		// when
		DrawResultConfirmation confirmation = confirmations.confirmReplacement(run, actorId);
		// then
		assertThat(latest.getRevision()).isEqualTo(1);
		assertThat(latest.getSourceDrawId()).isEqualTo(run);
		assertThat(latest.getEntries()).containsExactly(new DrawPublication.Entry(replacement.getId(), run, confirmation.getId()),
				new DrawPublication.Entry(resultB.getId(), initialRun, null));
		assertThat(publications.wasPublished(resultA.getId())).isFalse();
		assertThat(latest.getConfirmedBy()).isEqualTo(actorId);
		assertThat(latest.getReason()).isEqualTo("취소 사유 1");
		assertThat(latest.getPublishedAt()).isEqualTo(NOW);
	}

	/** 마감 +1분 전 확인은 기록만 저장하고 예정 시각 경계에 최초 공개한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("마감 +1분 전 확인은 기록만 저장하고 예정 시각 경계에 최초 공개한다")
	void confirmsBeforeTimeAndPublishesAtBoundary() {
		// given
		endsAt = NOW.minusSeconds(59);
		UUID run = addRun(1, resultA, DrawRunStatus.CONFIRMED, List.of(selected(1, userC)));
		// when
		DrawResultConfirmation confirmation = confirmations.confirmReplacement(run, actorId);
		// then
		assertThat(checked).containsEntry(run, confirmation);
		assertThat(latest).isNull();
		assertThat(publications.publishInitial(eventId)).isEmpty();
		when(clock.instant()).thenReturn(NOW.plusSeconds(1));
		DrawPublication first = publications.publishInitial(eventId).orElseThrow();
		assertThat(first.getConfirmedAt()).isEqualTo(NOW);
		assertThat(first.getPublishedAt()).isEqualTo(NOW.plusSeconds(1));
		assertThat(publications.publishInitial(eventId)).contains(first);
		assertThat(history).hasSize(1);
	}

	/** 여러 재추첨 중 하나라도 미확인이면 최초 명단 일부를 먼저 공개하지 않는다는 시나리오를 검증한다. */
	@Test
	@DisplayName("여러 재추첨 중 하나라도 미확인이면 최초 명단 일부를 먼저 공개하지 않는다")
	void waitsForAllInitialConfirmations() {
		// given
		UUID first = addRun(1, resultA, DrawRunStatus.CONFIRMED, List.of(selected(1, userC)));
		UUID second = addRun(2, resultB, DrawRunStatus.CONFIRMED, List.of(selected(2, userD)));
		// when
		confirmations.confirmReplacement(second, actorId);
		// then
		assertThat(latest).isNull();
		assertThat(publications.publishInitial(eventId)).isEmpty();
		confirmations.confirmReplacement(first, actorId);
		assertThat(history).hasSize(1);
		assertThat(latest.getEntries()).extracting(DrawPublication.Entry::getConfirmationId)
				.containsExactly(checked.get(first).getId(), checked.get(second).getId());
	}

	/** 미준비 재추첨이 있으면 다른 결과 확인 후에도 최초 발표를 지연한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("미준비 재추첨이 있으면 다른 결과 확인 후에도 최초 발표를 지연한다")
	void waitsForPendingInitialExecution() {
		// given
		UUID first = addRun(1, resultA, DrawRunStatus.CONFIRMED, List.of(selected(1, userC)));
		UUID second = addRun(2, resultB, DrawRunStatus.FAILED, List.of());
		// when / then
		confirmations.confirmReplacement(first, actorId);
		assertThat(latest).isNull();
		assertThat(checked).hasSize(1);
		runs.set(1, new DrawPublicationRun(second, eventId, 2, DrawRunStatus.CONFIRMED, NOW,
				List.of(selected(2, userD)), null));
		confirmations.confirmReplacement(second, actorId);
		assertThat(history).hasSize(1);
		assertThat(latest.getEntries()).hasSize(2);
	}

	/** 발표 후 미확인 결과는 비공개이며 확인 후 유지 명단과 새 결과로 다음 버전을 만든다는 시나리오를 검증한다. */
	@Test
	@DisplayName("발표 후 미확인 결과는 비공개이며 확인 후 유지 명단과 새 결과로 다음 버전을 만든다")
	void updatesAfterConfirmationAndPreservesHistory() {
		// given
		DrawPublication first = publications.publishInitial(eventId).orElseThrow();
		DrawResult replacement = selected(1, userC);
		UUID run = addRun(1, resultA, DrawRunStatus.CONFIRMED, List.of(replacement));
		assertThat(publications.publishConfirmed(eventId, run)).contains(first);
		assertThat(publications.wasPublished(replacement.getId())).isFalse();
		// when
		confirmations.confirmReplacement(run, actorId);
		// then
		assertThat(latest.getRevision()).isEqualTo(2);
		assertThat(latest.getType()).isEqualTo(DrawPublicationType.UPDATE);
		assertThat(latest.getEntries()).extracting(DrawPublication.Entry::getResultId).containsExactly(replacement.getId(), resultB.getId());
		assertThat(history.getFirst()).isEqualTo(first);
		assertThat(publications.wasPublished(resultA.getId())).isTrue();
		assertThat(publications.wasPublished(replacement.getId())).isTrue();
	}

	/** 공개 후 독립 재추첨을 역순으로 확인해도 이미 반영된 새 결과를 유지한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("공개 후 독립 재추첨을 역순으로 확인해도 이미 반영된 새 결과를 유지한다")
	void confirmsIndependentRunsInReverseOrder() {
		// given
		publications.publishInitial(eventId);
		DrawResult replacementA = selected(1, userC), replacementB = selected(2, userD);
		UUID first = addRun(1, resultA, DrawRunStatus.CONFIRMED, List.of(replacementA));
		UUID second = addRun(2, resultB, DrawRunStatus.CONFIRMED, List.of(replacementB));
		// when
		confirmations.confirmReplacement(second, actorId);
		DrawPublication intermediate = latest;
		confirmations.confirmReplacement(first, actorId);
		// then
		assertThat(intermediate.getEntries()).extracting(DrawPublication.Entry::getResultId).containsExactly(resultA.getId(), replacementB.getId());
		assertThat(latest.getRevision()).isEqualTo(3);
		assertThat(latest.getSourceDrawId()).isEqualTo(first);
		assertThat(latest.getEntries()).containsExactly(new DrawPublication.Entry(replacementA.getId(), first, checked.get(first).getId()),
				new DrawPublication.Entry(replacementB.getId(), second, checked.get(second).getId()));
	}

	/** 미확인 중간 결과가 다시 취소되면 선행 확인까지 기다리고 중간 당첨자는 공개하지 않는다는 시나리오를 검증한다. */
	@Test
	@DisplayName("미확인 중간 결과가 다시 취소되면 선행 확인까지 기다리고 중간 당첨자는 공개하지 않는다")
	void preservesPrivateIntermediateResult() {
		// given
		publications.publishInitial(eventId);
		DrawResult privateResult = selected(1, userC), finalResult = selected(1, userD);
		UUID first = addRun(1, resultA, DrawRunStatus.CONFIRMED, List.of(privateResult));
		UUID second = addRun(2, privateResult, DrawRunStatus.CONFIRMED, List.of(finalResult));
		// when
		confirmations.confirmReplacement(second, actorId);
		assertThat(history).hasSize(1);
		confirmations.confirmReplacement(first, actorId);
		// then
		assertThat(history).hasSize(2);
		assertThat(publications.wasPublished(privateResult.getId())).isFalse();
		assertThat(latest.getEntries()).extracting(DrawPublication.Entry::getResultId).containsExactly(finalResult.getId(), resultB.getId());
	}

	/** 동일 확인·공개 재요청은 원래 확인자·시각과 한 공개 버전을 보존한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("동일 확인·공개 재요청은 원래 확인자·시각과 한 공개 버전을 보존한다")
	void repeatsWithoutNewConfirmationOrVersion() {
		// given
		publications.publishInitial(eventId);
		UUID run = addRun(1, resultA, DrawRunStatus.CONFIRMED, List.of(selected(1, userC)));
		DrawResultConfirmation first = confirmations.confirmReplacement(run, actorId);
		DrawPublication published = latest;
		when(clock.instant()).thenReturn(NOW.plusSeconds(60));
		// when / then
		assertThat(confirmations.confirmReplacement(run, actorId)).isEqualTo(first);
		assertThat(publications.publishConfirmed(eventId, run)).contains(published);
		assertThat(latest).isEqualTo(published);
		assertThat(history).hasSize(2);
		verify(notifications, times(2)).register(any());
		verify(repository, times(1)).saveConfirmation(eq(run), eq(actorId), any());
		verify(repository, times(1)).savePublication(eq(eventId), eq(run), eq(2), any(), any(), anyList(), any());
	}

	/** 다른 관리자가 같은 확인을 반복해도 원래 확인자를 덮어쓰지 않는다는 시나리오를 검증한다. */
	@Test
	@DisplayName("다른 관리자가 같은 확인을 반복해도 원래 확인자를 덮어쓰지 않는다")
	void preservesOriginalAdministratorOnReplay() {
		// given
		UUID run = addRun(1, resultA, DrawRunStatus.CONFIRMED, List.of(selected(1, userC)));
		DrawResultConfirmation original = confirmations.confirmReplacement(run, actorId);
		UUID otherId = UUID.randomUUID();
		when(users.findById(otherId)).thenReturn(Optional.of(new User(otherId, "다른 관리자", UserRole.ADMIN,
				UserStatus.ACTIVE, Membership.VIP, null, null, null, null, NOW, NOW)));
		// when / then
		assertThat(confirmations.confirmReplacement(run, otherId)).isEqualTo(original);
		assertThat(latest.getConfirmedBy()).isEqualTo(actorId);
		assertThat(history).hasSize(1);
		verify(repository, times(1)).saveConfirmation(eq(run), eq(actorId), any());
	}

	/** 같은 실행에 연결된 여러 취소 자리의 새 결과·미충원을 모두 검증한다. */
	@Test
	@DisplayName("같은 실행에 연결된 여러 취소 자리의 새 결과·미충원을 모두 검증한다")
	void validatesAllCancellationSlotsInOneRun() {
		// given
		UUID run = addRun(1, resultA, DrawRunStatus.CONFIRMED, List.of(selected(1, userC)));
		cancellations.add(new DrawPublication.Cancellation(UUID.randomUUID(), resultB.getId(), run, "두 번째 취소"));
		// when / then: 두 자리 중 한 자리만 확정된 명단을 먼저 공개하지 않는다.
		assertThatThrownBy(() -> confirmations.confirmReplacement(run, actorId)).isInstanceOf(BusinessException.class);
		assertThat(checked).isEmpty();
		DrawResult unfilled = new DrawResult(UUID.randomUUID(), new DrawSelection(prizeId, 1, 2, null,
				null, null, DrawSelectionResultType.UNFILLED), NOW);
		runs.set(0, new DrawPublicationRun(run, eventId, 1, DrawRunStatus.CONFIRMED, NOW,
				List.of(selected(1, userC), unfilled), null));
		confirmations.confirmReplacement(run, actorId);
		assertThat(history).hasSize(1);
		assertThat(latest.getEntries()).hasSize(1);
		assertThat(latest.getReason()).isEqualTo("취소 사유 1\n두 번째 취소");
	}

	/** 빈 자리 미충원 확정도 관리자 확인 후 취소 당첨자만 명단에서 제거한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("빈 자리 미충원 확정도 관리자 확인 후 취소 당첨자만 명단에서 제거한다")
	void publishesUnfilledReplacementAfterConfirmation() {
		// given
		publications.publishInitial(eventId);
		DrawResult unfilled = new DrawResult(UUID.randomUUID(), new DrawSelection(prizeId, 1, 1, null, null, null,
				DrawSelectionResultType.UNFILLED), NOW);
		UUID run = addRun(1, resultA, DrawRunStatus.CONFIRMED, List.of(unfilled));
		// when / then
		assertThat(history).hasSize(1);
		confirmations.confirmReplacement(run, actorId);
		assertThat(latest.getEntries()).containsExactly(new DrawPublication.Entry(resultB.getId(), initialRun, null));
		assertThat(latest.getRevision()).isEqualTo(2);
		assertThat(publications.wasPublished(unfilled.getId())).isFalse();
		org.mockito.ArgumentCaptor<List<DrawPublication.Execution>> evidence = org.mockito.ArgumentCaptor.forClass(List.class);
		verify(repository).saveExecutionEvidence(eq(latest.getId()), eq(eventId), evidence.capture());
		assertThat(evidence.getValue()).hasSize(1);
		assertThat(evidence.getValue().getFirst().getRunId()).isEqualTo(run);
		assertThat(evidence.getValue().getFirst().getConfirmationId()).isEqualTo(checked.get(run).getId());
		assertThat(evidence.getValue().getFirst().getConfirmedAt()).isEqualTo(checked.get(run).getConfirmedAt());
	}

	/** 공개 저장 실패는 Spring 확인 트랜잭션을 롤백하고 재요청에 한 번만 반영한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("공개 저장 실패는 Spring 확인 트랜잭션을 롤백하고 재요청에 한 번만 반영한다")
	void rollsBackConfirmationAndPublicationInMemory() {
		// given
		DrawPublication first = publications.publishInitial(eventId).orElseThrow();
		UUID run = addRun(1, resultA, DrawRunStatus.CONFIRMED, List.of(selected(1, userC)));
		doThrow(new IllegalStateException("공개 저장 대역 실패")).when(repository)
				.savePublication(eq(eventId), any(), anyInt(), any(), any(), anyList(), any());
		// when / then
		assertThatThrownBy(() -> confirmations.confirmReplacement(run, actorId)).isInstanceOf(IllegalStateException.class);
		assertThat(checked).isEmpty();
		assertThat(latest).isEqualTo(first);
		assertThat(history).hasSize(1);
		verify(notifications).register(first);
		doAnswer(invocation -> save(invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3),
				invocation.getArgument(4), invocation.getArgument(5), invocation.getArgument(6))).when(repository)
				.savePublication(eq(eventId), any(), anyInt(), any(), any(), anyList(), any());
		DrawResultConfirmation retried = confirmations.confirmReplacement(run, actorId);
		assertThat(confirmations.confirmReplacement(run, actorId)).isEqualTo(retried);
		assertThat(checked).hasSize(1);
		assertThat(history).hasSize(2);
	}

	/** 메모리 이벤트 잠금에서 확인·최초 공개의 동시 요청은 한 확인·한 공개 버전만 만든다는 시나리오를 검증한다. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("메모리 이벤트 잠금에서 확인·최초 공개의 동시 요청은 한 확인·한 공개 버전만 만든다")
	void concurrentConfirmationRequests(boolean initialPublication) throws Exception {
		// given
		if (!initialPublication) publications.publishInitial(eventId);
		UUID run = addRun(1, resultA, DrawRunStatus.CONFIRMED, List.of(selected(1, userC)));
		CountDownLatch recorded = new CountDownLatch(1), release = new CountDownLatch(1);
		doAnswer(invocation -> {
			DrawResultConfirmation confirmation = new DrawResultConfirmation(UUID.randomUUID(), run, actorId, NOW);
			checked.put(run, confirmation);
			recorded.countDown();
			if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("확인 잠금 해제 대기 실패");
			return confirmation;
		}).when(repository).saveConfirmation(eq(run), eq(actorId), any());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			// when
			Future<DrawResultConfirmation> first = executor.submit(() -> confirmations.confirmReplacement(run, actorId));
			assertThat(recorded.await(10, TimeUnit.SECONDS)).isTrue();
			Future<Object> second = executor.submit(() -> initialPublication
					? publications.publishInitial(eventId).orElseThrow() : confirmations.confirmReplacement(run, actorId));
			assertThat(transactions.waiting.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(second.isDone()).isFalse();
			release.countDown();
			// then
			DrawResultConfirmation firstResult = first.get(10, TimeUnit.SECONDS);
			assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(initialPublication ? latest : firstResult);
			assertThat(checked).hasSize(1);
			assertThat(history).hasSize(initialPublication ? 1 : 2);
			verify(notifications, times(initialPublication ? 1 : 2)).register(any());
			verify(repository, times(1)).saveConfirmation(eq(run), eq(actorId), any());
		} finally {
			release.countDown(); executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	/** 비관리자·비활성 관리자와 취소 이벤트·미확정 실행의 확인을 거절한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("비관리자·비활성 관리자와 취소 이벤트·미확정 실행의 확인을 거절한다")
	void rejectsUnauthorizedAndUnconfirmableRequests() {
		// given
		UUID run = addRun(1, resultA, DrawRunStatus.READY, List.of());
		// when / then
		assertThatThrownBy(() -> confirmations.confirmReplacement(run, actorId)).isInstanceOf(BusinessException.class);
		when(users.findById(actorId)).thenReturn(Optional.of(actor(UserRole.USER, UserStatus.ACTIVE)));
		assertThatThrownBy(() -> confirmations.confirmReplacement(run, actorId)).isInstanceOf(BusinessException.class);
		when(users.findById(actorId)).thenReturn(Optional.of(actor(UserRole.ADMIN, UserStatus.INACTIVE)));
		assertThatThrownBy(() -> confirmations.confirmReplacement(run, actorId)).isInstanceOf(BusinessException.class);
		when(users.findById(actorId)).thenReturn(Optional.of(actor(UserRole.ADMIN, UserStatus.ACTIVE)));
		eventStatus = EventStatus.CANCELED;
		assertThatThrownBy(() -> confirmations.confirmReplacement(run, actorId)).isInstanceOf(BusinessException.class);
		verify(repository, never()).saveConfirmation(any(), any(), any());
	}

	/** 유지 당첨자 재선정·틀린 자리·빈 취소 근거·다른 이벤트 결과를 공개하지 않는다는 시나리오를 검증한다. */
	@Test
	@DisplayName("유지 당첨자 재선정·틀린 자리·빈 취소 근거·다른 이벤트 결과를 공개하지 않는다")
	void rejectsInvalidReplacementRelations() {
		// given
		UUID run = addRun(1, resultA, DrawRunStatus.CONFIRMED, List.of(selected(1, userB)));
		// when / then
		assertThatThrownBy(() -> confirmations.confirmReplacement(run, actorId)).isInstanceOf(BusinessException.class);
		runs.set(0, new DrawPublicationRun(run, eventId, 1, DrawRunStatus.CONFIRMED, NOW, List.of(selected(1, userA)), null));
		assertThatThrownBy(() -> confirmations.confirmReplacement(run, actorId)).isInstanceOf(BusinessException.class);
		runs.set(0, new DrawPublicationRun(run, eventId, 1, DrawRunStatus.CONFIRMED, NOW, List.of(selected(2, userC)), null));
		assertThatThrownBy(() -> confirmations.confirmReplacement(run, actorId)).isInstanceOf(BusinessException.class);
		runs.set(0, new DrawPublicationRun(run, UUID.randomUUID(), 1, DrawRunStatus.CONFIRMED, NOW, List.of(selected(1, userC)), null));
		assertThatThrownBy(() -> confirmations.confirmReplacement(run, actorId)).isInstanceOf(BusinessException.class);
		runs.set(0, new DrawPublicationRun(run, eventId, 1, DrawRunStatus.CONFIRMED, NOW, List.of(selected(1, userC)), null));
		cancellations.clear();
		assertThatThrownBy(() -> confirmations.confirmReplacement(run, actorId)).isInstanceOf(BusinessException.class);
		verify(repository, never()).saveConfirmation(any(), any(), any());
	}

	/** 시험용 재추첨 실행과 그 원인이 된 취소의 연결을 메모리에 등록한다. */
	private UUID addRun(int number, DrawResult canceled, DrawRunStatus status, List<DrawResult> selected) {
		UUID id = UUID.randomUUID();
		runs.add(new DrawPublicationRun(id, eventId, number, status, status == DrawRunStatus.CONFIRMED ? NOW : null, selected, null));
		cancellations.add(new DrawPublication.Cancellation(UUID.randomUUID(), canceled.getId(), id, "취소 사유 " + number));
		return id;
	}
	/** 시험용 후보에 연결된 지정 자리의 선정 결과를 만든다. */
	private DrawResult selected(int slot, UUID user) {
		return new DrawResult(UUID.randomUUID(), new DrawSelection(prizeId, 1, slot, slot,
				candidates.get(user), user, DrawSelectionResultType.SELECTED), NOW);
	}
	/** 지정 역할과 상태를 가진 시험용 관리자 문맥을 구성한다. */
	private User actor(UserRole role, UserStatus status) {
		return new User(actorId, "관리자", role, status, Membership.VIP, null, null, null, null, NOW, NOW);
	}
	/** 시험용 공개 저장 대역에 새 버전과 변경 이력을 보존한다. */
	private DrawPublication save(UUID source, int revision, String reason, DrawResultConfirmation confirmation,
			List<DrawPublication.Entry> entries, Instant publishedAt) {
		latest = new DrawPublication(UUID.randomUUID(), eventId, source, revision,
				revision == 1 ? DrawPublicationType.INITIAL : DrawPublicationType.UPDATE, reason,
				confirmation == null ? null : confirmation.getConfirmedBy(),
				confirmation == null ? null : confirmation.getConfirmedAt(), publishedAt, entries);
		history.add(latest);
		return latest;
	}
	/** 시험용 트랜잭션 관리자를 적용한 Spring 서비스 프록시를 구성한다. */
	@SuppressWarnings("unchecked")
	private <T> T proxy(T target) {
		ProxyFactory factory = new ProxyFactory(target);
		factory.setProxyTargetClass(true);
		TransactionInterceptor interceptor = new TransactionInterceptor();
		interceptor.setTransactionManager(transactions);
		interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
		factory.addAdvice(interceptor);
		return (T) factory.getProxy();
	}

	/** 서비스의 트랜잭션 경계와 이벤트 선잠금 계약을 재현하는 시험용 대역. 실제 JDBC 검증과 구분한다. */
	private final class MemoryTransactions extends AbstractPlatformTransactionManager {
		private final ThreadLocal<MemoryTransaction> current = new ThreadLocal<>();
		private final ReentrantLock eventLock = new ReentrantLock();
		private final CountDownLatch waiting = new CountDownLatch(1);
		/** 현재 스레드의 시험용 트랜잭션을 재사용하거나 새 트랜잭션 상태를 반환한다. */
		@Override protected Object doGetTransaction() {
			MemoryTransaction existing = current.get();
			return existing == null ? new MemoryTransaction() : existing;
		}
		/** 시험용 트랜잭션이 이미 활성 상태인지 확인한다. */
		@Override protected boolean isExistingTransaction(Object transaction) { return ((MemoryTransaction) transaction).active; }
		/** 시험용 트랜잭션을 활성화하고 현재 스레드에 연결한다. */
		@Override protected void doBegin(Object transaction, TransactionDefinition definition) {
			MemoryTransaction state = (MemoryTransaction) transaction;
			state.active = true; current.set(state);
		}
		/** 시험용 이벤트 잠금과 롤백 복원을 위한 현재 확인·공개 상태 사본을 준비한다. */
		void lockEvent() {
			MemoryTransaction state = current.get();
			assertThat(state).isNotNull();
			if (state.locked) return;
			if (!eventLock.tryLock()) { waiting.countDown(); eventLock.lock(); }
			state.locked = true;
			state.checked = new HashMap<>(checked); state.latest = latest; state.history = new ArrayList<>(history);
		}
		/** 메모리 변경은 이미 반영돼 있으므로 시험용 커밋에 추가 작업을 하지 않는다. */
		@Override protected void doCommit(DefaultTransactionStatus status) { }
		/** 잠금 획득 시 보존한 확인·공개 버전·이력을 메모리 대역에 복원한다. */
		@Override protected void doRollback(DefaultTransactionStatus status) {
			MemoryTransaction state = (MemoryTransaction) status.getTransaction();
			if (!state.locked) return;
			checked.clear(); checked.putAll(state.checked); latest = state.latest;
			history.clear(); history.addAll(state.history);
		}
		/** 시험용 트랜잭션을 롤백 전용으로 표시한다. */
		@Override protected void doSetRollbackOnly(DefaultTransactionStatus status) {
			((MemoryTransaction) status.getTransaction()).rollbackOnly = true;
		}
		/** 시험용 이벤트 잠금을 해제하고 현재 스레드의 트랜잭션 상태를 정리한다. */
		@Override protected void doCleanupAfterCompletion(Object transaction) {
			MemoryTransaction state = (MemoryTransaction) transaction;
			if (state.locked) eventLock.unlock();
			state.active = false; current.remove();
		}
	}
	private static final class MemoryTransaction implements org.springframework.transaction.support.SmartTransactionObject {
		private boolean active, locked;
		private boolean rollbackOnly;
		private Map<UUID, DrawResultConfirmation> checked;
		private DrawPublication latest;
		private List<DrawPublication> history;
		/** 시험용 트랜잭션의 롤백 전용 표시를 반환한다. */
		@Override public boolean isRollbackOnly() { return rollbackOnly; }
		/** 메모리 대역에는 영속성 flush 작업이 없으므로 추가 작업을 하지 않는다. */
		@Override public void flush() { }
	}
}
