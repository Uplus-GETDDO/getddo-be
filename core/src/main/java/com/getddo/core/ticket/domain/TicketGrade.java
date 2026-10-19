package com.getddo.core.ticket.domain;

/**
 * 응모권 등급. {@code tickets.grade} ENUM과 같은 값을 가진다.
 *
 * <p>추첨 가중치는 브론즈 1, 실버 3, 골드 5, 플래티넘 10이다(ADR-025). 출석 보상은 브론즈로 고정하고, 미션·게임 보상은
 * 지급 건마다 서버가 무작위로 한 등급을 확정한다. 확정한 등급은 이후 바뀌지 않는다. 등급은 가중치를 정하는 값이며 실제
 * 장수는 항상 1장이다.</p>
 */
public enum TicketGrade {
	BRONZE,
	SILVER,
	GOLD,
	PLATINUM
}
