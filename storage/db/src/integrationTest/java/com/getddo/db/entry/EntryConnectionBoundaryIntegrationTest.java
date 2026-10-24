package com.getddo.db.entry;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.getddo.core.entry.domain.EntryCommand;
import com.getddo.core.entry.domain.EntryReceipt;
import com.getddo.core.entry.exception.EntryErrorCode;
import com.getddo.core.entry.repository.EntryParticipantRepository;
import com.getddo.core.ticket.domain.TicketGrade;

import static com.getddo.db.ticket.TicketGrantSeeds.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 실제 MySQL 트랜잭션의 커밋 직전 실패와 커밋 직후 응답 유실을 구분한다.
 * 물리 연결을 끊지 않고 동기화 콜백에서 연결 예외를 주입하므로 실제 RDS 장애·드라이버 복구시간 검증은 아니다.
 */
class EntryConnectionBoundaryIntegrationTest extends EntryIntegrationTestSupport {

	@MockitoSpyBean
	private EntryParticipantRepository participants;

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("커밋 전 연결 실패는 차감을 롤백하고 커밋 후 응답 유실은 같은 키로 추가 차감 없이 재조회한다")
	void connectionFailureDoesNotTriggerAutomaticWriteRetry(boolean committed) {
		// given
		giveTickets(userId, TicketGrade.GOLD, 2);
		UUID event = weightedEvent(5);
		UUID key = UUID.randomUUID();
		EntryCommand request = command(userId, event, key, Map.of(TicketGrade.GOLD, 1L));
		doAnswer(invocation -> {
			invocation.callRealMethod();
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void beforeCommit(boolean readOnly) {
					if (!committed) {
						throw connectionFailure();
					}
				}

				@Override
				public void afterCommit() {
					if (committed) {
						throw connectionFailure();
					}
				}
			});
			return null;
		}).when(participants).addUsedTicketCount(any(UUID.class), anyLong());
		// when / then
		assertThatThrownBy(() -> entryService.enter(request)).isInstanceOf(DataAccessResourceFailureException.class);
		verify(participants, times(1)).addUsedTicketCount(any(UUID.class), anyLong());
		assertThat(count("select count(*) from event_entries where id = ?", bytes(key))).isEqualTo(committed ? 1 : 0);
		assertThat(spentTickets(userId)).isEqualTo(committed ? 1 : 0);
		assertThat(count("select count(*) from ticket_histories where event_entry_id = ?", bytes(key))).isEqualTo(committed ? 1 : 0);

		// given: 연결이 복구된 뒤 클라이언트가 같은 요청 키·본문으로 다시 보낸다.
		reset(participants);
		// when
		EntryReceipt replay = entryService.enter(request);
		EntryReceipt again = entryService.enter(request);
		// then
		assertThat(replay.isCreated()).isEqualTo(!committed);
		assertThat(again.isCreated()).isFalse();
		assertThat(replay.getEntryId()).isEqualTo(key);
		assertThat(again.getAcceptedAt()).isEqualTo(replay.getAcceptedAt());
		assertThat(spentTickets(userId)).isEqualTo(1);
		assertThat(usedTicketCount(userId, event)).isEqualTo(1);
		assertThat(count("select count(*) from ticket_histories where event_entry_id = ?", bytes(key))).isEqualTo(1);
		assertThatThrownBy(() -> entryService.enter(command(userId, event, key, Map.of(TicketGrade.GOLD, 2L))))
				.satisfies(failure -> assertEntryError(failure, EntryErrorCode.IDEMPOTENCY_CONFLICT));
	}

	private static DataAccessResourceFailureException connectionFailure() {
		return new DataAccessResourceFailureException("test connection boundary", new SQLException("test", "08007"));
	}
}
