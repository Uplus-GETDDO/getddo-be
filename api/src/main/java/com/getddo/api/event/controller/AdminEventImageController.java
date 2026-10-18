package com.getddo.api.event.controller;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.api.event.config.EventImageUrls;
import com.getddo.api.event.dto.response.EventImageUploadResponse;
import com.getddo.core.event.domain.EventImageTarget;
import com.getddo.core.event.exception.EventErrorCode;
import com.getddo.core.event.exception.EventException;
import com.getddo.core.event.service.EventImageUploadService;
import com.getddo.core.user.domain.User;

/** 이미지 한 장을 먼저 업로드하고, 반환한 키로 기존 이벤트·경품 등록 API를 호출한다. */
@RestController
@RequestMapping("/api/v1/admin/events/images")
public class AdminEventImageController {
	private final EventImageUploadService uploads;
	private final EventImageUrls imageUrls;

	public AdminEventImageController(EventImageUploadService uploads, EventImageUrls imageUrls) {
		this.uploads = uploads;
		this.imageUrls = imageUrls;
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<ResponseEnvelope<EventImageUploadResponse>> upload(@CurrentUser User user,
			@RequestParam EventImageTarget target, @RequestPart("file") MultipartFile file) {
		// byte[] 생성 전에도 크기를 확인한다. 서비스는 HTTP 외의 호출에도 같은 상한을 보장한다.
		if (file.isEmpty() || file.getSize() > EventImageUploadService.MAX_BYTES) {
			throw new EventException(EventErrorCode.INVALID_IMAGE);
		}
		imageUrls.requireConfigured();
		try {
			String key = uploads.upload(user.getId(), target, file.getOriginalFilename(), file.getContentType(), file.getBytes());
			return ResponseEntity.status(HttpStatus.CREATED)
					.body(ResponseEnvelope.success(new EventImageUploadResponse(key, imageUrls.resolve(key))));
		} catch (IOException exception) {
			throw new EventException(EventErrorCode.INVALID_IMAGE, exception);
		}
	}
}
