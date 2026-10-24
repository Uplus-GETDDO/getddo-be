package com.getddo.core.notification.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.notification.domain.MockDeliveryStatus;
import com.getddo.core.notification.domain.NotificationDelivery;
import com.getddo.core.notification.domain.NotificationDeliveryResult;
import com.getddo.core.notification.domain.NotificationJob;
import com.getddo.core.notification.domain.NotificationJobRequest;
import com.getddo.core.notification.domain.NotificationRetryPolicy;
import com.getddo.core.notification.exception.NotificationProcessingErrorCode;
import com.getddo.core.notification.exception.NotificationProcessingException;
import com.getddo.core.notification.repository.NotificationJobRepository;

/**
 * DB에 저장된 알림 작업을 처리하고 생성·모의 발송의 재시도를 분리한다.
 *
 * <p>등록은 원본 업무 트랜잭션에 참여하므로 롤백된 발생 사실의 알림은 남지 않는다.
 * 처리는 저장소의 별도 트랜잭션들로 진행하며 이미 확정된 원본 결과를 변경하지 않는다.
 * 묶음 저장 중 실패해도 앞서 확정한 묶음의 알림은 유지된다.</p>
 */
@Service
@RequiredArgsConstructor
public class NotificationJobService {
	private static final Log LOG = LogFactory.getLog(NotificationJobService.class);
	private static final int GENERATION_BATCH_SIZE = 100;
	private final NotificationJobRepository repository;
	private final TimeProvider timeProvider;
	private final MockNotificationSender sender;
	private final NotificationBatchTransactionService batches;

	/** 동일 발생 키는 동일 입력에만 재사용한다. 서로 다른 업무 유형도 키가 충돌하지 않아야 한다. */
	@Transactional
	public UUID register(NotificationJobRequest request) {
		if (request == null) {
			throw new NotificationProcessingException(NotificationProcessingErrorCode.INVALID_JOB);
		}
		return repository.register(request, timeProvider.now());
	}

	/** 작업 하나를 선점·처리한다. 대상 작업이 없으면 false, 선점한 경우 true다. */
	public boolean processNextJob() {
		NotificationJob job = repository.claimNextJob(timeProvider.now()).orElse(null);
		if (job == null) {
			return false;
		}
		try {
			NotificationJobRequest request = repository.findRequest(job.getId());
			List<UUID> recipients = request.getRecipientIds();
			for (int offset = 0; offset < recipients.size(); offset += GENERATION_BATCH_SIZE) {
				List<UUID> chunk = recipients.subList(offset, Math.min(offset + GENERATION_BATCH_SIZE, recipients.size()));
				if (!batches.createNotifications(job, request, chunk, timeProvider.now())) {
					return true;
				}
			}
			repository.completeJob(job, timeProvider.now());
		} catch (RuntimeException failure) {
			LOG.warn("Notification generation failed: jobId=" + job.getId()
					+ ", attempt=" + job.getAttemptCount() + ", exceptionType=" + failure.getClass().getName());
			repository.failJob(job, NotificationRetryPolicy.nextAttemptAt(
					job.getCycleAttemptCount(), timeProvider.now(), failure), NotificationRetryPolicy.errorCode(failure));
		}
		return true;
	}

	/** 기존 알림 하나만 모의 발송한다. 재시도로 새 알림을 생성하거나 읽음 처리하지 않는다. */
	public boolean processNextDelivery() {
		NotificationDelivery delivery = repository.claimNextDelivery(timeProvider.now()).orElse(null);
		if (delivery == null) {
			return false;
		}
		deliver(List.of(delivery));
		return true;
	}

	/** 최대 100건을 함께 선점하고 각 발송 결과를 하나의 묶음으로 저장한다. */
	public int processNextDeliveries() {
		List<NotificationDelivery> deliveries = batches.claimNextDeliveries(timeProvider.now(),
				NotificationJobRepository.MAX_DELIVERY_CLAIM);
		deliver(deliveries);
		return deliveries.size();
	}

	/** 묶음 저장 실패 후에는 같은 결과만 개별 새 트랜잭션으로 저장하며 발송기를 다시 호출하지 않는다. */
	private void deliver(List<NotificationDelivery> deliveries) {
		if (deliveries.isEmpty()) {
			return;
		}
		List<NotificationDeliveryResult> results = new ArrayList<>(deliveries.size());
		for (NotificationDelivery delivery : deliveries) {
			results.add(send(delivery));
		}
		try {
			batches.recordDeliveryResults(results);
		} catch (RuntimeException failure) {
			LOG.warn("Notification delivery batch recording failed: count=" + results.size()
					+ ", exceptionType=" + failure.getClass().getName());
			for (NotificationDeliveryResult result : results) {
				try {
					batches.recordDeliveryResults(List.of(result));
				} catch (RuntimeException recordFailure) {
					// 개별 기록도 실패한 항목만 기존 임대 만료 후 복구한다.
					LOG.warn("Notification delivery bookkeeping failed: notificationId=" + result.getDelivery().getId()
							+ ", attempt=" + result.getDelivery().getAttemptCount()
							+ ", exceptionType=" + recordFailure.getClass().getName());
				}
			}
		}
	}

	/** 성공 시각 또는 기존 재시도 정책에 따른 실패 결과를 고정한다. DB 기록은 별도 단계다. */
	private NotificationDeliveryResult send(NotificationDelivery delivery) {
		try {
			if (sender.send(delivery) != MockDeliveryStatus.SENT) {
				throw new NotificationProcessingException(NotificationProcessingErrorCode.TEMPORARY_FAILURE);
			}
			return NotificationDeliveryResult.sent(delivery, timeProvider.now());
		} catch (RuntimeException failure) {
			LOG.warn("Notification delivery failed: notificationId=" + delivery.getId()
					+ ", attempt=" + delivery.getAttemptCount() + ", exceptionType=" + failure.getClass().getName());
			return NotificationDeliveryResult.failed(delivery, NotificationRetryPolicy.nextAttemptAt(
					delivery.getCycleAttemptCount(), timeProvider.now(), failure), NotificationRetryPolicy.errorCode(failure));
		}
	}
}
