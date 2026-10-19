package com.getddo.core.game.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.getddo.core.game.domain.GamePlay;
import com.getddo.core.game.domain.TakkoOrderGenerator;
import com.getddo.core.game.domain.TakkoOrderMenu;
import com.getddo.core.game.domain.TakkoOrderRoundInput;
import com.getddo.core.game.domain.TakkoOrderRules;
import com.getddo.core.game.domain.TakkoOrderSubmission;

import static org.assertj.core.api.Assertions.assertThat;

class TakkoOrderValidatorTest {

	private final TakkoOrderValidator validator = new TakkoOrderValidator();
	private final GamePlay play = new GamePlay(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			TakkoOrderRules.RULE_VERSION, "{\"initialLives\":3,\"startLength\":3,\"maxLength\":8}",
			"a".repeat(64), TakkoOrderRules.GENERATOR_VERSION, 1L, Instant.parse("2026-10-18T00:00:00Z"), null, null, null);

	@Test
	@DisplayName("seed1의 고정 주문은 세 번째 실패에서18점으로 확정하며 버린 난수도 다음 주문에 영향을 준다")
	void replaysFixedOrders() {
		// given: FE 연산식으로 생성한 참조 입력이며 Java/TS 교차 실행 성공을 뜻하지 않는다.
		TakkoOrderSubmission input = input("scallion scallion shrimp", "scallion shrimp scallion scallion",
				"scallion cheese shrimp shrimp cheese", "shrimp scallion scallion nori scallion nori",
				"cheese", "scallion", "cheese");
		// when / then
		assertThat(validator.validate(play, input)).isEqualTo(18L);
	}

	@Test
	@DisplayName("빈 기록·목숨 잔여·미완성·첫 오답 이후 선택·종료 이후 주문·불연속 번호는 INVALID다")
	void rejectsInvalidOrders() {
		// given
		List<TakkoOrderSubmission> invalid = List.of(input(), input("cheese"), input("scallion"),
				input("cheese scallion"), input("cheese", "cheese", "cheese", "cheese"),
				new TakkoOrderSubmission(List.of(new TakkoOrderRoundInput(2, List.of(TakkoOrderMenu.CHEESE)))),
				new TakkoOrderSubmission(List.of(new TakkoOrderRoundInput(1, List.of()))));
		// when / then
		for (TakkoOrderSubmission candidate : invalid) assertThat(validator.validate(play, candidate)).isNull();
	}

	@Test
	@DisplayName("오답은 길이를 늘리지 않고 성공은8에서 멈추며 세 번째 오답까지만 인정한다")
	void preservesLengthAfterFailureAndCapsAtEight() {
		// given: 첫 오답 뒤7회 성공, 마지막 두 오답으로 종료한다.
		TakkoOrderGenerator generator = new TakkoOrderGenerator(1);
		List<TakkoOrderRoundInput> rounds = new ArrayList<>();
		int length = 3;
		rounds.add(wrong(1, generator.next(length)));
		for (int success = 0; success < 7; success++) {
			rounds.add(new TakkoOrderRoundInput(rounds.size() + 1, generator.next(length)));
			length = Math.min(8, length + 1);
		}
		rounds.add(wrong(rounds.size() + 1, generator.next(length)));
		rounds.add(wrong(rounds.size() + 1, generator.next(length)));
		// when / then
		assertThat(validator.validate(play, new TakkoOrderSubmission(rounds))).isEqualTo(41L);
	}

	@Test
	@DisplayName("첫 선택에서 세 번 실패한 자연 종료는 유효0점이다")
	void acceptsZero() {
		// given
		TakkoOrderGenerator generator = new TakkoOrderGenerator(1);
		List<TakkoOrderRoundInput> rounds = new ArrayList<>();
		for (int sequence = 1; sequence <= 3; sequence++) rounds.add(wrong(sequence, generator.next(3)));
		// when / then
		assertThat(validator.validate(play, new TakkoOrderSubmission(rounds))).isZero();
	}

	private static TakkoOrderRoundInput wrong(int sequence, List<TakkoOrderMenu> expected) {
		TakkoOrderMenu wrong = expected.getFirst() == TakkoOrderMenu.SCALLION ? TakkoOrderMenu.CHEESE : TakkoOrderMenu.SCALLION;
		return new TakkoOrderRoundInput(sequence, List.of(wrong));
	}

	private static TakkoOrderSubmission input(String... orders) {
		List<TakkoOrderRoundInput> rounds = new ArrayList<>();
		for (String order : orders) {
			List<TakkoOrderMenu> picks = new ArrayList<>();
			for (String menu : order.split(" ")) picks.add(TakkoOrderMenu.valueOf(menu.toUpperCase(Locale.ROOT)));
			rounds.add(new TakkoOrderRoundInput(rounds.size() + 1, picks));
		}
		return new TakkoOrderSubmission(rounds);
	}
}
