package com.getddo.db.notification.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.notification.domain.AdminNotification;
import com.getddo.core.notification.domain.AdminNotificationJob;
import com.getddo.core.notification.domain.MockDeliveryStatus;
import com.getddo.core.notification.domain.NotificationDeliveryQuery;
import com.getddo.core.notification.domain.NotificationJobQuery;
import com.getddo.core.notification.domain.NotificationJobStatus;
import com.getddo.core.notification.domain.NotificationType;
import com.getddo.core.notification.repository.NotificationAdminRepository;
import com.getddo.db.common.util.UuidBinary;

/** Service 트랜잭션에서 관리자 조회와 행 잠금 기반 복구 접수를 수행한다. */
@Repository
@RequiredArgsConstructor
public class NotificationAdminRepositoryImpl implements NotificationAdminRepository {
	private static final String JOB_COLUMNS = """
		select j.id, j.event_id, j.notification_type, j.status, j.attempt_count, j.attempt_base,
		j.scheduled_at, j.next_attempt_at, j.created_at, j.completed_at, j.last_error,
		j.source_job_id, j.last_processed_user_id, j.lease_until, e.status as event_status, e.starts_at as event_starts_at,
		e.deleted_at as event_deleted_at,
		case when json_type(json_extract(j.payload, '$.publicationId')) = 'STRING'
		then json_unquote(json_extract(j.payload, '$.publicationId')) end as publication_id,
		json_unquote(json_extract(j.payload, '$.generationStoppedBy')) as stopped_by
		from notification_jobs j left join events e on e.id = j.event_id
		""";
	private static final String DELIVERY_COLUMNS = """
		select n.*, u.name as user_name
		from notifications n join users u on u.id = n.user_id
		""";
	private final JdbcTemplate jdbc;

	/** 선택 필터와 생성 시각 역순·ID 역순으로 작업 페이지를 조회한다. */
	@Override
	public PageResult<AdminNotificationJob> findJobs(NotificationJobQuery query) {
		StringBuilder where = new StringBuilder(" where 1=1");
		List<Object> args = new ArrayList<>();
		filter(where, args, "j.status = ?", query.getStatus() == null ? null : query.getStatus().name());
		filter(where, args, "j.notification_type = ?", query.getNotificationType() == null ? null : query.getNotificationType().name());
		filter(where, args, "j.event_id = ?", bytes(query.getEventId()));
		filter(where, args, "j.created_at >= ?", filterTime(query.getFrom()));
		filter(where, args, "j.created_at < ?", filterTime(query.getTo()));
		long count = jdbc.queryForObject("select count(*) from notification_jobs j" + where, Long.class, args.toArray());
		PageQuery page = query.getPage();
		args.add(page.getSize());
		args.add(page.offset());
		// 큰 payload를 정렬 대상으로 읽지 않도록 ID 페이지를 먼저 확정한다.
		List<UUID> ids = jdbc.query("select j.id from notification_jobs j" + where
				+ " order by j.created_at desc, j.id desc limit ? offset ?",
				(row, index) -> UuidBinary.fromBytes(row.getBytes("id")), args.toArray());
		if (ids.isEmpty()) {
			return new PageResult<>(List.of(), page.getPage(), page.getSize(), count);
		}
		String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
		Object[] detailArgs = ids.stream().map(UuidBinary::toBytes).toArray();
		List<AdminNotificationJob> items = jdbc.query(JOB_COLUMNS + " where j.id in (" + placeholders + ")",
				(row, index) -> job(row), detailArgs);
		// 상세 조회에서는 DB 정렬을 하지 않고 첫 조회의 순서를 복원한다.
		Map<UUID, Integer> positions = new HashMap<>();
		for (int index = 0; index < ids.size(); index++) {
			positions.put(ids.get(index), index);
		}
		items.sort(Comparator.comparingInt(item -> positions.get(item.getId())));
		return new PageResult<>(items, page.getPage(), page.getSize(), count);
	}

	/** 이벤트 상태를 포함한 작업 조회 결과를 반환한다. */
	@Override
	public Optional<AdminNotificationJob> findJob(UUID id) {
		return jdbc.query(JOB_COLUMNS + " where j.id = ?", (row, index) -> job(row), bytes(id)).stream().findFirst();
	}

	/** 호출 Service의 트랜잭션에서 이벤트 다음 작업 순서로 행을 잠근다. */
	@Override
	public Optional<AdminNotificationJob> lockJob(UUID id) {
		// event_id 자체는 등록 이후 변경하지 않는다. 잠금 읽기로 실제 작업 상태를 다시 확인한다.
		List<byte[]> events = jdbc.query("select event_id from notification_jobs where id = ?",
				(row, index) -> row.getBytes("event_id"), bytes(id));
		if (events.isEmpty()) {
			return Optional.empty();
		}
		if (events.getFirst() != null) {
			jdbc.query("select id from events where id = ? for update", (row, index) -> row.getBytes("id"), events.getFirst());
		}
		return jdbc.query(JOB_COLUMNS + " where j.id = ? for update of j",
				(row, index) -> job(row), bytes(id)).stream().findFirst();
	}

	/** 선택 필터와 생성 시각 역순·ID 역순으로 발송 이력 페이지를 조회한다. */
	@Override
	public PageResult<AdminNotification> findDeliveries(NotificationDeliveryQuery query) {
		StringBuilder where = new StringBuilder(" where 1=1");
		List<Object> args = new ArrayList<>();
		filter(where, args, "n.user_id = ?", bytes(query.getUserId()));
		filter(where, args, "n.event_id = ?", bytes(query.getEventId()));
		filter(where, args, "n.job_id = ?", bytes(query.getJobId()));
		filter(where, args, "n.mock_delivery_status = ?", query.getMockDeliveryStatus() == null ? null : query.getMockDeliveryStatus().name());
		filter(where, args, "n.created_at >= ?", filterTime(query.getFrom()));
		filter(where, args, "n.created_at < ?", filterTime(query.getTo()));
		long count = jdbc.queryForObject("select count(*) from notifications n" + where, Long.class, args.toArray());
		PageQuery page = query.getPage();
		args.add(page.getSize());
		args.add(page.offset());
		List<AdminNotification> items = jdbc.query(DELIVERY_COLUMNS + where + " order by n.created_at desc, n.id desc limit ? offset ?",
				(row, index) -> delivery(row), args.toArray());
		return new PageResult<>(items, page.getPage(), page.getSize(), count);
	}

	/** 복구 조건을 다시 확인할 수 있도록 해당 알림 행을 잠근다. */
	@Override
	public Optional<AdminNotification> lockDelivery(UUID id) {
		return jdbc.query(DELIVERY_COLUMNS + " where n.id = ? for update of n",
				(row, index) -> delivery(row), bytes(id)).stream().findFirst();
	}

	/** 관리자 발송 응답에 필요한 알림 한 건을 읽는다. */
	@Override
	public Optional<AdminNotification> findDelivery(UUID id) {
		// 복구 직후에도 호출자 이전 스냅샷 대신 현재 행의 읽음 상태를 반환한다.
		return jdbc.query(DELIVERY_COLUMNS + " where n.id = ? for share of n",
				(row, index) -> delivery(row), bytes(id)).stream().findFirst();
	}

	/** 최종 실패 작업을 대기 상태로 바꾸고 누적 차수를 새 구간의 기준으로 저장한다. */
	@Override
	public boolean retryJob(UUID id, Instant now) {
		return jdbc.update("""
			update notification_jobs set status = 'PENDING', attempt_base = attempt_count,
			next_attempt_at = ?, lease_until = null, completed_at = null, last_error = null
			where id = ? and status = 'FAILED' and next_attempt_at is null and lease_until is null
			and attempt_count < 2147483647 and json_extract(payload, '$.generationStoppedBy') is null
			""", time(now), bytes(id)) == 1;
	}

	/** 알림 내용·읽음 상태를 유지하고 모의 발송의 새 복구 구간을 접수한다. */
	@Override
	public boolean retryDelivery(UUID id, Instant now) {
		return jdbc.update("""
			update notifications set mock_delivery_status = 'PENDING', delivery_attempt_base = delivery_attempt_count,
			next_delivery_attempt_at = ?, last_delivery_error = null
			where id = ? and mock_delivery_status = 'FAILED' and next_delivery_attempt_at is null
			and delivery_attempt_count < 2147483647
			""", time(now), bytes(id)) == 1;
	}

	/** 선택 값이 있을 때에만 SQL 조건과 바인딩 값을 함께 추가한다. */
	private static void filter(StringBuilder where, List<Object> args, String condition, Object value) {
		if (value != null) {
			where.append(" and ").append(condition);
			args.add(value);
		}
	}

	/** DB 행을 복구 가능 여부 판단에 필요한 작업 도메인으로 변환한다. */
	private static AdminNotificationJob job(ResultSet row) throws SQLException {
		String publication = row.getString("publication_id");
		String status = row.getString("event_status");
		return new AdminNotificationJob(uuid(row.getBytes("id")), uuid(row.getBytes("event_id")),
				publicationId(publication), NotificationType.valueOf(row.getString("notification_type")),
				NotificationJobStatus.valueOf(row.getString("status")), row.getInt("attempt_count"), row.getInt("attempt_base"),
				instant(row, "scheduled_at"), instant(row, "next_attempt_at"), instant(row, "created_at"), instant(row, "completed_at"),
				row.getString("stopped_by"), row.getString("last_error"), uuid(row.getBytes("source_job_id")),
				uuid(row.getBytes("last_processed_user_id")), instant(row, "lease_until"),
				status == null ? null : EventStatus.valueOf(status), instant(row, "event_starts_at"), row.getObject("event_deleted_at") != null);
	}

	/** 손상된 선택 메타데이터 때문에 최종 실패 작업의 관리자 조회까지 막지 않는다. */
	private static UUID publicationId(String value) {
		if (value == null) return null;
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException invalidMetadata) {
			return null;
		}
	}

	/** DB 행을 읽음·발송 정보와 복구 기준 차수를 가진 도메인으로 변환한다. */
	private static AdminNotification delivery(ResultSet row) throws SQLException {
		return new AdminNotification(uuid(row.getBytes("id")), uuid(row.getBytes("job_id")), uuid(row.getBytes("user_id")), row.getString("user_name"),
				uuid(row.getBytes("event_id")), row.getString("title"), row.getString("body"), row.getString("link_url"),
				instant(row, "created_at"), row.getBoolean("is_read"), MockDeliveryStatus.valueOf(row.getString("mock_delivery_status")),
				instant(row, "mock_sent_at"), row.getInt("delivery_attempt_count"), row.getInt("delivery_attempt_base"),
				instant(row, "next_delivery_attempt_at"), row.getString("last_delivery_error"));
	}

	/** 선택 시각 컬럼을 UTC Instant로 변환하며 null을 유지한다. */
	private static Instant instant(ResultSet row, String column) throws SQLException {
		LocalDateTime value = row.getObject(column, LocalDateTime.class);
		return value == null ? null : value.toInstant(ZoneOffset.UTC);
	}

	/** DB의 마이크로초 격자에서 [from,to)를 유지하도록 두 경계를 올림한다. */
	private static LocalDateTime filterTime(Instant value) {
		if (value == null) return null;
		Instant micros = value.truncatedTo(ChronoUnit.MICROS);
		return time(micros.equals(value) ? micros : micros.plus(1, ChronoUnit.MICROS));
	}

	/** Instant를 UTC DB 날짜·시각으로 변환하며 null을 유지한다. */
	private static LocalDateTime time(Instant value) {
		return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
	}

	/** 공통 UUID 변환기로 선택 UUID를 BINARY(16) 바인딩 값으로 바꾼다. */
	private static byte[] bytes(UUID value) {
		return value == null ? null : UuidBinary.toBytes(value);
	}

	/** 선택 BINARY(16) 값을 UUID로 변환하며 null을 유지한다. */
	private static UUID uuid(byte[] value) {
		return value == null ? null : UuidBinary.fromBytes(value);
	}
}
