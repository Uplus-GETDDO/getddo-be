package com.getddo.core.notification.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.getddo.core.notification.domain.NotificationDelivery;
import com.getddo.core.notification.domain.NotificationDeliveryResult;
import com.getddo.core.notification.domain.NotificationJob;
import com.getddo.core.notification.domain.NotificationJobRequest;

/** 알림 생성·모의 발송을 원본 업무와 분리해 저장하고 선점 차수로 동시 처리를 보호한다. */
public interface NotificationJobRepository {
	/** 한 번에 함께 선점할 수 있는 발송 대상의 최대 건수다. */
	int MAX_DELIVERY_CLAIM = 100;
	/** 결과 한 묶음의 DB 작업에 허용할 최대 초다. 남은 선점 시간이 더 짧으면 그 시간을 따른다. */
	int MAX_DELIVERY_RECORD_SECONDS = 5;

	/** 호출자의 업무 트랜잭션에 참여하며 동일 발생 키·동일 입력에는 기존 작업 ID를 반환한다. */
	UUID register(NotificationJobRequest request, Instant now);
	/** 별도 트랜잭션에서 실행 시각이 된 작업 하나를 선점한다. 만료된 선점도 복구 대상이다. */
	Optional<NotificationJob> claimNextJob(Instant now);
	/** 저장된 발생 사실과 수신자 목록을 읽는다. 재시도 중 다시 계산하지 않는다. */
	NotificationJobRequest findRequest(UUID jobId);
	/** 같은 발생 건의 등록 입력은 최신 잠금 읽기로 비교한다. 호출 Service 트랜잭션에 참여한다. */
	NotificationJobRequest findRequestForComparison(UUID jobId);
	/** 사용자별 별도 트랜잭션에서 중복 생성 없이 저장한다. 선점이 유효하지 않으면 false다. */
	boolean createNotification(NotificationJob job, NotificationJobRequest request, UUID userId, Instant now);
	/** Service의 독립 트랜잭션에서 한 묶음을 저장한다. 소유권이 없으면 생성하지 않고 false를 반환한다. */
	boolean createNotifications(NotificationJob job, NotificationJobRequest request, List<UUID> userIds, Instant now);
	/** 재선점되지 않은 자신의 선점 차수라면 제한 시간 이후에도 생성 완료를 확정한다. */
	void completeJob(NotificationJob job, Instant now);
	/** 자신의 선점 차수일 때만 실패·다음 시각을 기록한다. null은 최종 실패다. */
	void failJob(NotificationJob job, Instant nextAttemptAt, String errorCode);
	/** 생성된 알림 하나의 모의 발송을 별도 트랜잭션에서 선점한다. */
	Optional<NotificationDelivery> claimNextDelivery(Instant now);
	/** Service의 독립 트랜잭션에서 최대 limit건을 함께 선점하며 다른 처리자의 잠긴 행은 건너뛴다. */
	List<NotificationDelivery> claimNextDeliveries(Instant now, int limit);
	/** Service의 독립 트랜잭션에서 최대 100건의 결과를 저장하며 이전 선점·복구 구간의 결과는 무시한다. */
	void recordDeliveryResults(List<NotificationDeliveryResult> results, int timeoutSeconds);
	/** 재선점되지 않은 자신의 발송 차수라면 제한 시간 이후에도 성공을 기록하며 읽음 상태는 그대로 둔다. */
	void completeDelivery(NotificationDelivery delivery, Instant now);
	/** 생성 실패와 별개인 발송 실패·다음 시각을 기록한다. 읽음 상태는 그대로 둔다. */
	void failDelivery(NotificationDelivery delivery, Instant nextAttemptAt, String errorCode);
}
