package com.getddo.core.ticket.repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.getddo.core.ticket.domain.Ticket;
import com.getddo.core.ticket.domain.TicketHistory;

/** 이벤트 전체 취소용 묶음 반환 저장소. 모든 호출은 취소 트랜잭션에 참여한다. */
public interface TicketBulkRefundRepository {

	List<TicketHistory> findUses(Collection<UUID> entryIds);

	/** ID 순서로 잠가 최신 상태를 읽는다. 없는 응모권은 결과에서 빠진다. */
	List<Ticket> findTicketsForUpdate(Collection<UUID> ticketIds);

	/** 응모권 잠금 이후 읽어 앞선 반환의 커밋 결과를 확인한다. */
	Set<UUID> findRefundedUseIds(Collection<UUID> useIds);

	/** 원본 버전의 응모권 갱신과 반환 이력 추가. 한 건이라도 갱신되지 않으면 예외로 전체 롤백한다. */
	void saveRefunds(List<Ticket> returned, List<TicketHistory> histories);
}
