package com.getddo.core.notification.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.TransientDataAccessResourceException;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.notification.domain.MockDeliveryStatus;
import com.getddo.core.notification.domain.NotificationDelivery;
import com.getddo.core.notification.domain.NotificationJob;
import com.getddo.core.notification.domain.NotificationJobRequest;
import com.getddo.core.notification.domain.NotificationType;
import com.getddo.core.notification.exception.NotificationProcessingErrorCode;
import com.getddo.core.notification.exception.NotificationProcessingException;
import com.getddo.core.notification.repository.NotificationJobRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;
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

	/** 개별 발송 실패가 뒤의 정상 대상까지 중단시키지 않는지 확인한다. */
	@Test
	@DisplayName("묶음 중 하나의 발송 실패가 다음 알림 발송과 각 결과 기록을 막지 않는다")
	void recordsDeliveryBatchOutcomesSeparately() {
		// given
		NotificationDelivery second = new NotificationDelivery(UUID.randomUUID(), SECOND, "제목", "내용", null, 1);
		when(batches.claimNextDeliveries(NOW, 20)).thenReturn(List.of(DELIVERY, second));
		when(sender.send(DELIVERY)).thenReturn(MockDeliveryStatus.FAILED);
		when(sender.send(second)).thenReturn(MockDeliveryStatus.SENT);

		// when
		assertThat(service.processNextDeliveries()).isEqualTo(2);

		// then
		verify(repository).failDelivery(DELIVERY, NOW.plusSeconds(10), "NOTIFICATION-005");
		verify(repository).completeDelivery(second, NOW);
		verify(repository, never()).claimNextDelivery(any());
		verify(repository, never()).claimNextJob(any());
	}

	/** 결과 기록이 실패해도 뒤의 선점 항목을 발송하고 민감한 오류 원문을 남기지 않는다. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("두 번째 알림의 결과 기록이 실패해도 세 번째 알림은 발송한다")
	void continuesAfterDeliveryBookkeepingFailure(boolean completionFails, CapturedOutput output) {
		// given
		NotificationDelivery second = new NotificationDelivery(UUID.randomUUID(), SECOND, "제목", "내용", null, 1);
		NotificationDelivery third = new NotificationDelivery(UUID.randomUUID(), new UUID(0, 3), "제목", "내용", null, 1);
		when(batches.claimNextDeliveries(NOW, 20)).thenReturn(List.of(DELIVERY, second, third));
		when(sender.send(DELIVERY)).thenReturn(MockDeliveryStatus.SENT);
		when(sender.send(second)).thenReturn(completionFails ? MockDeliveryStatus.SENT : MockDeliveryStatus.FAILED);
		when(sender.send(third)).thenReturn(MockDeliveryStatus.SENT);
		if (completionFails) {
			doThrow(new TransientDataAccessResourceException("private completion details"))
					.when(repository).completeDelivery(second, NOW);
		}
		doThrow(new TransientDataAccessResourceException("private bookkeeping details"))
				.when(repository).failDelivery(second, NOW.plusSeconds(10), "NOTIFICATION-005");

		// when
		assertThat(service.processNextDeliveries()).isEqualTo(3);

		// then
		InOrder order = inOrder(sender, repository);
		order.verify(sender).send(DELIVERY);
		order.verify(repository).completeDelivery(DELIVERY, NOW);
		order.verify(sender).send(second);
		if (completionFails) {
			order.verify(repository).completeDelivery(second, NOW);
		}
		order.verify(repository).failDelivery(second, NOW.plusSeconds(10), "NOTIFICATION-005");
		order.verify(sender).send(third);
		order.verify(repository).completeDelivery(third, NOW);
		assertThat(output.getOut()).contains("Notification delivery bookkeeping failed", second.getId().toString(),
				"attempt=1", TransientDataAccessResourceException.class.getName())
				.doesNotContain("private completion details", "private bookkeeping details", "제목", "내용");
	}

	/** 선점할 알림이 없는 폴링에서는 발송이나 결과 기록을 실행하지 않는다. */
	@Test
	@DisplayName("묶음 발송 대상이 없으면 발송기를 호출하지 않는다")
	void skipsSendingEmptyDeliveryBatch() {
		// given
		when(batches.claimNextDeliveries(NOW, 20)).thenReturn(List.of());

		// when / then
		assertThat(service.processNextDeliveries()).isZero();
		verifyNoInteractions(sender, repository);
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
		verify(repository).completeDelivery(DELIVERY, NOW);
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
		verify(repository).failDelivery(DELIVERY, NOW.plusSeconds(10), "NOTIFICATION-005");
		verify(repository, never()).completeDelivery(DELIVERY, NOW);
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
		verify(repository).failDelivery(DELIVERY, null, "NOTIFICATION-003");
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
		verify(repository).failDelivery(DELIVERY, null, "NOTIFICATION-006");
		assertThat(output.getOut()).contains(DELIVERY.getId().toString(), "attempt=1",
				IllegalArgumentException.class.getName()).doesNotContain("private delivery details", "제목", "내용");
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
