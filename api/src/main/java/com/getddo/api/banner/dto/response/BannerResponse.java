package com.getddo.api.banner.dto.response;

import java.util.UUID;
import lombok.Builder;
import com.getddo.core.banner.domain.Banner;

@Builder
public record BannerResponse(UUID id, UUID eventId, String imageUrl, int displayOrder) {
	public static BannerResponse from(Banner banner, String imageUrl) {
		return new BannerResponse(banner.getId(), banner.getEventId(), imageUrl, banner.getDisplayOrder());
	}
}
