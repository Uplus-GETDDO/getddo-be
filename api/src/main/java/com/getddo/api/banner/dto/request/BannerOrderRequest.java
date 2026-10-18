package com.getddo.api.banner.dto.request;

import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

/** ID 집합·중복·목록 경합 검증은 도메인에서 BANNER-005로 처리한다. */
@Builder
public record BannerOrderRequest(@NotNull List<UUID> bannerIds) {
}
