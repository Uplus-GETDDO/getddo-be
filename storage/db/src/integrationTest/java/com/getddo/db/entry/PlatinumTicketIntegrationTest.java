package com.getddo.db.entry;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.entry.domain.EntryHistoryQuery;
import com.getddo.core.entry.domain.EntryReceipt;
import com.getddo.core.entry.domain.EntrySummary;
import com.getddo.core.entry.exception.EntryErrorCode;
import com.getddo.core.entry.service.EntryHistoryService;
import com.getddo.core.ticket.domain.TicketGrade;

import static com.getddo.db.ticket.TicketGrantSeeds.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 MySQL에서 플래티넘 응모권이 보유·응모·저장·이력 조회 전 경로에서 한 등급으로 다뤄지는지 검증한다. */
class PlatinumTicketIntegrationTest extends EntryIntegrationTestSupport {

	@Autowired
	private EntryHistoryService historyService;

	@Test
	@DisplayName("플래티넘 응모권을 보유하고 브론즈와 함께 응모하면 등급별로 차감·저장되고 이력에서 그대로 조회된다")
	void usesPlatinumTicketAndKeepsItInHistory() {
		// given
		giveTickets(userId, TicketGrade.PLATINUM, 2);
		giveTickets(userId, TicketGrade.BRONZE, 1);
		UUID event = weightedEvent(5);
		UUID entryId = UUID.randomUUID();
		// when
		EntryReceipt receipt = entryService.enter(command(userId, event, entryId,
				tickets(TicketGrade.PLATINUM, 1, TicketGrade.BRONZE, 1)));
		// then
		assertThat(receipt.getTicketsByGrade().get(TicketGrade.PLATINUM)).isEqualTo(1L);
		assertThat(receipt.getTicketsByGrade().get(TicketGrade.BRONZE)).isEqualTo(1L);
		assertThat(count("select requested_platinum_count from event_entries where id = ?", bytes(entryId)))
				.isEqualTo(1L);
		assertThat(count("select count(*) from tickets where user_id = ? and grade = 'PLATINUM' and status = 'SPENT'",
				bytes(userId))).isEqualTo(1L);
		EntrySummary summary = historyService.findMyEntries(userId, new EntryHistoryQuery(null, null, null, null),
				new PageQuery(1, 10)).getItems().get(0);
		assertThat(summary.getEntry().getRequestedTicketsByGrade().get(TicketGrade.PLATINUM)).isEqualTo(1L);
		assertThat(summary.getEntry().getDeductedTicketsByGrade().get(TicketGrade.PLATINUM)).isEqualTo(1L);
		assertThat(summary.getEntry().getRequestedTicketsByGrade().get(TicketGrade.GOLD)).isZero();
	}

	@Test
	@DisplayName("플래티넘 응모권의 보유 수는 등급별 맵에 담기고 사용한 만큼 줄어든다")
	void holdingsCountPlatinumByGrade() {
		// given
		giveTickets(userId, TicketGrade.PLATINUM, 2);
		UUID event = weightedEvent(5);
		// when
		long before = ticketQueryService.getMyTickets(userId).getCountByGrade().get(TicketGrade.PLATINUM);
		entryService.enter(command(userId, event, UUID.randomUUID(), tickets(TicketGrade.PLATINUM, 1)));
		long after = ticketQueryService.getMyTickets(userId).getCountByGrade().get(TicketGrade.PLATINUM);
		// then
		assertThat(before).isEqualTo(2L);
		assertThat(after).isEqualTo(1L);
	}

	@Test
	@DisplayName("플래티넘만 요청해 거절된 응모(상한 초과)도 요청 장수가 저장되고 이력에 플래티넘으로 남는다")
	void rejectedPlatinumRequestIsRecorded() {
		// given
		giveTickets(userId, TicketGrade.PLATINUM, 6);
		UUID event = weightedEvent(2);
		UUID entryId = UUID.randomUUID();
		// when
		assertThatThrownBy(() -> entryService.enter(command(userId, event, entryId, tickets(TicketGrade.PLATINUM, 3))))
				.satisfies(e -> assertEntryError(e, EntryErrorCode.TICKET_LIMIT_EXCEEDED));
		// then
		List<EntrySummary> items = historyService.findMyEntries(userId,
				new EntryHistoryQuery(null, null, null, null), new PageQuery(1, 10)).getItems();
		assertThat(items).hasSize(1);
		assertThat(items.get(0).getEntry().getRequestedTicketsByGrade().get(TicketGrade.PLATINUM)).isEqualTo(3L);
		assertThat(items.get(0).getEntry().getDeductedTicketCount()).isZero();
		assertThat(items.get(0).getEntry().getRejectionCode()).isEqualTo("ENTRY-007");
	}
}
