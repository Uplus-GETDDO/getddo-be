package com.getddo.api.banner.dto.request;

import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import com.getddo.core.banner.domain.BannerSettings;

@Builder
public record BannerWriteRequest(@NotNull UUID eventId, @NotBlank @Size(max = 500) String imageKey,
		@NotNull Integer displayOrder) {
	public BannerWriteRequest {
		imageKey = imageKey == null ? null : imageKey.strip();
	}

	public BannerSettings toDomain() {
		return new BannerSettings(eventId, imageKey, displayOrder);
	}
}
