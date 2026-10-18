package com.getddo.api.banner.dto.response;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import com.getddo.core.banner.domain.Banner;

@Builder
public record AdminBannerResponse(UUID id, UUID eventId, String imageUrl, int displayOrder,
		String imageKey, Instant createdAt, Instant updatedAt) {
	public static AdminBannerResponse from(Banner banner, String imageUrl) {
		return new AdminBannerResponse(banner.getId(), banner.getEventId(), imageUrl, banner.getDisplayOrder(),
				banner.getImageKey(), banner.getCreatedAt(), banner.getUpdatedAt());
	}
}
