package com.getddo.core.entry.repository;

import java.util.Optional;
import java.util.UUID;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.entry.domain.EntryParticipant;
import com.getddo.core.entry.domain.EntryParticipationTotals;

/** 응모자 저장소. 모든 메서드는 호출자 트랜잭션 안에서 호출한다. */
public interface EntryParticipantRepository {

	/** 이벤트에 응모한 사용자의 응모자를 잠그지 않고 읽는다. */
	Optional<EntryParticipant> find(UUID eventId, UUID userId);

	/**
	 * 이벤트에 응모한 사용자의 응모자를 PK로 잠가({@code FOR UPDATE}) 최신 값으로 읽는다. 아직 응모하지 않았으면 비어 있다.
	 *
	 * <p>없는 키를 범위로 잠그면 gap lock이 잡혀 교착할 수 있으므로, 잠그지 않고 ID를 찾은 뒤 그 행을 PK로 잠근다.</p>
	 */
	Optional<EntryParticipant> findForUpdate(UUID eventId, UUID userId);

	/**
	 * 새 응모자를 저장하고 즉시 DB에 반영한다. 같은 이벤트·사용자의 응모자가 동시에 만들어지면 UNIQUE 위반 예외가 난다.
	 *
	 * @param participant ID가 없는 응모자
	 * @return ID가 발급된 응모자
	 */
	EntryParticipant create(EntryParticipant participant);

	/** 이벤트의 접수 완료된 응모자 수와 누적 차감 수량 합계를 잠그지 않고 읽는다. 응모자가 없으면 0이다. */
	EntryParticipationTotals summarize(UUID eventId);

	/** 잠가 읽은 응모자의 누적 차감 수량을 늘린다. */
	void addUsedTicketCount(UUID participantId, long amount);

	/** 이벤트의 응모자를 최초 응모 시각 내림차순(같으면 ID 내림차순)으로 조회한다. 관리자 조회(AO07)용이며 잠그지 않는다. */
	PageResult<EntryParticipant> findByEvent(UUID eventId, PageQuery page);
}
