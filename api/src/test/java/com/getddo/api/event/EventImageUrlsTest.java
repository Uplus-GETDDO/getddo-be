package com.getddo.api.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.getddo.api.event.config.EventImageUrls;
import com.getddo.core.event.exception.EventException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventImageUrlsTest {
	@Test
	@DisplayName("공개 기준 주소에 상대 키를 인코딩해 붙이고 빈 값·경로 이탈은 노출하지 않는다")
	void resolvesKeysWithoutEscapingBasePath() {
		// given
		EventImageUrls urls = new EventImageUrls("https://cdn.example/images/");
		// when / then
		assertThat(urls.resolve("events/경품 #1.png")).isEqualTo("https://cdn.example/images/events/%EA%B2%BD%ED%92%88%20%231.png");
		assertThat(urls.resolve("../private.png")).isNull();
		assertThat(urls.resolve("/private.png")).isNull();
		assertThat(urls.resolve(null)).isNull();
		assertThat(new EventImageUrls("").resolve("a.png")).isNull();
		assertThatThrownBy(() -> new EventImageUrls("").requireConfigured()).isInstanceOf(EventException.class);
	}
}
