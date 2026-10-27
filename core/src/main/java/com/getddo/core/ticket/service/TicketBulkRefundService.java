package com.getddo.core.ticket.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.ticket.domain.Ticket;
import com.getddo.core.ticket.domain.TicketExpiry;
import com.getddo.core.ticket.domain.TicketHistory;
import com.getddo.core.ticket.domain.TicketStatus;
import com.getddo.core.ticket.exception.TicketErrorCode;
import com.getddo.core.ticket.exception.TicketException;
import com.getddo.core.ticket.repository.TicketBulkRefundRepository;

/** 이벤트 전체 취소에서 개별 응모마다 DB를 왕복하지 않고 반환을 묶어서 처리한다. */
@Service
@RequiredArgsConstructor
public class TicketBulkRefundService {

	private static final int BATCH_SIZE = 500;
	private final TicketBulkRefundRepository repository;
	private final TimeProvider timeProvider;

	/**
	 * 이벤트 쓰기 잠금과 READ COMMITTED를 확보한 취소 서비스에서 호출한다.
	 * 묶음은 SQL 크기와 메모리 사용을 제한할 뿐, 커밋 경계가 아니다. 마지막 실패도 앞선 반환을 함께 롤백한다.
	 * 기존 원본 USE의 반환 이력이 있으면 중복 반환하지 않고 수량만 포함한다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public long refundAll(List<UUID> entryIds, String reason) {
		if (entryIds == null || entryIds.stream().anyMatch(java.util.Objects::isNull) || reason == null || reason.isBlank()) {
			throw new TicketException(TicketErrorCode.TICKET_INVALID_USE);
		}
		List<UUID> distinctEntries = entryIds.stream().distinct().toList();
		Instant refundedAt = timeProvider.now().truncatedTo(ChronoUnit.MICROS);
		Instant expiresAt = TicketExpiry.forRefund(refundedAt, timeProvider);
		long quantity = 0;
		for (int offset = 0; offset < distinctEntries.size(); offset += BATCH_SIZE) {
			List<UUID> entries = distinctEntries.subList(offset, Math.min(offset + BATCH_SIZE, distinctEntries.size()));
			List<TicketHistory> uses = repository.findUses(entries);
			Set<UUID> foundEntries = uses.stream().map(TicketHistory::getEventEntryId).collect(Collectors.toSet());
			if (!foundEntries.equals(new HashSet<>(entries))) {
				throw new TicketException(TicketErrorCode.TICKET_USE_NOT_FOUND);
			}
			// 한 응모가 다수 응모권을 사용해도 SQL 파라미터 수가 무한히 커지지 않도록 나눈다.
			for (int start = 0; start < uses.size(); start += BATCH_SIZE) {
				List<TicketHistory> batch = uses.subList(start, Math.min(start + BATCH_SIZE, uses.size()));
				refundBatch(batch, reason, refundedAt, expiresAt);
				quantity = Math.addExact(quantity, batch.size());
			}
		}
		return quantity;
	}

	private void refundBatch(List<TicketHistory> uses, String reason, Instant refundedAt, Instant expiresAt) {
		Map<UUID, Ticket> tickets = repository.findTicketsForUpdate(uses.stream().map(TicketHistory::getTicketId).toList())
				.stream().collect(Collectors.toMap(Ticket::getId, Function.identity()));
		Set<UUID> refunded = repository.findRefundedUseIds(uses.stream().map(TicketHistory::getId).toList());
		List<Ticket> returned = new ArrayList<>();
		List<TicketHistory> histories = new ArrayList<>();
		Set<UUID> pendingTickets = new HashSet<>();
		for (TicketHistory use : uses) {
			if (refunded.contains(use.getId())) {
				continue;
			}
			Ticket ticket = tickets.get(use.getTicketId());
			if (ticket == null || ticket.getStatus() != TicketStatus.SPENT || ticket.getVersion() != use.getTicketVersion()
					|| !pendingTickets.add(ticket.getId())) {
				throw new TicketException(TicketErrorCode.TICKET_REFUND_STATE_MISMATCH);
			}
			Ticket next = ticket.refund(refundedAt, expiresAt);
			returned.add(next);
			histories.add(TicketHistory.refund(next, use.getId(), reason));
		}
		if (!returned.isEmpty()) {
			repository.saveRefunds(returned, histories);
		}
	}
}
