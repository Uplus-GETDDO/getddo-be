package com.getddo.core.banner.domain;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.getddo.core.banner.exception.BannerErrorCode;
import com.getddo.core.common.exception.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BannerSettingsTest {
	private static final UUID EVENT = new UUID(0, 1);

	@ParameterizedTest
	@ValueSource(ints = {1, 5})
	@DisplayName("양끝 순서를 허용하고 이미지 키의 앞뒤 공백을 제거한다")
	void normalizesKeyAndAcceptsOrderBounds(int order) {
		// given / when
		BannerSettings settings = new BannerSettings(EVENT, "  banners/one.png  ", order);
		// then
		assertThat(settings.getEventId()).isEqualTo(EVENT);
		assertThat(settings.getImageKey()).isEqualTo("banners/one.png");
		assertThat(settings.getDisplayOrder()).isEqualTo(order);
	}

	@ParameterizedTest
	@ValueSource(ints = {-1, 0, 6})
	@DisplayName("순서가1~5 밖이면 설정 오류로 거절한다")
	void rejectsInvalidOrder(int order) {
		// given / when / then
		assertThatThrownBy(() -> new BannerSettings(EVENT, "one.png", order))
				.isInstanceOfSatisfying(BusinessException.class,
						error -> assertThat(error.getErrorCode()).isEqualTo(BannerErrorCode.INVALID_SETTINGS));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "\t", "\n"})
	@DisplayName("공백뿐인 이미지 키는 저장 가능한 설정으로 만들지 않는다")
	void rejectsBlankKey(String key) {
		// given / when / then
		assertThatThrownBy(() -> new BannerSettings(EVENT, key, 1)).isInstanceOf(BusinessException.class);
	}

	@Test
	@DisplayName("필수 이벤트와 키를 검증하고 공백 제거 후500자는 허용하며501자는 거절한다")
	void validatesRequiredFieldsAndKeyLength() {
		// given / when / then
		assertThat(new BannerSettings(EVENT, " " + "x".repeat(500) + " ", 1).getImageKey()).hasSize(500);
		assertThatThrownBy(() -> new BannerSettings(null, "one.png", 1)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> new BannerSettings(EVENT, null, 1)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> new BannerSettings(EVENT, "x".repeat(501), 1))
				.isInstanceOfSatisfying(BusinessException.class,
						error -> assertThat(error.getErrorCode()).isEqualTo(BannerErrorCode.INVALID_SETTINGS));
	}

	@ParameterizedTest
	@ValueSource(strings = {".", "..", "../outside.png", "./one.png", "banners/../one.png",
			"banners/./one.png", "banners/.", "banners/..", "  ../outside.png  "})
	@DisplayName("공개 이미지 기준 경로를 바꾸는 점 세그먼트는 등록·수정 전에 거절한다")
	void rejectsDotSegments(String key) {
		// given / when / then
		assertThatThrownBy(() -> new BannerSettings(EVENT, key, 1))
				.isInstanceOfSatisfying(BusinessException.class,
						error -> assertThat(error.getErrorCode()).isEqualTo(BannerErrorCode.INVALID_SETTINGS));
	}

	@ParameterizedTest
	@ValueSource(strings = {"banners/one.png", "banners/.hidden.png", "banners/one..png", "banners/.../one.png"})
	@DisplayName("이름에 점이 있어도 경로 전체가 점 세그먼트가 아니면 허용한다")
	void preservesDottedFileNames(String key) {
		// given / when / then
		assertThat(new BannerSettings(EVENT, key, 1).getImageKey()).isEqualTo(key);
	}
}
