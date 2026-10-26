package com.getddo.core.notification.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.notification.domain.NotificationDelivery;
import com.getddo.core.notification.domain.NotificationDeliveryResult;
import com.getddo.core.notification.domain.NotificationJob;
import com.getddo.core.notification.domain.NotificationJobRequest;
import com.getddo.core.notification.repository.NotificationJobRepository;

/** 묶음 저장과 선점을 독립적으로 확정해 실패한 묶음이 앞선 알림을 롤백하지 않도록 한다. */
@Service
@RequiredArgsConstructor
public class NotificationBatchTransactionService {
	private final NotificationJobRepository repository;
	private final PlatformTransactionManager transactionManager;
	private final TimeProvider timeProvider;

	/** 잠금 확인·알림 저장·진행 위치 갱신을 함께 확정하며 실패한 묶음은 전체 롤백한다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean createNotifications(NotificationJob job, NotificationJobRequest request, List<UUID> userIds,
			Instant now) {
		return repository.createNotifications(job, request, userIds, now);
	}

	/** 발송 전에 묶음의 처리 차수와 임대를 확정하고 잠금은 발송 중에 유지하지 않는다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<NotificationDelivery> claimNextDeliveries(Instant now, int limit) {
		return repository.claimNextDeliveries(now, limit);
	}

	/** 남은 임대·5초 중 작은 timeout으로 새 트랜잭션을 열고 연결 획득 후 SQL 예산을 다시 구한다. */
	public void recordDeliveryResults(List<NotificationDeliveryResult> results, Instant deadline) {
		if (results.isEmpty()) {
			return;
		}
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		transaction.setTimeout(remainingTimeout(deadline));
		transaction.executeWithoutResult(status -> {
			int timeout = remainingTimeout(deadline);
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				/** 결과 쓰기 후 만료됐으면 실제 commit 전에 롤백한다. */
				@Override
				public void beforeCommit(boolean readOnly) {
					if (!timeProvider.now().isBefore(deadline)) {
						// beforeCommit에서는 일반 런타임 예외로 실패시켜 실제 DB 롤백까지 수행한다.
						throw new QueryTimeoutException("알림 결과 기록의 선점 시간이 만료됐습니다.");
					}
				}
			});
			repository.recordDeliveryResults(results, timeout);
		});
	}

	/** 초 미만을 버려 임대 밖으로 늘리지 않으며 이미 지난 deadline은 DB 호출 전에 거절한다. */
	private int remainingTimeout(Instant deadline) {
		long seconds = Duration.between(timeProvider.now(), deadline).getSeconds();
		if (seconds < 1) {
			throw new TransactionTimedOutException("알림 결과를 기록할 선점 시간이 부족합니다.");
		}
		return (int) Math.min(seconds, NotificationJobRepository.MAX_DELIVERY_RECORD_SECONDS);
	}
}
