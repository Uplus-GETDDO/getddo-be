package com.getddo.db.ticket.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.getddo.core.ticket.domain.GrantSource;
import com.getddo.core.ticket.domain.GrantSourceType;
import com.getddo.core.ticket.domain.Ticket;
import com.getddo.core.ticket.domain.TicketGrade;
import com.getddo.core.ticket.domain.TicketHistory;
import com.getddo.core.ticket.domain.TicketOperationType;
import com.getddo.core.ticket.domain.TicketStatus;
import com.getddo.core.ticket.exception.TicketErrorCode;
import com.getddo.core.ticket.exception.TicketException;
import com.getddo.core.ticket.repository.TicketBulkRefundRepository;
import com.getddo.db.common.util.UuidBinary;

/** JPA 영속성 컨텍스트를 키우지 않고 묶음 SQL로 취소 반환을 저장한다. */
@Repository
@RequiredArgsConstructor
public class TicketBulkRefundRepositoryImpl implements TicketBulkRefundRepository {

	private final NamedParameterJdbcTemplate jdbc;

	@Override
	public List<TicketHistory> findUses(Collection<UUID> entryIds) {
		return jdbc.query("""
				select * from ticket_histories where event_entry_id in (:ids) and operation_type = 'USE'
				order by ticket_id, id
				""", Map.of("ids", bytes(entryIds)), (row, index) -> new TicketHistory(
				UuidBinary.fromBytes(row.getBytes("id")), UuidBinary.fromBytes(row.getBytes("ticket_id")),
				TicketOperationType.USE, row.getLong("ticket_version"), TicketStatus.valueOf(row.getString("status")),
				instant(row, "expires_at"), UuidBinary.fromBytes(row.getBytes("event_entry_id")), null, null,
				row.getString("reason"), instant(row, "created_at")));
	}

	@Override
	public List<Ticket> findTicketsForUpdate(Collection<UUID> ticketIds) {
		return jdbc.query("select * from tickets where id in (:ids) order by id for update",
				Map.of("ids", bytes(ticketIds)), (row, index) -> new Ticket(
						UuidBinary.fromBytes(row.getBytes("id")), UuidBinary.fromBytes(row.getBytes("user_id")),
						source(row), TicketGrade.valueOf(row.getString("grade")), TicketStatus.valueOf(row.getString("status")),
						instant(row, "expires_at"), row.getLong("version"), instant(row, "created_at"), instant(row, "updated_at")));
	}

	@Override
	public Set<UUID> findRefundedUseIds(Collection<UUID> useIds) {
		return new HashSet<>(jdbc.query("select original_use_history_id from ticket_histories where original_use_history_id in (:ids)",
				Map.of("ids", bytes(useIds)), (row, index) -> UuidBinary.fromBytes(row.getBytes("original_use_history_id"))));
	}

	@Override
	public void saveRefunds(List<Ticket> returned, List<TicketHistory> histories) {
		if (returned.isEmpty()) {
			return;
		}
		List<Object> updateValues = new ArrayList<>();
		List<String> sources = new ArrayList<>();
		for (Ticket ticket : returned) {
			sources.add("select ? as id, ? as expected_version");
			updateValues.add(UuidBinary.toBytes(ticket.getId()));
			updateValues.add(ticket.getVersion() - 1);
		}
		Ticket first = returned.getFirst();
		updateValues.add(utc(first.getExpiresAt()));
		updateValues.add(utc(first.getUpdatedAt()));
		// 묶음 UPDATE와 multi-row INSERT를 사용해 JDBC 드라이버의 batch rewrite 설정에 의존하지 않는다.
		int updated = jdbc.getJdbcTemplate().update("update tickets t join (" + String.join(" union all ", sources)
				+ ") r on t.id = r.id and t.version = r.expected_version"
				+ " set t.status = 'RETURNED', t.version = t.version + 1, t.expires_at = ?, t.updated_at = ?"
				+ " where t.status = 'SPENT'", updateValues.toArray());
		if (updated != returned.size()) {
			throw new TicketException(TicketErrorCode.TICKET_REFUND_STATE_MISMATCH);
		}
		List<Object> insertValues = new ArrayList<>();
		TicketHistory firstHistory = histories.getFirst();
		insertValues.add(utc(firstHistory.getExpiresAt()));
		insertValues.add(firstHistory.getReason());
		insertValues.add(utc(firstHistory.getCreatedAt()));
		List<String> rows = new ArrayList<>();
		for (TicketHistory history : histories) {
			rows.add("select ? as id, ? as ticket_id, ? as use_id, ? as ticket_version");
			insertValues.add(UuidBinary.toBytes(UuidVersion7Strategy.INSTANCE.generateUuid(null)));
			insertValues.add(UuidBinary.toBytes(history.getTicketId()));
			insertValues.add(UuidBinary.toBytes(history.getOriginalUseHistoryId()));
			insertValues.add(history.getTicketVersion());
		}
		// 공통 사유는 한 번만 바인딩해 긴 TEXT 사유가 묶음 크기만큼 전송되지 않게 한다.
		jdbc.getJdbcTemplate().update("insert into ticket_histories"
				+ " (id,ticket_id,original_use_history_id,operation_type,ticket_version,status,expires_at,reason,created_at)"
				+ " select r.id,r.ticket_id,r.use_id,'REFUND',r.ticket_version,'RETURNED',?,?,? from ("
				+ String.join(" union all ", rows) + ") r", insertValues.toArray());
	}

	private static List<byte[]> bytes(Collection<UUID> ids) {
		return ids.stream().distinct().map(UuidBinary::toBytes).toList();
	}

	private static GrantSource source(ResultSet row) throws SQLException {
		for (GrantSourceType type : GrantSourceType.values()) {
			byte[] id = row.getBytes(switch (type) {
				case ATTENDANCE -> "attendance_reward_claim_id";
				case MISSION -> "mission_reward_claim_id";
				case GAME -> "game_reward_claim_id";
			});
			if (id != null) {
				return new GrantSource(type, UuidBinary.fromBytes(id));
			}
		}
		throw new IllegalStateException("응모권에 지급 근거 청구가 없다.");
	}

	private static Instant instant(ResultSet row, String name) throws SQLException {
		return row.getObject(name, LocalDateTime.class).toInstant(ZoneOffset.UTC);
	}

	private static LocalDateTime utc(Instant value) {
		return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
	}
}
