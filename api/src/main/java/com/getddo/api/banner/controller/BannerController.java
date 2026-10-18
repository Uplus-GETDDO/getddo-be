package com.getddo.api.banner.controller;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.getddo.api.banner.config.BannerImageUrls;
import com.getddo.api.banner.dto.response.BannerResponse;
import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.core.banner.service.BannerService;
import com.getddo.core.user.domain.User;

@RestController
@RequestMapping("/api/v1/banners")
@RequiredArgsConstructor
public class BannerController {
	private final BannerService service;
	private final BannerImageUrls images;

	@GetMapping
	public ResponseEnvelope<List<BannerResponse>> list(@CurrentUser(membershipRequired = false) User actor) {
		return ResponseEnvelope.success(service.list(actor).stream()
				.map(banner -> BannerResponse.from(banner, images.resolve(banner.getImageKey()))).toList());
	}
}
