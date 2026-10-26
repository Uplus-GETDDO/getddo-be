package com.getddo.core.notification.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.TransientDataAccessResourceException;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.notification.domain.MockDeliveryStatus;
import com.getddo.core.notification.domain.NotificationDelivery;
import com.getddo.core.notification.domain.NotificationDeliveryResult;
import com.getddo.core.notification.domain.NotificationJob;
import com.getddo.core.notification.domain.NotificationJobRequest;
import com.getddo.core.notification.domain.NotificationType;
import com.getddo.core.notification.exception.NotificationProcessingErrorCode;
import com.getddo.core.notification.exception.NotificationProcessingException;
import com.getddo.core.notification.repository.NotificationJobRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class NotificationJobServiceTest {
	private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
	private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final NotificationJob JOB = new NotificationJob(UUID.randomUUID(), 1);
	private static final NotificationDelivery DELIVERY = new NotificationDelivery(UUID.randomUUID(), FIRST,
			"제목", "내용", null, 1);

	@Mock private NotificationJobRepository repository;
	@Mock private MockNotificationSender sender;
	@Mock private NotificationBatchTransactionService batches;
	private NotificationJobService service;

	/** 서비스가 사용할 고정 업무 시각을 준비한다. */
	@BeforeEach
	void setUp() {
		service = new NotificationJobService(repository, new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)), sender, batches);
	}

	/** 검증 시나리오: 묶음 생성 중 일시적 실패는 후속 처리를 멈추고 별도 실패 기록과 재시도로 넘긴다. */
	@Test
	@DisplayName("첫 묶음 이후 실패하면 남은 묶음은 생성하지 않고 기존 재시도로 넘긴다")
	void recordsGenerationFailureWithoutSending(CapturedOutput output) {
		// given
		NotificationJobRequest request = request(recipients(201));
		when(repository.claimNextJob(NOW)).thenReturn(Optional.of(JOB));
		when(repository.findRequest(JOB.getId())).thenReturn(request);
		when(batches.createNotifications(JOB, request, request.getRecipientIds().subList(0, 100), NOW)).thenReturn(true);
		when(batches.createNotifications(JOB, request, request.getRecipientIds().subList(100, 200), NOW))
				.thenThrow(new TransientDataAccessResourceException("private details"));

		// when / then
		assertThat(service.processNextJob()).isTrue();
		verify(repository).failJob(JOB, NOW.plusSeconds(10), "NOTIFICATION-005");
		verify(repository, never()).completeJob(JOB, NOW);
		verify(batches, times(2)).createNotifications(any(), any(), any(), any());
		verifyNoInteractions(sender);
		assertThat(output.getOut()).contains(JOB.getId().toString(), "attempt=1",
				TransientDataAccessResourceException.class.getName()).doesNotContain("private details", "제목", "내용");
	}

	/** 검증 시나리오: 선점이 교체되면 이전 처리자는 생성 완료나 실패를 덮어쓰지 않는다. */
	@Test
	@DisplayName("선점이 교체되면 이전 처리자는 생성 완료나 실패를 덮어쓰지 않는다")
	void stopsWhenClaimIsLost() {
		// given
		NotificationJobRequest request = request(List.of(FIRST));
		when(repository.claimNextJob(NOW)).thenReturn(Optional.of(JOB));
		when(repository.findRequest(JOB.getId())).thenReturn(request);
		when(batches.createNotifications(JOB, request, List.of(FIRST), NOW)).thenReturn(false);

		// when / then
		assertThat(service.processNextJob()).isTrue();
		verify(repository, never()).completeJob(any(), any());
		verify(repository, never()).failJob(any(), any(), any());
	}

	/** 검증 시나리오: 대상이 없는 작업도 완료하며 발송을 수행하지 않는다. */
	@Test
	@DisplayName("대상이 없는 작업도 완료하며 발송을 수행하지 않는다")
	void completesEmptyRecipientSet() {
		// given
		when(repository.claimNextJob(NOW)).thenReturn(Optional.of(JOB));
		when(repository.findRequest(JOB.getId())).thenReturn(request(List.of()));

		// when / then
		assertThat(service.processNextJob()).isTrue();
		verify(repository).completeJob(JOB, NOW);
		verifyNoInteractions(sender);
		verifyNoInteractions(batches);
	}

	/** 모든 묶음이 성공한 뒤에만 작업 완료를 기록하는지 경계 크기로 검사한다. */
	@Test
	@DisplayName("201명은 100·100·1명으로 나누고 모든 묶음이 성공한 뒤 완료한다")
	void generatesBoundedChunksBeforeCompleting() {
		// given
		NotificationJobRequest request = request(recipients(201));
		when(repository.claimNextJob(NOW)).thenReturn(Optional.of(JOB));
		when(repository.findRequest(JOB.getId())).thenReturn(request);
		when(batches.createNotifications(any(), any(), any(), any())).thenReturn(true);

		// when
		assertThat(service.processNextJob()).isTrue();

		// then
		InOrder order = inOrder(batches, repository);
		order.verify(repository).claimNextJob(NOW);
		order.verify(repository).findRequest(JOB.getId());
		order.verify(batches).createNotifications(JOB, request, request.getRecipientIds().subList(0, 100), NOW);
		order.verify(batches).createNotifications(JOB, request, request.getRecipientIds().subList(100, 200), NOW);
		order.verify(batches).createNotifications(JOB, request, request.getRecipientIds().subList(200, 201), NOW);
		order.verify(repository).completeJob(JOB, NOW);
		order.verifyNoMoreInteractions();
	}

	/** 개별 발송 실패가 뒤의 정상 대상을 막지 않고 각 시각·재시도 결과가 같은 묶음에 보존되는지 확인한다. */
	@Test
	@DisplayName("묶음 중 하나의 발송 실패가 다음 알림 발송과 각 결과 기록을 막지 않는다")
	void recordsDeliveryBatchOutcomesSeparately() {
		// given
		NotificationDelivery second = new NotificationDelivery(UUID.randomUUID(), SECOND, "제목", "내용", null, 1);
		when(batches.claimNextDeliveries(NOW, 100)).thenReturn(List.of(DELIVERY, second));
		when(sender.send(DELIVERY)).thenReturn(MockDeliveryStatus.FAILED);
		when(sender.send(second)).thenReturn(MockDeliveryStatus.SENT);

		// when
		assertThat(service.processNextDeliveries()).isEqualTo(2);

		// then
		assertThat(recordedResults()).extracting(NotificationDeliveryResult::getDelivery,
				NotificationDeliveryResult::getStatus, NotificationDeliveryResult::getSentAt,
				NotificationDeliveryResult::getNextAttemptAt, NotificationDeliveryResult::getErrorCode)
				.containsExactly(tuple(DELIVERY, MockDeliveryStatus.FAILED, null, NOW.plusSeconds(10), "NOTIFICATION-005"),
						tuple(second, MockDeliveryStatus.SENT, NOW, null, null));
		verifyNoInteractions(repository);
	}

	/** 결과 기록이 실패해도 뒤의 선점 항목을 발송하고 민감한 오류 원문을 남기지 않는다. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("묶음 저장 실패 후 같은 결과만 개별 저장하고 발송은 반복하지 않는다")
	void continuesAfterDeliveryBookkeepingFailure(boolean secondSent, CapturedOutput output) {
		// given
		NotificationDelivery second = new NotificationDelivery(UUID.randomUUID(), SECOND, "제목", "내용", null, 1);
		NotificationDelivery third = new NotificationDelivery(UUID.randomUUID(), new UUID(0, 3), "제목", "내용", null, 1);
		when(batches.claimNextDeliveries(NOW, 100)).thenReturn(List.of(DELIVERY, second, third));
		when(sender.send(DELIVERY)).thenReturn(MockDeliveryStatus.SENT);
		when(sender.send(second)).thenReturn(secondSent ? MockDeliveryStatus.SENT : MockDeliveryStatus.FAILED);
		when(sender.send(third)).thenReturn(MockDeliveryStatus.SENT);
		List<List<NotificationDeliveryResult>> writes = new ArrayList<>();
		doAnswer(invocation -> {
			List<NotificationDeliveryResult> results = invocation.getArgument(0);
			writes.add(List.copyOf(results));
			if (results.size() > 1 || results.getFirst().getDelivery() == second) {
				throw new TransientDataAccessResourceException("private bookkeeping details");
			}
			return null;
		}).when(batches).recordDeliveryResults(anyList(), eq(NOW.plusSeconds(60)));

		// when
		assertThat(service.processNextDeliveries()).isEqualTo(3);

		// then
		assertThat(writes).hasSize(4);
		assertThat(writes.getFirst()).extracting(NotificationDeliveryResult::getDelivery)
				.containsExactly(DELIVERY, second, third);
		for (int index = 0; index < 3; index++) {
			assertThat(writes.get(index + 1)).containsExactly(writes.getFirst().get(index));
		}
		assertThat(writes.get(2).getFirst().getStatus())
				.isEqualTo(secondSent ? MockDeliveryStatus.SENT : MockDeliveryStatus.FAILED);
		verify(sender).send(DELIVERY);
		verify(sender).send(second);
		verify(sender).send(third);
		verifyNoInteractions(repository);
		assertThat(output.getOut()).contains("Notification delivery bookkeeping failed", second.getId().toString(),
				"attempt=1", TransientDataAccessResourceException.class.getName())
				.doesNotContain("private bookkeeping details", "제목", "내용");
	}

	/** 선점과 발송에 시간이 걸려도 결과 기록은 최초 선점 만료 시각을 그대로 전달한다. */
	@Test
	@DisplayName("선점·발송에 쓴 시간을 결과 기록의 임대 예산에서 제외한다")
	void preservesOriginalDeadlineAcrossClaimAndSend() {
		AtomicReference<Instant> now = useAdvancingClock();
		when(batches.claimNextDeliveries(NOW, 100)).thenAnswer(invocation -> {
			now.set(NOW.plusSeconds(45));
			return List.of(DELIVERY);
		});
		when(sender.send(DELIVERY)).thenAnswer(invocation -> {
			now.set(NOW.plusSeconds(58));
			return MockDeliveryStatus.SENT;
		});

		assertThat(service.processNextDeliveries()).isEqualTo(1);
		assertThat(recordedResults().getFirst().getSentAt()).isEqualTo(NOW.plusSeconds(58));
	}

	/** 발송 후 1초 미만이 남으면 JDBC timeout을 올림하여 임대를 넘기지 않고 복구에 맡긴다. */
	@Test
	@DisplayName("선점 만료까지 1초 미만이면 결과 저장을 시작하지 않는다")
	void skipsRecordingWithLessThanOneSecondLeft() {
		AtomicReference<Instant> now = useAdvancingClock();
		when(batches.claimNextDeliveries(NOW, 100)).thenReturn(List.of(DELIVERY));
		when(sender.send(DELIVERY)).thenAnswer(invocation -> {
			now.set(NOW.plusMillis(59_500));
			return MockDeliveryStatus.SENT;
		});

		assertThat(service.processNextDeliveries()).isEqualTo(1);
		verify(batches, never()).recordDeliveryResults(anyList(), any());
		verify(sender).send(DELIVERY);
	}

	/** 묶음 오류가 선점 예산을 소진하면 추가 개별 트랜잭션을 열거나 다시 발송하지 않는다. */
	@Test
	@DisplayName("묶음 저장 실패 시 임대가 끝났으면 개별 저장을 시도하지 않는다")
	void stopsFallbackAfterBatchExhaustsDeadline() {
		AtomicReference<Instant> now = useAdvancingClock();
		when(batches.claimNextDeliveries(NOW, 100)).thenReturn(List.of(DELIVERY, DELIVERY));
		when(sender.send(DELIVERY)).thenReturn(MockDeliveryStatus.SENT);
		doAnswer(invocation -> {
			now.set(NOW.plusSeconds(60));
			throw new TransientDataAccessResourceException("synthetic timeout");
		}).when(batches).recordDeliveryResults(anyList(), eq(NOW.plusSeconds(60)));

		assertThat(service.processNextDeliveries()).isEqualTo(2);
		verify(batches).recordDeliveryResults(anyList(), eq(NOW.plusSeconds(60)));
		verify(sender, times(2)).send(DELIVERY);
	}

	/** 개별 저장이 시간을 소진해도 deadline을 연장하지 않고 나머지 결과는 임대 복구에 남긴다. */
	@Test
	@DisplayName("개별 저장 중 시간이 소진되면 남은 저장과 재발송을 중단한다")
	void stopsFallbackWhenIndividualWriteExhaustsDeadline() {
		AtomicReference<Instant> now = useAdvancingClock();
		NotificationDelivery second = new NotificationDelivery(UUID.randomUUID(), SECOND, "제목", "내용", null, 1);
		when(batches.claimNextDeliveries(NOW, 100)).thenReturn(List.of(DELIVERY, second));
		when(sender.send(any())).thenReturn(MockDeliveryStatus.SENT);
		List<List<NotificationDeliveryResult>> writes = new ArrayList<>();
		doAnswer(invocation -> {
			List<NotificationDeliveryResult> results = invocation.getArgument(0);
			writes.add(List.copyOf(results));
			if (results.size() == 2) {
				now.set(NOW.plusSeconds(58));
				throw new TransientDataAccessResourceException("synthetic batch failure");
			}
			now.set(NOW.plusSeconds(60));
			throw new TransientDataAccessResourceException("synthetic individual timeout");
		}).when(batches).recordDeliveryResults(anyList(), eq(NOW.plusSeconds(60)));

		assertThat(service.processNextDeliveries()).isEqualTo(2);
		assertThat(writes).hasSize(2);
		assertThat(writes.getLast()).containsExactly(writes.getFirst().getFirst());
		verify(sender).send(DELIVERY);
		verify(sender).send(second);
	}

	/** 고정 시계를 교체해 sleep 없이 선점·발송·저장 중의 시간 경과를 재현한다. */
	private AtomicReference<Instant> useAdvancingClock() {
		AtomicReference<Instant> now = new AtomicReference<>(NOW);
		Clock clock = mock(Clock.class);
		when(clock.instant()).thenAnswer(invocation -> now.get());
		service = new NotificationJobService(repository, new TimeProvider(clock), sender, batches);
		return now;
	}

	/** 상한까지 선점한 100건을 각각 한 번 발송하고 결과는 하나의 묶음으로 저장한다. */
	@Test
	@DisplayName("발송 100건을 한 회차에 선점하고 발송 결과도 100건 한 묶음으로 기록한다")
	void processesOneHundredDeliveriesInOneBatch() {
		// given
		List<NotificationDelivery> deliveries = IntStream.range(0, 100)
				.mapToObj(index -> new NotificationDelivery(new UUID(1, index), FIRST, "제목", "내용", null, 1)).toList();
		when(batches.claimNextDeliveries(NOW, 100)).thenReturn(deliveries);
		when(sender.send(any())).thenReturn(MockDeliveryStatus.SENT);
		// when / then
		assertThat(service.processNextDeliveries()).isEqualTo(100);
		assertThat(recordedResults()).hasSize(100)
				.allSatisfy(result -> assertThat(result.getStatus()).isEqualTo(MockDeliveryStatus.SENT));
		verify(sender, times(100)).send(any());
		verifyNoInteractions(repository);
	}

	/** 선점할 알림이 없는 폴링에서는 발송이나 결과 기록을 실행하지 않는다. */
	@Test
	@DisplayName("묶음 발송 대상이 없으면 발송기를 호출하지 않는다")
	void skipsSendingEmptyDeliveryBatch() {
		// given
		when(batches.claimNextDeliveries(NOW, 100)).thenReturn(List.of());

		// when / then
		assertThat(service.processNextDeliveries()).isZero();
		verifyNoInteractions(sender, repository);
		verify(batches, never()).recordDeliveryResults(anyList(), eq(NOW.plusSeconds(60)));
	}

	/** 검증 시나리오: 모의 발송 성공은 기존 알림의 발송 결과만 저장한다. */
	@Test
	@DisplayName("모의 발송 성공은 기존 알림의 발송 결과만 저장한다")
	void completesDeliverySeparately() {
		// given
		when(repository.claimNextDelivery(NOW)).thenReturn(Optional.of(DELIVERY));
		when(sender.send(DELIVERY)).thenReturn(MockDeliveryStatus.SENT);

		// when / then
		assertThat(service.processNextDelivery()).isTrue();
		NotificationDeliveryResult result = recordedResults().getFirst();
		assertThat(result.getDelivery()).isSameAs(DELIVERY);
		assertThat(result.getStatus()).isEqualTo(MockDeliveryStatus.SENT);
		assertThat(result.getSentAt()).isEqualTo(NOW);
		verify(repository, never()).claimNextJob(NOW);
	}

	/** 검증 시나리오: 모의 발송 실패 결과는 생성 재시도와 구분해서 10초 뒤 재시도한다. */
	@Test
	@DisplayName("모의 발송 실패 결과는 생성 재시도와 구분해서 10초 뒤 재시도한다")
	void retriesFailedMockDelivery() {
		// given
		when(repository.claimNextDelivery(NOW)).thenReturn(Optional.of(DELIVERY));
		when(sender.send(DELIVERY)).thenReturn(MockDeliveryStatus.FAILED);

		// when / then
		assertThat(service.processNextDelivery()).isTrue();
		NotificationDeliveryResult result = recordedResults().getFirst();
		assertThat(result.getStatus()).isEqualTo(MockDeliveryStatus.FAILED);
		assertThat(result.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
		assertThat(result.getErrorCode()).isEqualTo("NOTIFICATION-005");
	}

	/** 검증 시나리오: 모의 발송 입력 오류는 즉시 최종 실패 처리한다. */
	@Test
	@DisplayName("모의 발송 입력 오류는 즉시 최종 실패 처리한다")
	void doesNotRetryInvalidDelivery() {
		// given
		when(repository.claimNextDelivery(NOW)).thenReturn(Optional.of(DELIVERY));
		when(sender.send(DELIVERY)).thenThrow(new NotificationProcessingException(NotificationProcessingErrorCode.INVALID_JOB));

		// when / then
		assertThat(service.processNextDelivery()).isTrue();
		NotificationDeliveryResult result = recordedResults().getFirst();
		assertThat(result.getNextAttemptAt()).isNull();
		assertThat(result.getErrorCode()).isEqualTo("NOTIFICATION-003");
	}

	/** 검증 시나리오: 예상하지 못한 발송 오류도 원문 없이 추적하고 최종 실패로 기록한다. */
	@Test
	@DisplayName("발송 실패 로그는 알림 ID·차수·예외 유형만 남기고 원문을 노출하지 않는다")
	void logsDeliveryFailureWithoutPrivateDetails(CapturedOutput output) {
		// given
		when(repository.claimNextDelivery(NOW)).thenReturn(Optional.of(DELIVERY));
		when(sender.send(DELIVERY)).thenThrow(new IllegalArgumentException("private delivery details"));
		// when
		assertThat(service.processNextDelivery()).isTrue();
		// then
		NotificationDeliveryResult result = recordedResults().getFirst();
		assertThat(result.getNextAttemptAt()).isNull();
		assertThat(result.getErrorCode()).isEqualTo("NOTIFICATION-006");
		assertThat(output.getOut()).contains(DELIVERY.getId().toString(), "attempt=1",
				IllegalArgumentException.class.getName()).doesNotContain("private delivery details", "제목", "내용");
	}

	/** 묶음에 보존한 결과를 꺼내 발송 상태·시각·재시도 정책을 검증한다. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private List<NotificationDeliveryResult> recordedResults() {
		ArgumentCaptor<List<NotificationDeliveryResult>> captor = ArgumentCaptor.forClass(List.class);
		verify(batches).recordDeliveryResults(captor.capture(), eq(NOW.plusSeconds(60)));
		return captor.getValue();
	}

	/** 각 시나리오에서 발생 키와 수신 대상을 지정할 유효한 알림 작업 입력을 만든다. */
	private static NotificationJobRequest request(List<UUID> users) {
		return new NotificationJobRequest("발생-1", NotificationType.RESULT_CHANGED,
				null, null, "제목", "내용", null, NOW, users);
	}

	/** UUID 순서가 고정된 수신자를 만들어 묶음 경계를 검증한다. */
	private static List<UUID> recipients(int count) {
		return IntStream.rangeClosed(1, count).mapToObj(index -> new UUID(0, index)).toList();
	}
}
