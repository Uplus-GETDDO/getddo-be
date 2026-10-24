package com.getddo.core.notification.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.notification.domain.NotificationDelivery;
import com.getddo.core.notification.domain.NotificationJob;
import com.getddo.core.notification.domain.NotificationJobRequest;
import com.getddo.core.notification.repository.NotificationJobRepository;

/** 묶음 저장과 선점을 독립적으로 확정해 실패한 묶음이 앞선 알림을 롤백하지 않도록 한다. */
@Service
@RequiredArgsConstructor
public class NotificationBatchTransactionService {
	private final NotificationJobRepository repository;

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
}
