package com.getddo.core.game.domain;

import java.util.ArrayList;
import java.util.List;

/** 주문별 난수 흐름을 이어 쓰며 같은 메뉴 세 개 연속은 다시 추출한다. 화면 장식 난수와 분리한다. */
public final class TakkoOrderGenerator {

	private static final TakkoOrderMenu[] MENUS = TakkoOrderMenu.values();
	private int state;

	public TakkoOrderGenerator(long seed) {
		if (seed < 1 || seed > 0xffff_ffffL) {
			throw new IllegalArgumentException("0이 아닌 unsigned 32-bit seed가 필요합니다.");
		}
		state = (int) seed;
	}

	/** 버린 메뉴도 난수를 소비하며 새 주문에서는 연속 메뉴 제한을 새로 시작한다. */
	public List<TakkoOrderMenu> next(int length) {
		if (length < TakkoOrderRules.START_LENGTH || length > TakkoOrderRules.MAX_LENGTH) {
			throw new IllegalArgumentException("주문 길이는 3~8이어야 합니다.");
		}
		List<TakkoOrderMenu> order = new ArrayList<>(length);
		while (order.size() < length) {
			state ^= state << 13;
			state ^= state >>> 17;
			state ^= state << 5;
			double random = Integer.toUnsignedLong(state) / 4294967296.0;
			TakkoOrderMenu menu = MENUS[(int) (random * MENUS.length)];
			int size = order.size();
			if (size >= 2 && order.get(size - 1) == menu && order.get(size - 2) == menu) continue;
			order.add(menu);
		}
		return List.copyOf(order);
	}
}
