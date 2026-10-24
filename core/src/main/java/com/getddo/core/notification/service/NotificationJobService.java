package com.getddo.core.notification.service;

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
	private static final int DELIVERY_BATCH_SIZE = 20;
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
		deliver(delivery);
		return true;
	}

	/** 폴링 상한 20건을 함께 선점하고 각 알림의 성공·실패는 독립적으로 기록한다. */
	public int processNextDeliveries() {
		List<NotificationDelivery> deliveries = batches.claimNextDeliveries(timeProvider.now(), DELIVERY_BATCH_SIZE);
		for (NotificationDelivery delivery : deliveries) {
			deliver(delivery);
		}
		return deliveries.size();
	}

	/** 기존 재시도 횟수·기준 차수를 유지하며 생성과 분리해 발송 결과만 확정한다. */
	private void deliver(NotificationDelivery delivery) {
		try {
			if (sender.send(delivery) != MockDeliveryStatus.SENT) {
				throw new NotificationProcessingException(NotificationProcessingErrorCode.TEMPORARY_FAILURE);
			}
			repository.completeDelivery(delivery, timeProvider.now());
		} catch (RuntimeException failure) {
			LOG.warn("Notification delivery failed: notificationId=" + delivery.getId()
					+ ", attempt=" + delivery.getAttemptCount() + ", exceptionType=" + failure.getClass().getName());
			repository.failDelivery(delivery, NotificationRetryPolicy.nextAttemptAt(
					delivery.getCycleAttemptCount(), timeProvider.now(), failure), NotificationRetryPolicy.errorCode(failure));
		}
	}
}
