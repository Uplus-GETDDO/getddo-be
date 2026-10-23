package com.getddo.api.ticket.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.getddo.core.ticket.domain.TicketConsistencyResult;
import com.getddo.core.ticket.domain.TicketInconsistencyView;
import com.getddo.core.ticket.domain.TicketStatus;

/**
 * AO04 한 사용자의 응모권 정합성 확인 결과다.
 *
 * @param userId 확인한 사용자 ID
 * @param userName 확인한 사용자의 현재 이름. 표시용이며 동일인 판단은 {@code userId}로 한다
 * @param availableCount 확인 시각에 사용할 수 있는 응모권 수
 * @param isConsistent 어긋난 응모권이 없으면 true
 * @param inconsistencies 어긋난 응모권. 없으면 빈 목록
 * @param checkedAt 같은 기준 시점의 확인 시각
 */
public record TicketConsistencyResponse(UUID userId, String userName, long availableCount, boolean isConsistent,
		List<Inconsistency> inconsistencies, Instant checkedAt) {

	/**
	 * 어긋난 응모권 한 장.
	 *
	 * @param ticketVersion 응모권의 현재 버전
	 * @param latestHistoryVersion 마지막 이력의 버전. 이력이 없으면 0
	 * @param ticketStatus 응모권의 현재 상태
	 * @param latestHistoryStatus 마지막 이력의 상태. 이력이 없으면 null
	 */
	public record Inconsistency(UUID ticketId, long ticketVersion, long latestHistoryVersion,
			TicketStatus ticketStatus, TicketStatus latestHistoryStatus) {

		static Inconsistency from(TicketInconsistencyView view) {
			return new Inconsistency(view.getTicketId(), view.getTicketVersion(), view.getLatestHistoryVersion(),
					view.getTicketStatus(), view.getLatestHistoryStatus());
		}
	}

	/** 서비스가 반환한 확인 결과를 응답으로 옮긴다. */
	public static TicketConsistencyResponse from(TicketConsistencyResult result) {
		return new TicketConsistencyResponse(result.getUserId(), result.getUserName(), result.getAvailableCount(),
				result.isConsistent(), result.getInconsistencies().stream().map(Inconsistency::from).toList(),
				result.getCheckedAt());
	}
}
