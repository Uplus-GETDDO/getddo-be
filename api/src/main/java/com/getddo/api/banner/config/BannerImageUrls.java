package com.getddo.api.banner.config;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.getddo.core.banner.service.BannerImageConfiguration;

/** 기존 공개 이미지 주소만 사용한다. 비공개 bucket·접근 주소는 응답에 포함하지 않는다. */
@Component
public class BannerImageUrls implements BannerImageConfiguration {
	private final String baseUrl;

	public BannerImageUrls(@Value("${getddo.images.base-url:}") String baseUrl) {
		this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
	}

	/** 미설정 값을 가짜 이미지 주소로 대체하지 않는다. 변경 요청은 저장 전에 호출한다. */
	@Override
	public void requireConfigured() {
		URI base;
		try {
			base = URI.create(baseUrl);
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("공개 이미지 기준 주소 설정이 올바르지 않습니다.", exception);
		}
		if (!("https".equalsIgnoreCase(base.getScheme()) || "http".equalsIgnoreCase(base.getScheme()))
				|| base.getHost() == null || base.getUserInfo() != null || base.getRawQuery() != null
				|| base.getRawFragment() != null) {
			throw new IllegalStateException("공개 이미지 기준 주소 설정이 필요합니다.");
		}
	}

	public String resolve(String imageKey) {
		requireConfigured();
		String encoded = URLEncoder.encode(imageKey.strip(), StandardCharsets.UTF_8)
				.replace("+", "%20").replace("%2F", "/");
		// 선행 /도 키의 일부로 보존한다. URL.resolve로 기준 경로나 호스트를 교체하지 않는다.
		return (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + encoded;
	}
}
