package com.getddo.db.notification.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.notification.domain.MockDeliveryStatus;
import com.getddo.core.notification.domain.NotificationDelivery;
import com.getddo.core.notification.domain.NotificationDeliveryResult;
import com.getddo.core.notification.domain.NotificationJob;
import com.getddo.core.notification.domain.NotificationJobRequest;
import com.getddo.core.notification.domain.NotificationJobStatus;
import com.getddo.core.notification.domain.NotificationRetryPolicy;
import com.getddo.core.notification.domain.NotificationType;
import com.getddo.core.notification.exception.NotificationProcessingErrorCode;
import com.getddo.core.notification.exception.NotificationProcessingException;
import com.getddo.core.notification.repository.NotificationJobRepository;
import com.getddo.db.common.util.UuidBinary;

/**
 * 기존 알림 테이블의 선점·저장·재시도를 담당한다.
 *
 * <p>선점에는 MySQL의 행 잠금과 SKIP LOCKED를 사용한다. 오래된 처리자가 새 처리자의 결과를
 * 덮어쓰지 못하도록 선점 차수를 조건에 포함한다. JSON 문구의 이스케이프는 DB가 수행하고,
 * 입력 UUID 배열만 표준 문자열로 전달하므로 별도 직렬화 의존성이나 Mapper가 필요하지 않다.</p>
 */
@Repository
@RequiredArgsConstructor
public class NotificationJobRepositoryImpl implements NotificationJobRepository {
	private final JdbcTemplate jdbc;

	/** 업무 트랜잭션의 롤백과 함께 작업 등록도 취소한다. UNIQUE 충돌은 DB 내부에서 해결한다. */
	@Override
	@Transactional
	public UUID register(NotificationJobRequest request, Instant now) {
		String recipients = request.getRecipientIds().stream().map(id -> "\"" + id + "\"")
				.collect(Collectors.joining(",", "[", "]"));
		jdbc.update("""
			insert into notification_jobs
			(id, event_id, payload, occurrence_key,
			 notification_type, status, scheduled_at, created_at)
			values (?, ?, json_object('version', 1, 'publicationId', ?, 'title', ?, 'body', ?, 'linkUrl', ?,
			 'recipientIds', cast(? as json)), ?, ?, ?, ?, ?)
			on duplicate key update id = id
			""", bytes(newId()), bytes(request.getEventId()),
				request.getPublicationId() == null ? null : request.getPublicationId().toString(),
				request.getTitle(), request.getBody(), request.getLinkUrl(), recipients,
				request.getOccurrenceKey(), request.getType().name(), NotificationJobStatus.PENDING.name(),
				time(request.getScheduledAt()), time(now));
		// 중복 등록에서 확인한 최신 행을 읽어 호출자의 이전 스냅샷 영향을 피한다.
		UUID id = jdbc.queryForObject("select id from notification_jobs where occurrence_key = ? for share",
				(row, index) -> uuid(row.getBytes("id")), request.getOccurrenceKey());
		if (!findRequest(id, true).equals(request)) {
			throw new NotificationProcessingException(NotificationProcessingErrorCode.OCCURRENCE_CONFLICT);
		}
		return id;
	}

	/** 선점만 짧게 확정한다. 서버 종료 후에는 제한 시간과 차수로 다시 선점할 수 있다. */
	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<NotificationJob> claimNextJob(Instant now) {
		while (true) {
			List<NotificationJob> rows = jdbc.query("""
				select id, attempt_count, attempt_base from notification_jobs
				where json_extract(payload, '$.generationStoppedBy') is null
				and scheduled_at <= ? and (
				 (status = 'PENDING' and (next_attempt_at is null or next_attempt_at <= ?))
				 or (status = 'PROCESSING' and (lease_until is null or lease_until <= ?)))
				order by scheduled_at, id limit 1 for update skip locked
				""", (row, index) -> new NotificationJob(uuid(row.getBytes("id")), row.getInt("attempt_count"), row.getInt("attempt_base")),
					time(now), time(now), time(now));
			if (rows.isEmpty()) {
				return Optional.empty();
			}
			NotificationJob previous = rows.getFirst();
			if (previous.getAttemptCount() == Integer.MAX_VALUE || previous.getCycleAttemptCount() >= NotificationRetryPolicy.MAX_ATTEMPTS) {
				jdbc.update("""
					update notification_jobs set status = 'FAILED', lease_until = null,
					next_attempt_at = null, last_error = coalesce(last_error, ?) where id = ?
					""", NotificationProcessingErrorCode.TEMPORARY_FAILURE.getCode(), bytes(previous.getId()));
				continue;
			}
			NotificationJob job = new NotificationJob(previous.getId(), previous.getAttemptCount() + 1, previous.getAttemptBase());
			jdbc.update("""
				update notification_jobs set status = 'PROCESSING', attempt_count = ?,
				lease_until = ?, next_attempt_at = null where id = ?
				""", job.getAttemptCount(), time(now.plus(NotificationRetryPolicy.LEASE)), bytes(job.getId()));
			return Optional.of(job);
		}
	}

	/** 저장된 대상을 읽으므로 일부 생성 후 재시도에서도 대상이 바뀌지 않는다. */
	@Override
	public NotificationJobRequest findRequest(UUID jobId) {
		return findRequest(jobId, false);
	}

	@Override
	public NotificationJobRequest findRequestForComparison(UUID jobId) {
		return findRequest(jobId, true);
	}

	/** 등록 입력 비교는 잠금 읽기로 수행해 호출자의 이전 스냅샷에 영향을 받지 않는다. */
	private NotificationJobRequest findRequest(UUID jobId, boolean lockingRead) {
		String lockClause = lockingRead ? " for share" : "";
		List<UUID> recipients = jdbc.query("""
			select recipients.user_id from notification_jobs j
			join json_table(j.payload, '$.recipientIds[*]'
			 columns (user_id varchar(36) path '$' error on error)) recipients
			where j.id = ? order by recipients.user_id
			""" + (lockingRead ? " for share of j" : ""),
				(row, index) -> UUID.fromString(row.getString("user_id")), bytes(jobId));
		return jdbc.queryForObject("""
			select occurrence_key, notification_type, event_id, scheduled_at,
			if(json_type(json_extract(payload, '$.publicationId')) = 'NULL', null,
			 json_unquote(json_extract(payload, '$.publicationId'))) as publication_id,
			json_extract(payload, '$.version') as payload_version,
			json_type(json_extract(payload, '$.recipientIds')) as recipient_type,
			json_unquote(json_extract(payload, '$.title')) as title,
			json_unquote(json_extract(payload, '$.body')) as body,
			if(json_type(json_extract(payload, '$.linkUrl')) = 'NULL', null,
			 json_unquote(json_extract(payload, '$.linkUrl'))) as link_url
			from notification_jobs where id = ?
			""" + lockClause, (row, index) -> {
				if (row.getInt("payload_version") != 1 || !"ARRAY".equals(row.getString("recipient_type"))) {
					throw new NotificationProcessingException(NotificationProcessingErrorCode.INVALID_JOB);
				}
				String publicationId = row.getString("publication_id");
				return new NotificationJobRequest(row.getString("occurrence_key"),
						NotificationType.valueOf(row.getString("notification_type")), uuid(row.getBytes("event_id")),
						publicationId == null ? null : UUID.fromString(publicationId), row.getString("title"), row.getString("body"),
						row.getString("link_url"), instant(row, "scheduled_at"), recipients);
			}, bytes(jobId));
	}

	/**
	 * 연결 이벤트 → 작업 순서로 잠그고 알림 INSERT와 사용자 진행 위치 저장을 함께 확정한다.
	 * 이벤트 공유 잠금을 먼저 확보해 삭제·일정 변경의 이벤트 쓰기 잠금과 FK 검사 사이의 교착을 방지한다.
	 * 재처리는 기존 알림을 그대로 둔다.
	 */
	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean createNotification(NotificationJob job, NotificationJobRequest request, UUID userId, Instant now) {
		return createNotifications(job, request, List.of(userId), now);
	}

	/** Service가 연 독립 트랜잭션에서 잠금·차수 확인 후 묶음 INSERT와 진행 위치를 함께 저장한다. */
	@Override
	public boolean createNotifications(NotificationJob job, NotificationJobRequest request, List<UUID> userIds,
			Instant now) {
		if (userIds.isEmpty()) {
			return true;
		}
		if (request.getEventId() != null) {
			jdbc.query("select id from events where id = ? for share",
					(row, index) -> row.getBytes("id"), bytes(request.getEventId()));
		}
		List<Boolean> owned = jdbc.query("""
			select attempt_count, attempt_base, status, lease_until,
			json_extract(payload, '$.generationStoppedBy') as stopped_by
			from notification_jobs where id = ? for update
			""", (row, index) -> row.getInt("attempt_count") == job.getAttemptCount()
				&& row.getInt("attempt_base") == job.getAttemptBase()
				&& row.getString("stopped_by") == null
				&& NotificationJobStatus.PROCESSING.name().equals(row.getString("status"))
				&& instant(row, "lease_until") != null && instant(row, "lease_until").isAfter(now), bytes(job.getId()));
		if (owned.isEmpty() || !owned.getFirst()) {
			return false;
		}
		jdbc.batchUpdate("""
			insert into notifications
			(id, job_id, user_id, event_id, title, body, link_url, created_at, is_read,
			 mock_delivery_status, delivery_attempt_count)
			values (?, ?, ?, ?, ?, ?, ?, ?, false, 'PENDING', 0)
			on duplicate key update id = id
			""", userIds, userIds.size(), (statement, userId) -> {
				statement.setBytes(1, bytes(newId()));
				statement.setBytes(2, bytes(job.getId()));
				statement.setBytes(3, bytes(userId));
				statement.setBytes(4, bytes(request.getEventId()));
				statement.setString(5, request.getTitle());
				statement.setString(6, request.getBody());
				statement.setString(7, request.getLinkUrl());
				statement.setObject(8, time(now));
			});
		jdbc.update("update notification_jobs set last_processed_user_id = ?, lease_until = ? where id = ?",
				bytes(userIds.getLast()), time(now.plus(NotificationRetryPolicy.LEASE)), bytes(job.getId()));
		return true;
	}

	/** 재선점되지 않은 처리 차수의 완료를 별도 트랜잭션으로 기록하고 선점·오류 정보를 비운다. */
	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void completeJob(NotificationJob job, Instant now) {
		jdbc.update("""
			update notification_jobs set status = 'COMPLETED', completed_at = ?,
			lease_until = null, next_attempt_at = null, last_error = null
			where id = ? and status = 'PROCESSING' and attempt_count = ? and attempt_base = ?
			and json_extract(payload, '$.generationStoppedBy') is null
			""", time(now), bytes(job.getId()), job.getAttemptCount(), job.getAttemptBase());
	}

	/** 현재 처리 차수의 실패와 다음 시각을 별도 트랜잭션으로 기록한다. 다음 시각이 null이면 최종 실패다. */
	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void failJob(NotificationJob job, Instant nextAttemptAt, String errorCode) {
		jdbc.update("""
			update notification_jobs set status = ?, next_attempt_at = ?, lease_until = null,
			last_error = ? where id = ? and status = 'PROCESSING' and attempt_count = ? and attempt_base = ?
			and json_extract(payload, '$.generationStoppedBy') is null
			""", (nextAttemptAt == null ? NotificationJobStatus.FAILED : NotificationJobStatus.PENDING).name(),
				time(nextAttemptAt), errorCode, bytes(job.getId()), job.getAttemptCount(), job.getAttemptBase());
	}

	/** 다음 시도 시각을 발송 선점의 제한 시각으로도 사용한다. 별도 스키마 컬럼은 추가하지 않는다. */
	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<NotificationDelivery> claimNextDelivery(Instant now) {
		return claimNextDeliveries(now, 1).stream().findFirst();
	}

	/** 묶음의 행 잠금과 선점 차수 갱신을 호출 Service의 트랜잭션에서 함께 확정한다. */
	@Override
	public List<NotificationDelivery> claimNextDeliveries(Instant now, int limit) {
		if (limit < 1 || limit > NotificationJobRepository.MAX_DELIVERY_CLAIM) {
			throw new IllegalArgumentException("알림 발송 선점 건수는 1~"
					+ NotificationJobRepository.MAX_DELIVERY_CLAIM + "이어야 합니다.");
		}
		while (true) {
			List<NotificationDelivery> rows = jdbc.query("""
				select id, user_id, title, body, link_url, delivery_attempt_count, delivery_attempt_base from notifications
				where (mock_delivery_status = 'PENDING'
				 and (next_delivery_attempt_at is null or next_delivery_attempt_at <= ?))
				or (mock_delivery_status = 'FAILED' and next_delivery_attempt_at <= ?)
				order by created_at, id limit ? for update skip locked
				""", (row, index) -> new NotificationDelivery(uuid(row.getBytes("id")), uuid(row.getBytes("user_id")),
					row.getString("title"), row.getString("body"), row.getString("link_url"),
					row.getInt("delivery_attempt_count"), row.getInt("delivery_attempt_base")), time(now), time(now), limit);
			if (rows.isEmpty()) {
				return List.of();
			}
			List<NotificationDelivery> deliveries = new ArrayList<>();
			List<NotificationDelivery> exhausted = new ArrayList<>();
			for (NotificationDelivery previous : rows) {
				if (previous.getAttemptCount() == Integer.MAX_VALUE || previous.getCycleAttemptCount() >= NotificationRetryPolicy.MAX_ATTEMPTS) {
					exhausted.add(previous);
					continue;
				}
				deliveries.add(new NotificationDelivery(previous.getId(), previous.getUserId(),
						previous.getTitle(), previous.getBody(), previous.getLinkUrl(), previous.getAttemptCount() + 1, previous.getAttemptBase()));
			}
			if (!exhausted.isEmpty()) {
				jdbc.batchUpdate("""
					update notifications set mock_delivery_status = 'FAILED', next_delivery_attempt_at = null,
					last_delivery_error = coalesce(last_delivery_error, ?) where id = ?
					""", exhausted, exhausted.size(), (statement, delivery) -> {
						statement.setString(1, NotificationProcessingErrorCode.TEMPORARY_FAILURE.getCode());
						statement.setBytes(2, bytes(delivery.getId()));
					});
			}
			if (deliveries.isEmpty()) {
				continue;
			}
			jdbc.batchUpdate("""
				update notifications set mock_delivery_status = ?, delivery_attempt_count = ?,
				next_delivery_attempt_at = ? where id = ?
				""", deliveries, deliveries.size(), (statement, delivery) -> {
					statement.setString(1, MockDeliveryStatus.PENDING.name());
					statement.setInt(2, delivery.getAttemptCount());
					statement.setObject(3, time(now.plus(NotificationRetryPolicy.LEASE)));
					statement.setBytes(4, bytes(delivery.getId()));
				});
			return List.copyOf(deliveries);
		}
	}

	/** 각 행의 선점·복구 기준 차수를 검사하며 성공·실패 결과를 같은 JDBC 묶음으로 저장한다. */
	@Override
	public void recordDeliveryResults(List<NotificationDeliveryResult> results) {
		if (results.isEmpty()) {
			return;
		}
		if (results.size() > NotificationJobRepository.MAX_DELIVERY_CLAIM) {
			throw new IllegalArgumentException("발송 결과 묶음은 최대 "
					+ NotificationJobRepository.MAX_DELIVERY_CLAIM + "건이어야 합니다.");
		}
		jdbc.batchUpdate("""
			update notifications set mock_delivery_status = ?, mock_sent_at = coalesce(?, mock_sent_at),
			next_delivery_attempt_at = ?, last_delivery_error = ?
			where id = ? and mock_delivery_status = 'PENDING' and delivery_attempt_count = ?
			and delivery_attempt_base = ?
			""", results, results.size(), (statement, result) -> {
				statement.setString(1, result.getStatus().name());
				statement.setObject(2, time(result.getSentAt()));
				statement.setObject(3, time(result.getNextAttemptAt()));
				statement.setString(4, result.getErrorCode());
				statement.setBytes(5, bytes(result.getDelivery().getId()));
				statement.setInt(6, result.getDelivery().getAttemptCount());
				statement.setInt(7, result.getDelivery().getAttemptBase());
			});
	}

	/** 현재 발송 차수의 성공 시각을 별도 트랜잭션으로 저장하며 사용자의 읽음 상태는 변경하지 않는다. */
	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void completeDelivery(NotificationDelivery delivery, Instant now) {
		jdbc.update("""
			update notifications set mock_delivery_status = 'SENT', mock_sent_at = ?,
			next_delivery_attempt_at = null, last_delivery_error = null
			where id = ? and mock_delivery_status = 'PENDING' and delivery_attempt_count = ?
			and delivery_attempt_base = ?
			""", time(now), bytes(delivery.getId()), delivery.getAttemptCount(), delivery.getAttemptBase());
	}

	/** 현재 발송 차수의 실패와 재시도 시각을 저장한다. 이전 차수의 결과와 읽음 상태는 반영하지 않는다. */
	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void failDelivery(NotificationDelivery delivery, Instant nextAttemptAt, String errorCode) {
		jdbc.update("""
			update notifications set mock_delivery_status = 'FAILED', next_delivery_attempt_at = ?,
			last_delivery_error = ?
			where id = ? and mock_delivery_status = 'PENDING' and delivery_attempt_count = ?
			and delivery_attempt_base = ?
			""", time(nextAttemptAt), errorCode, bytes(delivery.getId()), delivery.getAttemptCount(), delivery.getAttemptBase());
	}

	/** 기존 BaseEntity와 같은 Hibernate UUID v7 생성기를 JDBC INSERT에도 재사용한다. */
	private static UUID newId() {
		return UuidVersion7Strategy.INSTANCE.generateUuid(null);
	}

	/** DB의 BINARY(16) 식별자와 비교할 수 있도록 UUID를 바이트 배열로 변환한다. */
	private static byte[] bytes(UUID id) {
		return id == null ? null : UuidBinary.toBytes(id);
	}

	/** DB의 BINARY(16) 값을 UUID로 복원하며 nullable 식별자는 null로 유지한다. */
	private static UUID uuid(byte[] value) {
		return value == null ? null : UuidBinary.fromBytes(value);
	}

	/** DATETIME에는 JVM 기본 시간대와 무관하게 UTC의 날짜·시각을 전달한다. */
	private static LocalDateTime time(Instant instant) {
		return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	/** DB의 날짜·시각을 UTC 순간으로 읽으며 nullable 시각은 null로 유지한다. */
	private static Instant instant(ResultSet row, String column) throws SQLException {
		LocalDateTime value = row.getObject(column, LocalDateTime.class);
		return value == null ? null : value.toInstant(ZoneOffset.UTC);
	}
}
