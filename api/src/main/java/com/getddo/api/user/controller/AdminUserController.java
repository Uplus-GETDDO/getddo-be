package com.getddo.api.user.controller;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.exception.CommonErrorCode;
import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.api.user.dto.request.AdminUserQueryRequest;
import com.getddo.api.user.dto.response.AdminUserListResponse;
import com.getddo.api.user.dto.response.AdminUserResponse;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.service.AdminUserQueryService;

/** 관리자 사용자 목록·상세의 요청 해석과 공개 필드 변환을 담당한다. */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {
	private final AdminUserQueryService service;

	@GetMapping
	public ResponseEnvelope<AdminUserListResponse> list(@CurrentUser(membershipRequired = false) User actor,
			@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
			@ModelAttribute @ParameterObject AdminUserQueryRequest request, BindingResult bindingResult) {
		if (bindingResult.hasErrors()) {
			throw new BusinessException(CommonErrorCode.INVALID_FORMAT);
		}
		return ResponseEnvelope.success(AdminUserListResponse.from(service.findAll(actor, request.toDomain(page, size))));
	}

	@GetMapping("/{userId}")
	public ResponseEnvelope<AdminUserResponse> detail(@CurrentUser(membershipRequired = false) User actor, @PathVariable UUID userId) {
		return ResponseEnvelope.success(AdminUserResponse.from(service.findById(actor, userId)));
	}
}
