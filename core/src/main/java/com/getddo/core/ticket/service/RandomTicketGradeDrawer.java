package com.getddo.core.ticket.service;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.getddo.core.ticket.domain.GrantSourceType;
import com.getddo.core.ticket.domain.TicketGrade;
import com.getddo.core.ticket.domain.TicketGradeDrawer;

/**
 * 확률로 응모권 등급을 정하는 기본 구현.
 *
 * <p>출석은 브론즈로 고정한다. 미션·게임은 0~9999 사이 정수를 하나 뽑아 등급을 나눈다. 확률은 1만 분율(basis point,
 * 100bp = 1%)이며 0.02% 같은 소수 확률을 표현하기 위해 100 단위 대신 쓴다. 실버·골드·플래티넘 확률은 설정값이고 브론즈는
 * 나머지(10000에서 셋을 뺀 값)다. 설정이 음수이거나 합이 10000을 넘으면 시작할 때 실패한다. 개별 사용자나 일정 횟수 안에서
 * 등급 비율을 맞추지 않는 단순 무작위다.</p>
 *
 * <p>설정 기본값은 실버 18%(1800bp), 골드 2%(200bp), 플래티넘 0%다. 플래티넘은 추첨 스냅샷이 플래티넘 가중치를
 * 지원하기 전까지 지급하지 않으려고 0으로 두며, 확정 확률 0.02%(2bp)는 지급을 켤 때 설정한다.</p>
 */
@Component
public class RandomTicketGradeDrawer implements TicketGradeDrawer {

	static final int TOTAL = 10_000;

	private final RandomGenerator random;
	private final int bronzeUpperBound;
	private final int silverUpperBound;
	private final int goldUpperBound;

	@Autowired
	public RandomTicketGradeDrawer(
			@Value("${getddo.ticket.grade-probability.silver-bp:1800}") int silverBp,
			@Value("${getddo.ticket.grade-probability.gold-bp:200}") int goldBp,
			@Value("${getddo.ticket.grade-probability.platinum-bp:0}") int platinumBp) {
		this(new SecureRandom(), silverBp, goldBp, platinumBp);
	}

	/**
	 * @throws IllegalArgumentException 확률이 음수이거나 실버·골드·플래티넘의 합이 10000을 넘는 경우
	 */
	RandomTicketGradeDrawer(RandomGenerator random, int silverBp, int goldBp, int platinumBp) {
		if (silverBp < 0 || goldBp < 0 || platinumBp < 0 || (long) silverBp + goldBp + platinumBp > TOTAL) {
			throw new IllegalArgumentException("등급 확률은 0 이상이고 실버·골드·플래티넘의 합이 10000bp 이하여야 한다.");
		}
		this.random = random;
		this.bronzeUpperBound = TOTAL - silverBp - goldBp - platinumBp;
		this.silverUpperBound = bronzeUpperBound + silverBp;
		this.goldUpperBound = silverUpperBound + goldBp;
	}

	@Override
	public TicketGrade draw(GrantSourceType type) {
		if (type == GrantSourceType.ATTENDANCE) {
			return TicketGrade.BRONZE;
		}
		int roll = random.nextInt(TOTAL);
		if (roll < bronzeUpperBound) {
			return TicketGrade.BRONZE;
		}
		if (roll < silverUpperBound) {
			return TicketGrade.SILVER;
		}
		if (roll < goldUpperBound) {
			return TicketGrade.GOLD;
		}
		return TicketGrade.PLATINUM;
	}
}
