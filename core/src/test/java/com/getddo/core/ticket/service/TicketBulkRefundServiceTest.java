package com.getddo.core.ticket.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.getddo.core.common.time.TimeProvider;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TicketBulkRefundServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-27T03:00:00Z");
	private TicketBulkRefundRepository repository;
	private TicketBulkRefundService service;

	@BeforeEach
	void setUp() {
		repository = mock(TicketBulkRefundRepository.class);
		service = new TicketBulkRefundService(repository, new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));
	}

	@Test
	void refundsAcrossBatchesPreservingGradeSourceAndOriginalUse() {
		List<TicketHistory> uses = IntStream.range(0, 501).mapToObj(ignored -> use(UUID.randomUUID())).toList();
		Map<UUID, TicketHistory> byEntry = uses.stream().collect(Collectors.toMap(TicketHistory::getEventEntryId, item -> item));
		Map<UUID, Ticket> tickets = uses.stream().collect(Collectors.toMap(TicketHistory::getTicketId, this::spent));
		when(repository.findUses(any())).thenAnswer(call -> {
			List<UUID> ids = call.getArgument(0);
			return ids.stream().map(byEntry::get).toList();
		});
		when(repository.findTicketsForUpdate(any())).thenAnswer(call -> {
			List<UUID> ids = call.getArgument(0);
			return ids.stream().map(tickets::get).toList();
		});
		when(repository.findRefundedUseIds(any())).thenReturn(Set.of());
		List<UUID> entries = new ArrayList<>(byEntry.keySet());
		entries.add(entries.getFirst());
		assertThat(service.refundAll(entries, "취소")).isEqualTo(501);
		ArgumentCaptor<List<Ticket>> returned = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<List<TicketHistory>> histories = ArgumentCaptor.forClass(List.class);
		verify(repository, times(2)).saveRefunds(returned.capture(), histories.capture());
		assertThat(returned.getAllValues()).extracting(List::size).containsExactly(500, 1);
		assertThat(returned.getAllValues().stream().flatMap(List::stream)).allSatisfy(ticket -> {
			assertThat(ticket.getStatus()).isEqualTo(TicketStatus.RETURNED);
			assertThat(ticket.getVersion()).isEqualTo(3);
			assertThat(ticket.getGrade()).isEqualTo(TicketGrade.GOLD);
			assertThat(ticket.getExpiresAt()).isEqualTo(Instant.parse("2026-11-30T15:00:00Z"));
		});
		assertThat(histories.getAllValues().stream().flatMap(List::stream).map(TicketHistory::getOriginalUseHistoryId))
				.containsExactlyInAnyOrderElementsOf(uses.stream().map(TicketHistory::getId).toList());
	}

	@Test
	void skipsAlreadyRefundedUseEvenWhenTicketWasUsedAgain() {
		TicketHistory use = use(UUID.randomUUID());
		when(repository.findUses(any())).thenReturn(List.of(use));
		when(repository.findTicketsForUpdate(any())).thenReturn(List.of());
		when(repository.findRefundedUseIds(any())).thenReturn(Set.of(use.getId()));
		assertThat(service.refundAll(List.of(use.getEventEntryId()), "취소")).isEqualTo(1);
		verify(repository, never()).saveRefunds(anyList(), anyList());
	}

	@Test
	void rejectsMissingUseAndChangedTicketWithoutSaving() {
		TicketHistory use = use(UUID.randomUUID());
		when(repository.findUses(any())).thenReturn(List.of());
		assertError(TicketErrorCode.TICKET_USE_NOT_FOUND, List.of(use.getEventEntryId()));
		when(repository.findUses(any())).thenReturn(List.of(use));
		when(repository.findTicketsForUpdate(any())).thenReturn(List.of());
		when(repository.findRefundedUseIds(any())).thenReturn(Set.of());
		assertError(TicketErrorCode.TICKET_REFUND_STATE_MISMATCH, List.of(use.getEventEntryId()));
		verify(repository, never()).saveRefunds(anyList(), anyList());
	}

	@Test
	void emptyEventDoesNotQueryTicketsAndInvalidInputIsRejected() {
		assertThat(service.refundAll(List.of(), "취소")).isZero();
		assertError(TicketErrorCode.TICKET_INVALID_USE, null);
		assertError(TicketErrorCode.TICKET_INVALID_USE, java.util.Arrays.asList((UUID) null));
		verifyNoInteractions(repository);
	}

	private void assertError(TicketErrorCode expected, List<UUID> entries) {
		assertThatThrownBy(() -> service.refundAll(entries, "취소")).isInstanceOfSatisfying(TicketException.class,
				failure -> assertThat(failure.getErrorCode()).isEqualTo(expected));
	}

	private TicketHistory use(UUID entryId) {
		return new TicketHistory(UUID.randomUUID(), UUID.randomUUID(), TicketOperationType.USE, 2,
				TicketStatus.SPENT, NOW.plusSeconds(86400), entryId, null, null, "응모", NOW.minusSeconds(10));
	}

	private Ticket spent(TicketHistory use) {
		return new Ticket(use.getTicketId(), UUID.randomUUID(), new GrantSource(GrantSourceType.GAME, UUID.randomUUID()),
				TicketGrade.GOLD, TicketStatus.SPENT, use.getExpiresAt(), 2, NOW.minusSeconds(20), NOW.minusSeconds(10));
	}
}
