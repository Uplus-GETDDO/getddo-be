package com.getddo.core.banner.domain;

import java.util.Arrays;
import java.util.UUID;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import com.getddo.core.banner.exception.BannerErrorCode;
import com.getddo.core.common.exception.BusinessException;

/** 배너 등록·수정의 필수 설정을 생성 시 검증한다. 이벤트 존재·상태는 서비스가 확인한다. */
@Getter
@EqualsAndHashCode
public final class BannerSettings {
	private final UUID eventId;
	private final String imageKey;
	private final int displayOrder;

	public BannerSettings(UUID eventId, String imageKey, int displayOrder) {
		String normalized = imageKey == null ? null : imageKey.strip();
		if (eventId == null || normalized == null || normalized.isBlank() || normalized.length() > 500
				|| displayOrder < 1 || displayOrder > 5
				|| Arrays.stream(normalized.split("/", -1))
						.anyMatch(segment -> segment.equals(".") || segment.equals(".."))) {
			throw new BusinessException(BannerErrorCode.INVALID_SETTINGS);
		}
		this.eventId = eventId;
		this.imageKey = normalized;
		this.displayOrder = displayOrder;
	}
}
