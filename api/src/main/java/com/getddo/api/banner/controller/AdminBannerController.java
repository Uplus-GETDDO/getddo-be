package com.getddo.api.banner.controller;

import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.getddo.api.banner.config.BannerImageUrls;
import com.getddo.api.banner.dto.request.BannerOrderRequest;
import com.getddo.api.banner.dto.request.BannerWriteRequest;
import com.getddo.api.banner.dto.response.AdminBannerResponse;
import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.core.banner.domain.Banner;
import com.getddo.core.banner.service.BannerService;
import com.getddo.core.user.domain.User;

@RestController
@RequestMapping("/api/v1/admin/banners")
@RequiredArgsConstructor
public class AdminBannerController {
	private final BannerService service;
	private final BannerImageUrls images;

	@GetMapping
	public ResponseEnvelope<List<AdminBannerResponse>> list(@CurrentUser(membershipRequired = false) User actor) {
		return ResponseEnvelope.success(service.listForAdmin(actor).stream().map(this::response).toList());
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ResponseEnvelope<AdminBannerResponse> create(@CurrentUser(membershipRequired = false) User actor,
			@Valid @RequestBody BannerWriteRequest request) {
		return ResponseEnvelope.success(response(service.create(actor, request.toDomain())));
	}

	@PutMapping("/{bannerId}")
	public ResponseEnvelope<AdminBannerResponse> update(@CurrentUser(membershipRequired = false) User actor,
			@PathVariable UUID bannerId, @Valid @RequestBody BannerWriteRequest request) {
		return ResponseEnvelope.success(response(service.update(actor, bannerId, request.toDomain())));
	}

	@DeleteMapping("/{bannerId}")
	public ResponseEnvelope<Void> delete(@CurrentUser(membershipRequired = false) User actor, @PathVariable UUID bannerId) {
		service.delete(actor, bannerId);
		return ResponseEnvelope.success(null);
	}

	@PutMapping("/order")
	public ResponseEnvelope<List<AdminBannerResponse>> reorder(@CurrentUser(membershipRequired = false) User actor,
			@Valid @RequestBody BannerOrderRequest request) {
		return ResponseEnvelope.success(service.reorder(actor, request.bannerIds()).stream().map(this::response).toList());
	}

	private AdminBannerResponse response(Banner banner) {
		return AdminBannerResponse.from(banner, images.resolve(banner.getImageKey()));
	}
}
