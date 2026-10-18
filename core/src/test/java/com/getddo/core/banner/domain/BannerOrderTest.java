package com.getddo.core.banner.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.getddo.core.banner.exception.BannerErrorCode;
import com.getddo.core.common.exception.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BannerOrderTest {
	private static final UUID A = new UUID(0, 1);
	private static final UUID B = new UUID(0, 2);
	private static final UUID C = new UUID(0, 3);
	private static final UUID D = new UUID(0, 4);
	private static final UUID E = new UUID(0, 5);
	private static final UUID F = new UUID(0, 6);

	@ParameterizedTest
	@CsvSource({"1, DABC", "2, ADBC", "4, ABCD"})
	@DisplayName("맨앞·중간·마지막 삽입이 기존 배너를 뒤로 밀고 원본 목록은 유지한다")
	void insertsAtRequestedPosition(int position, String expected) {
		// given
		List<UUID> current = new ArrayList<>(List.of(A, B, C));
		// when
		List<UUID> result = BannerOrder.insert(current, D, position);
		// then
		assertThat(result).containsExactlyElementsOf(ids(expected));
		assertThat(current).containsExactly(A, B, C);
		assertThatThrownBy(() -> result.clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@ParameterizedTest
	@CsvSource({"1, BACD", "2, ABCD", "4, ACDB"})
	@DisplayName("기존 배너를 앞으로·같은 자리·뒤로 옮길 때 사이의 순서를 조정한다")
	void movesExistingBanner(int position, String expected) {
		// given / when
		List<UUID> result = BannerOrder.move(List.of(A, B, C, D), B, position);
		// then
		assertThat(result).containsExactlyElementsOf(ids(expected));
	}

	@Test
	@DisplayName("삭제하면 빈자리를 당기고 마지막 배너 삭제는 빈 목록이 된다")
	void closesGapAfterDeletion() {
		// given / when / then
		assertThat(BannerOrder.remove(List.of(A, B, C), A)).containsExactly(B, C);
		assertThat(BannerOrder.remove(List.of(A, B, C), B)).containsExactly(A, C);
		assertThat(BannerOrder.remove(List.of(A, B, C), C)).containsExactly(A, B);
		assertThat(BannerOrder.remove(List.of(A), A)).isEmpty();
	}

	@Test
	@DisplayName("비어 있는 관리 목록에1번을 등록할 수 있고 여섯 번째 등록은 거절한다")
	void enforcesTotalCapacity() {
		// given / when / then
		assertThat(BannerOrder.insert(List.of(), A, 1)).containsExactly(A);
		assertThatThrownBy(() -> BannerOrder.insert(List.of(A, B, C, D, E), F, 1))
				.isInstanceOfSatisfying(BusinessException.class,
						error -> assertThat(error.getErrorCode()).isEqualTo(BannerErrorCode.LIMIT_EXCEEDED));
	}

	@ParameterizedTest
	@ValueSource(ints = {-1, 0, 5})
	@DisplayName("현재 개수로 만들 수 없는 삽입 위치는 빈자리를 남기지 않고 거절한다")
	void rejectsPositionOutsideCurrentList(int position) {
		// given / when / then
		assertThatThrownBy(() -> BannerOrder.insert(List.of(A, B, C), D, position))
				.isInstanceOfSatisfying(BusinessException.class,
						error -> assertThat(error.getErrorCode()).isEqualTo(BannerErrorCode.INVALID_SETTINGS));
	}

	@Test
	@DisplayName("없는 배너의 이동과 삭제는 동일한 배너 부재 오류다")
	void rejectsMissingBanner() {
		// given / when / then
		assertThatThrownBy(() -> BannerOrder.move(List.of(A), B, 1)).isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.getErrorCode()).isEqualTo(BannerErrorCode.BANNER_NOT_FOUND));
		assertThatThrownBy(() -> BannerOrder.remove(List.of(A), B)).isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.getErrorCode()).isEqualTo(BannerErrorCode.BANNER_NOT_FOUND));
	}

	@Test
	@DisplayName("전체 순서는 같은 ID집합만 허용하며 빈 목록도 처리한다")
	void acceptsCompletePermutation() {
		// given
		List<UUID> request = new ArrayList<>(List.of(C, A, B));
		// when
		List<UUID> result = BannerOrder.reorder(List.of(A, B, C), request);
		request.clear();
		// then
		assertThat(result).containsExactly(C, A, B);
		assertThat(BannerOrder.reorder(List.of(), List.of())).isEmpty();
	}

	@Test
	@DisplayName("전체 순서의 누락·추가·중복·다른 ID는 목록 변경409로 거절한다")
	void rejectsChangedIdSet() {
		// given
		List<List<UUID>> requests = List.of(List.of(A, B), List.of(A, B, C, D), List.of(A, A, C), List.of(A, B, D));
		// when / then
		for (List<UUID> request : requests) {
			assertThatThrownBy(() -> BannerOrder.reorder(List.of(A, B, C), request))
					.isInstanceOfSatisfying(BusinessException.class,
							error -> assertThat(error.getErrorCode()).isEqualTo(BannerErrorCode.LIST_CHANGED));
		}
	}

	private static List<UUID> ids(String letters) {
		return letters.chars().mapToObj(letter -> new UUID(0, letter - 'A' + 1)).toList();
	}
}
