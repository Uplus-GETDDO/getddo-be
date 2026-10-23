package com.getddo.api.entry.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.getddo.core.entry.domain.EntryParticipant;
import com.getddo.core.user.domain.NamedValue;

/**
 * 관리자 이벤트 응모자(AO07) 한 명.
 *
 * @param userName 응모자의 현재 이름. 표시용이며 동일인 판단은 {@code userId}로 한다
 * @param usedTicketCount 이 이벤트에서 누적으로 차감한 응모권 수. 이벤트 취소로 반환해도 줄지 않는다
 * @param createdAt 최초 응모 접수 시각
 */
public record AdminParticipantResponse(
		UUID id,
		UUID eventId,
		UUID userId,
		String userName,
		long usedTicketCount,
		Instant createdAt) {

	public static AdminParticipantResponse from(NamedValue<EntryParticipant> named) {
		EntryParticipant participant = named.getValue();
		return new AdminParticipantResponse(participant.getId(), participant.getEventId(), participant.getUserId(),
				named.getName(), participant.getUsedTicketCount(), participant.getCreatedAt());
	}
}
