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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import com.getddo.core.notification.domain.NotificationDelivery;
import com.getddo.core.notification.domain.NotificationDeliveryResult;
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

	/** 중지·만료·차수 불일치가 있으면 묶음 저장과 진행 갱신을 모두 차단한다. */
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

	/** 저장 실패를 호출 트랜잭션에 전달하고 아직 확정되지 않은 위치로 진행하지 않는다. */
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

	/** 원본 내용을 보존한 UUID v7 알림들을 묶어 저장하고 마지막 수신자를 진행 위치로 삼는다. */
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

	/** 빈 묶음 처리에서 불필요한 DB 접근이나 진행 갱신을 하지 않는다. */
	@Test
	@DisplayName("빈 생성 묶음은 DB에 접근하지 않는다")
	void skipsEmptyGenerationBatch() {
		// when / then
		assertThat(repository.createNotifications(JOB, request, List.of(), NOW)).isTrue();
		verifyNoInteractions(jdbc);
	}

	/** 복구 구간의 남은 시도와 소진된 시도를 구분해 발송을 선점한다. */
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
		verify(jdbc).batchUpdate(contains("coalesce(last_delivery_error"), eq(List.of(exhausted, overflow)), eq(2),
				any(ParameterizedPreparedStatementSetter.class));
		verify(jdbc).batchUpdate(contains("delivery_attempt_count = ?"), eq(claimed), eq(1),
				any(ParameterizedPreparedStatementSetter.class));
	}

	/** 소진된 행이 뒤의 실행 가능한 발송을 막지 않도록 다음 후보를 확인한다. */
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
		verify(jdbc).batchUpdate(contains("coalesce(last_delivery_error"), any(List.class), eq(1),
				any(ParameterizedPreparedStatementSetter.class));
		verify(jdbc, never()).batchUpdate(contains("delivery_attempt_count = ?"), any(List.class), anyInt(),
				any(ParameterizedPreparedStatementSetter.class));
	}

	/** 발송 결과의 상태·시각·소유권 조건을 같은 묶음에 바인딩하고 읽음은 갱신하지 않는다. */
	@Test
	@DisplayName("성공·실패 결과는 UTC 시각과 선점·복구 차수를 보존하여 함께 저장한다")
	void bindsMixedDeliveryResultsWithoutChangingReadState() throws Exception {
		// given
		NotificationDelivery first = delivery(7, 6);
		NotificationDelivery second = delivery(4, 0);
		List<NotificationDeliveryResult> results = List.of(NotificationDeliveryResult.sent(first, NOW),
				NotificationDeliveryResult.failed(second, null, "NOTIFICATION-005"));
		PreparedStatement sent = mock(PreparedStatement.class);
		PreparedStatement failed = mock(PreparedStatement.class);
		doAnswer(invocation -> {
			String sql = invocation.getArgument(0);
			assertThat(sql).contains("mock_delivery_status = 'PENDING'", "delivery_attempt_count = ?", "delivery_attempt_base = ?")
					.doesNotContain("is_read", "created_at", "title", "body", "user_id");
			ParameterizedPreparedStatementSetter<NotificationDeliveryResult> setter = invocation.getArgument(3);
			setter.setValues(sent, results.getFirst());
			setter.setValues(failed, results.getLast());
			return new int[][]{{1, 0}};
		}).when(jdbc).batchUpdate(contains("mock_sent_at = coalesce"), eq(results), eq(2),
				any(ParameterizedPreparedStatementSetter.class));
		// when
		repository.recordDeliveryResults(results);
		// then
		verify(sent).setString(1, "SENT");
		verify(sent).setObject(2, utc(NOW));
		verify(sent).setObject(3, null);
		verify(sent).setString(4, null);
		verify(sent).setBytes(5, UuidBinary.toBytes(first.getId()));
		verify(sent).setInt(6, 7);
		verify(sent).setInt(7, 6);
		verify(failed).setString(1, "FAILED");
		verify(failed).setObject(2, null);
		verify(failed).setObject(3, null);
		verify(failed).setString(4, "NOTIFICATION-005");
		verify(failed).setBytes(5, UuidBinary.toBytes(second.getId()));
		verify(failed).setInt(6, 4);
		verify(failed).setInt(7, 0);
	}

	/** 과도한 발송 묶음은 DB 접근 전에 거절하며 최대 100건까지 허용한다. */
	@ParameterizedTest
	@ValueSource(ints = {-1, 0, 101})
	@DisplayName("발송 선점은 1~100건만 허용한다")
	void rejectsInvalidClaimLimit(int limit) {
		assertThatThrownBy(() -> repository.claimNextDeliveries(NOW, limit)).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(jdbc);
	}

	/** 비어 있는 결과는 쓰지 않고 상한을 넘은 결과는 저장 전에 거절한다. */
	@Test
	@DisplayName("빈 결과 묶음은 생략하고 101건 결과는 거절한다")
	void boundsResultBatchSize() {
		repository.recordDeliveryResults(List.of());
		NotificationDeliveryResult result = NotificationDeliveryResult.sent(delivery(1, 0), NOW);
		assertThatThrownBy(() -> repository.recordDeliveryResults(java.util.Collections.nCopies(101, result)))
				.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(jdbc);
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
