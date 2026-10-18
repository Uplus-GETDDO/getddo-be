package com.getddo.api.banner.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BannerImageUrlsTest {
	@Test
	@DisplayName("공개 기준 경로에 키를 붙이고 공백·한글·쿼리 문자를 경로로 인코딩한다")
	void buildsPublicImageUrl() {
		// given
		BannerImageUrls images = new BannerImageUrls("https://cdn.example.test/images/");
		// when / then
		assertThat(images.resolve(" banner/한 글?#.png "))
				.isEqualTo("https://cdn.example.test/images/banner/%ED%95%9C%20%EA%B8%80%3F%23.png");
		assertThat(new BannerImageUrls("https://cdn.example.test/images").resolve("a.png"))
				.isEqualTo("https://cdn.example.test/images/a.png");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "bucket-name", "file:///tmp/images", "https://user:secret@example.test/", "https://cdn.test/?key=1"})
	@DisplayName("미설정·저장소 이름·비HTTP·자격 증명·쿼리 기준 주소를 거절한다")
	void rejectsInvalidPublicConfiguration(String value) {
		// given / when / then
		assertThatThrownBy(() -> new BannerImageUrls(value).requireConfigured()).isInstanceOf(IllegalStateException.class);
	}
}
