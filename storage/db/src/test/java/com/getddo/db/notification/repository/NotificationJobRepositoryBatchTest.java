package com.getddo.db.notification.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import com.getddo.core.notification.domain.NotificationDelivery;
import com.getddo.core.notification.domain.NotificationJob;
import com.getddo.core.notification.domain.NotificationJobRequest;
import com.getddo.core.notification.domain.NotificationType;
import com.getddo.db.common.util.UuidBinary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 실제 DB 실행과 구분해 잠금 결과·묶음 실패·발송 차수 경계를 단위 검증한다. */
@SuppressWarnings({"unchecked", "rawtypes"})
class NotificationJobRepositoryBatchTest {
	private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
	private static final UUID FIRST = new UUID(0, 1);
	private static final UUID SECOND = new UUID(0, 2);
	private static final NotificationJob JOB = new NotificationJob(UUID.randomUUID(), 1, 0);
	private JdbcTemplate jdbc;
	private NotificationJobRepositoryImpl repository;
	private NotificationJobRequest request;

	/** 개인 정보 없는 고정 수신자와 현재 시각을 준비한다. */
	@BeforeEach
	void setUp() {
		jdbc = mock(JdbcTemplate.class);
		repository = new NotificationJobRepositoryImpl(jdbc);
		request = new NotificationJobRequest("batch-unit", NotificationType.RESULT_CHANGED, null, null,
				"제목", "본문", null, NOW, List.of(FIRST, SECOND));
	}

	@ParameterizedTest
	@CsvSource(value = {
			"1,0,PROCESSING,NULL,60,true", "2,0,PROCESSING,NULL,60,false",
			"1,1,PROCESSING,NULL,60,false", "1,0,COMPLETED,NULL,60,false",
			"1,0,PROCESSING,stopped,60,false", "1,0,PROCESSING,NULL,0,false",
			"1,0,PROCESSING,NULL,-1,false"
	}, nullValues = "NULL")
	@DisplayName("선점 차수·기준 차수·중지·상태·만료가 맞는 묶음만 저장한다")
	void validatesOwnershipBeforeBatchInsert(int attempt, int base, String status, String stopped,
			int leaseSeconds, boolean expected) throws Exception {
		// given
		stubOwnership(attempt, base, status, stopped, leaseSeconds);

		// when
		assertThat(repository.createNotifications(JOB, request, List.of(FIRST, SECOND), NOW)).isEqualTo(expected);

		// then
		verify(jdbc, expected ? times(1) : never()).batchUpdate(contains("insert into notifications"),
				eq(List.of(FIRST, SECOND)), eq(2), any(ParameterizedPreparedStatementSetter.class));
		verify(jdbc, expected ? times(1) : never()).update(contains("last_processed_user_id"),
				any(byte[].class), any(LocalDateTime.class), any(byte[].class));
	}

	@Test
	@DisplayName("묶음 INSERT 실패 시 진행 위치를 갱신하지 않는다")
	void doesNotAdvanceProgressAfterFailedBatch() throws Exception {
		// given
		stubOwnership(1, 0, "PROCESSING", null, 60);
		doThrow(new DataIntegrityViolationException("synthetic failure")).when(jdbc).batchUpdate(
				anyString(), any(List.class), anyInt(), any(ParameterizedPreparedStatementSetter.class));

		// when / then
		assertThatThrownBy(() -> repository.createNotifications(JOB, request, List.of(FIRST, SECOND), NOW))
				.isInstanceOf(DataIntegrityViolationException.class);
		verify(jdbc, never()).update(contains("last_processed_user_id"), any(byte[].class),
				any(LocalDateTime.class), any(byte[].class));
	}

	@Test
	@DisplayName("묶음의 각 알림에 UUID v7·원본 내용·UTC 시각을 바인딩하고 마지막 사용자까지 진행한다")
	void bindsOriginalFieldsAndLastRecipient() throws Exception {
		// given
		stubOwnership(1, 0, "PROCESSING", null, 60);
		PreparedStatement statement = mock(PreparedStatement.class);
		doAnswer(invocation -> {
			ParameterizedPreparedStatementSetter<UUID> setter = invocation.getArgument(3);
			setter.setValues(statement, FIRST);
			setter.setValues(statement, SECOND);
			return new int[][]{{1, 1}};
		}).when(jdbc).batchUpdate(contains("insert into notifications"), eq(List.of(FIRST, SECOND)), eq(2),
				any(ParameterizedPreparedStatementSetter.class));

		// when
		assertThat(repository.createNotifications(JOB, request, List.of(FIRST, SECOND), NOW)).isTrue();

		// then
		ArgumentCaptor<byte[]> ids = ArgumentCaptor.forClass(byte[].class);
		verify(statement, times(2)).setBytes(eq(1), ids.capture());
		assertThat(ids.getAllValues()).allSatisfy(id -> assertThat(UuidBinary.fromBytes(id).version()).isEqualTo(7));
		verify(statement, times(2)).setBytes(2, UuidBinary.toBytes(JOB.getId()));
		verify(statement).setBytes(3, UuidBinary.toBytes(FIRST));
		verify(statement).setBytes(3, UuidBinary.toBytes(SECOND));
		verify(statement, times(2)).setBytes(4, null);
		verify(statement, times(2)).setString(5, request.getTitle());
		verify(statement, times(2)).setString(6, request.getBody());
		verify(statement, times(2)).setString(7, null);
		verify(statement, times(2)).setObject(8, utc(NOW));
		verify(jdbc).update(contains("last_processed_user_id"), eq(UuidBinary.toBytes(SECOND)),
				eq(utc(NOW.plusSeconds(60))), eq(UuidBinary.toBytes(JOB.getId())));
	}

	@Test
	@DisplayName("빈 생성 묶음은 DB에 접근하지 않는다")
	void skipsEmptyGenerationBatch() {
		// when / then
		assertThat(repository.createNotifications(JOB, request, List.of(), NOW)).isTrue();
		verifyNoInteractions(jdbc);
	}

	@Test
	@DisplayName("발송 묶음에서 소진된 알림은 제외하고 복구 기준 차수를 유지한 채 선점한다")
	void excludesExhaustedDeliveriesAndPreservesAttemptBase() {
		// given
		NotificationDelivery exhausted = delivery(4, 0);
		NotificationDelivery overflow = delivery(Integer.MAX_VALUE, 0);
		NotificationDelivery recovered = delivery(7, 6);
		doReturn(List.of(exhausted, overflow, recovered)).when(jdbc).query(contains("from notifications"),
				any(RowMapper.class), any(LocalDateTime.class), any(LocalDateTime.class), eq(20));

		// when
		List<NotificationDelivery> claimed = repository.claimNextDeliveries(NOW, 20);

		// then
		assertThat(claimed).hasSize(1);
		assertThat(claimed.getFirst().getId()).isEqualTo(recovered.getId());
		assertThat(claimed.getFirst().getAttemptCount()).isEqualTo(8);
		assertThat(claimed.getFirst().getAttemptBase()).isEqualTo(6);
		assertThat(claimed.getFirst().getCycleAttemptCount()).isEqualTo(2);
		verify(jdbc).update(contains("coalesce(last_delivery_error"), eq("NOTIFICATION-005"),
				eq(UuidBinary.toBytes(exhausted.getId())));
		verify(jdbc).update(contains("coalesce(last_delivery_error"), eq("NOTIFICATION-005"),
				eq(UuidBinary.toBytes(overflow.getId())));
		verify(jdbc).batchUpdate(contains("delivery_attempt_count = ?"), eq(claimed), eq(1),
				any(ParameterizedPreparedStatementSetter.class));
	}

	@Test
	@DisplayName("소진된 행만 있으면 다음 대상을 찾아보고 발송 대상을 만들지 않는다")
	void continuesPastExhaustedRows() {
		// given
		doReturn(List.of(delivery(4, 0)), List.of()).when(jdbc).query(contains("from notifications"),
				any(RowMapper.class), any(LocalDateTime.class), any(LocalDateTime.class), eq(20));

		// when / then
		assertThat(repository.claimNextDeliveries(NOW, 20)).isEmpty();
		verify(jdbc, times(2)).query(contains("from notifications"), any(RowMapper.class),
				any(LocalDateTime.class), any(LocalDateTime.class), eq(20));
		verify(jdbc, never()).batchUpdate(anyString(), any(List.class), anyInt(), any(ParameterizedPreparedStatementSetter.class));
	}

	/** 실제 결과 매퍼를 실행해 SQL 실행 여부와 함께 소유권 판정을 검증한다. */
	private void stubOwnership(int attempt, int base, String status, String stopped, int leaseSeconds) throws Exception {
		ResultSet row = mock(ResultSet.class, invocation -> switch ((String) invocation.getArgument(0)) {
			case "attempt_count" -> attempt;
			case "attempt_base" -> base;
			case "status" -> status;
			case "stopped_by" -> stopped;
			case "lease_until" -> utc(NOW.plusSeconds(leaseSeconds));
			default -> null;
		});
		doAnswer(invocation -> {
			RowMapper<Boolean> mapper = invocation.getArgument(1);
			return List.of(mapper.mapRow(row, 0));
		}).when(jdbc).query(contains("from notification_jobs where id"), any(RowMapper.class), any(byte[].class));
	}

	/** 차수와 복구 구간을 지정한 개인 알림을 만든다. */
	private static NotificationDelivery delivery(int attempt, int base) {
		return new NotificationDelivery(UUID.randomUUID(), FIRST, "제목", "본문", null, attempt, base);
	}

	/** JVM 기본 시간대에 영향을 받지 않는 DB 시각을 만든다. */
	private static LocalDateTime utc(Instant now) {
		return LocalDateTime.ofInstant(now, ZoneOffset.UTC);
	}
}
