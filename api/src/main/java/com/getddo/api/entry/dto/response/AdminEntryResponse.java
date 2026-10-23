package com.getddo.api.entry.dto.response;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import lombok.Builder;

import com.getddo.core.entry.domain.Entry;
import com.getddo.core.entry.domain.EntryStatus;
import com.getddo.core.entry.domain.EntrySummary;
import com.getddo.core.ticket.domain.TicketGrade;
import com.getddo.core.user.domain.NamedValue;

/**
 * 관리자 응모 내역(AO06) 한 건. 응모 결과({@link EntryReceiptResponse})의 필드에 응모한 사용자와 응모자 ID를 더했다.
 *
 * @param userId 응모한 사용자
 * @param userName 응모한 사용자의 현재 이름. 표시용이며 동일인 판단은 {@code userId}로 한다
 * @param participantId 접수된 응모의 이벤트 응모자. 거절된 요청은 {@code null}
 */
@Builder
public record AdminEntryResponse(
		UUID id,
		UUID userId,
		String userName,
		UUID participantId,
		UUID eventId,
		String eventTitle,
		long requestedTicketCount,
		Map<TicketGrade, Long> requestedTicketsByGrade,
		long deductedTicketCount,
		Map<TicketGrade, Long> deductedTicketsByGrade,
		EntryStatus status,
		Instant requestedAt,
		Instant acceptedAt,
		String rejectionCode,
		String rejectionReason) {

	public static AdminEntryResponse from(NamedValue<EntrySummary> named) {
		EntrySummary summary = named.getValue();
		Entry entry = summary.getEntry();
		return AdminEntryResponse.builder()
				.id(entry.getId())
				.userId(entry.getUserId())
				.userName(named.getName())
				.participantId(entry.getParticipantId())
				.eventId(entry.getEventId())
				.eventTitle(summary.getEventTitle())
				.requestedTicketCount(entry.getRequestedTicketCount())
				.requestedTicketsByGrade(entry.getRequestedTicketsByGrade())
				.deductedTicketCount(entry.getDeductedTicketCount())
				.deductedTicketsByGrade(entry.getDeductedTicketsByGrade())
				.status(entry.getStatus())
				.requestedAt(entry.getCreatedAt())
				.acceptedAt(entry.isRejected() ? null : entry.getCreatedAt())
				.rejectionCode(entry.getRejectionCode())
				.rejectionReason(entry.getRejectionReason())
				.build();
	}
}
