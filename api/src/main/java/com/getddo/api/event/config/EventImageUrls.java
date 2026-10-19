package com.getddo.api.event.config;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.getddo.core.event.exception.EventErrorCode;
import com.getddo.core.event.exception.EventException;

/** 저장소 접근 주소 대신 설정된 공개 이미지 주소와 키를 조합한다. DB 조회나 객체 업로드는 하지 않는다. */
@Component
public class EventImageUrls {
	private final String baseUrl;

	public EventImageUrls(@Value("${getddo.images.base-url:}") String baseUrl) {
		this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
		if (!this.baseUrl.isEmpty()) {
			URI parsed = URI.create(this.baseUrl);
			if (!("https".equalsIgnoreCase(parsed.getScheme()) || "http".equalsIgnoreCase(parsed.getScheme()))
					|| parsed.getHost() == null || parsed.getUserInfo() != null
					|| parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
				throw new IllegalArgumentException("공개 이미지 기본 주소가 올바르지 않습니다.");
			}
		}
	}

	/** 업로드 성공 후 사용할 공개 주소가 없으면 S3 저장 전에 거절한다. */
	public void requireConfigured() {
		if (baseUrl.isEmpty()) {
			throw new EventException(EventErrorCode.IMAGE_STORAGE_UNAVAILABLE);
		}
	}

	/** 미설정·이미지 없음은 기존 null 계약을 유지한다. 이전의 잘못된 경로로 기준 주소 밖을 가리키지 않는다. */
	public String resolve(String imageKey) {
		if (baseUrl.isEmpty() || imageKey == null || imageKey.isBlank()) {
			return null;
		}
		String key = imageKey.strip();
		if (key.startsWith("/") || key.contains("\\")
				|| Arrays.stream(key.split("/", -1)).anyMatch(segment -> segment.equals(".") || segment.equals(".."))) {
			return null;
		}
		String encoded = URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20").replace("%2F", "/");
		return (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + encoded;
	}
}
