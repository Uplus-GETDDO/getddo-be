package com.getddo.db.entry.mapper;

import java.util.Map;

import org.mapstruct.Mapper;

import com.getddo.core.entry.domain.Entry;
import com.getddo.core.entry.domain.EntryParticipant;
import com.getddo.core.ticket.domain.TicketGrade;
import com.getddo.db.entry.entity.EventEntryEntity;
import com.getddo.db.entry.entity.EventParticipantEntity;

/**
 * 응모자·응모 Entity와 도메인 객체 변환.
 *
 * <p>응모자 ID는 저장할 때 발급되므로 Entity로 옮기지 않는다. 응모 결과는 등급별 요청 장수를 컬럼 세 개로 나누어 저장하므로
 * 두 방향 모두 직접 변환한다.</p>
 */
@Mapper(componentModel = "spring")
public interface EntryMapper {

	EntryParticipant toDomain(EventParticipantEntity entity);

	EventParticipantEntity toEntity(EntryParticipant participant);

	default EventEntryEntity toEntity(Entry entry) {
		Map<TicketGrade, Long> requested = entry.getRequestedTicketsByGrade();
		return new EventEntryEntity(entry.getId(), entry.getEventId(), entry.getParticipantId(), entry.getUserId(),
				entry.getStatus(), entry.getRejectionCode(), entry.getRejectionReason(),
				entry.getRequestedTicketCount(), entry.getDeductedTicketCount(),
				Math.toIntExact(requested.get(TicketGrade.BRONZE)), Math.toIntExact(requested.get(TicketGrade.SILVER)),
				Math.toIntExact(requested.get(TicketGrade.GOLD)), Math.toIntExact(requested.get(TicketGrade.PLATINUM)),
				entry.getCreatedAt());
	}

	default Entry toDomain(EventEntryEntity entity) {
		Map<TicketGrade, Long> requested = Map.of(TicketGrade.BRONZE, (long) entity.getRequestedBronzeCount(),
				TicketGrade.SILVER, (long) entity.getRequestedSilverCount(), TicketGrade.GOLD,
				(long) entity.getRequestedGoldCount(), TicketGrade.PLATINUM, (long) entity.getRequestedPlatinumCount());
		return new Entry(entity.getId(), entity.getParticipantId(), entity.getEventId(), entity.getUserId(),
				entity.getStatus(), entity.getRequestedTicketCount(), requested, entity.getDeductedTicketCount(),
				entity.getRejectionCode(), entity.getRejectionReason(), entity.getCreatedAt());
	}
}
