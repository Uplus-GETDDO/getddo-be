package com.getddo.core.event.service;

import java.util.LinkedHashMap;
import java.util.Map;

import com.getddo.core.event.domain.RegisteredEvent;

/** 이벤트 설정과 경품 변경을 복원할 수 있는 업무 필드만 공통 감사 JSON으로 변환한다. */
final class EventAuditSnapshot {
	private EventAuditSnapshot() { }

	static Map<String, Object> from(RegisteredEvent event) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("title", event.getTitle());
		data.put("description", event.getDescription());
		data.put("imageKey", event.getImageKey());
		data.put("detailImageKey", event.getDetailImageKey());
		data.put("eventType", event.getEventType().name());
		data.put("weightingEnabled", event.isWeightingEnabled());
		data.put("maxTicketsPerUser", event.getMaxTicketsPerUser());
		data.put("membershipRule", event.getMembershipRule().name());
		data.put("startsAt", event.getStartsAt().toString());
		data.put("endsAt", event.getEndsAt().toString());
		data.put("status", event.getStatus().name());
		data.put("prizes", event.getPrizes().stream().map(EventAuditSnapshot::prize).toList());
		return data;
	}

	private static Map<String, Object> prize(RegisteredEvent.Prize prize) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("id", prize.getId().toString());
		data.put("rank", prize.getRank());
		data.put("name", prize.getName());
		data.put("description", prize.getDescription());
		data.put("imageKey", prize.getImageKey());
		data.put("winnerCount", prize.getWinnerCount());
		return data;
	}
}
