package com.getddo.core.event.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.springframework.stereotype.Service;

import com.getddo.core.event.domain.EventImageTarget;
import com.getddo.core.event.exception.EventErrorCode;
import com.getddo.core.event.exception.EventException;
import com.getddo.core.event.repository.EventActorRepository;
import com.getddo.core.event.repository.EventImageStorage;

/** 관리자 이미지의 크기·종류를 확인하고 저장 키를 생성한다. S3 호출 동안 DB 트랜잭션을 유지하지 않는다. */
@Service
public class EventImageUploadService {
	/** 5MB는 십진 단위이며 경계값을 포함한다. */
	public static final int MAX_BYTES = 5_000_000;
	private final EventActorRepository actors;
	private final EventImageStorage storage;

	public EventImageUploadService(EventActorRepository actors, EventImageStorage storage) {
		this.actors = actors;
		this.storage = storage;
	}

	public String upload(UUID actorId, EventImageTarget target, String filename, String contentType, byte[] contents) {
		if (actorId == null) {
			throw new EventException(EventErrorCode.USER_CONTEXT_REQUIRED);
		}
		EventActorRepository.Actor actor = actors.findById(actorId)
				.orElseThrow(() -> new EventException(EventErrorCode.USER_CONTEXT_REQUIRED));
		if (!actor.isActive() || !actor.isAdmin()) {
			throw new EventException(EventErrorCode.ADMIN_REQUIRED);
		}
		if (target == null || filename == null || contents == null || contents.length == 0
				|| contents.length > MAX_BYTES) {
			throw new EventException(EventErrorCode.INVALID_IMAGE);
		}
		String name = filename.toLowerCase(Locale.ROOT);
		boolean png = name.endsWith(".png") && "image/png".equalsIgnoreCase(contentType);
		boolean jpg = (name.endsWith(".jpg") || name.endsWith(".jpeg"))
				&& "image/jpeg".equalsIgnoreCase(contentType);
		if (!png && !jpg) {
			throw new EventException(EventErrorCode.INVALID_IMAGE);
		}
		validateImageHeader(contents, png ? "png" : "JPEG");
		// 파일명을 키에 사용하지 않아 경로 조작·동명 이미지 덮어쓰기를 방지한다.
		String key = target.getDirectory() + "/" + UUID.randomUUID() + (png ? ".png" : ".jpg");
		storage.upload(key, png ? "image/png" : "image/jpeg", contents);
		return key;
	}

	/** 확장자만 바꾼 파일을 거절한다. 전체 픽셀을 메모리에 풀지 않고 시그니처·형식·크기 헤더를 읽는다. */
	private void validateImageHeader(byte[] contents, String expectedFormat) {
		try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(contents))) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) {
				throw new EventException(EventErrorCode.INVALID_IMAGE);
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				if (!expectedFormat.equalsIgnoreCase(reader.getFormatName())
						|| reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0) {
					throw new EventException(EventErrorCode.INVALID_IMAGE);
				}
			} finally {
				reader.dispose();
			}
		} catch (IOException exception) {
			throw new EventException(EventErrorCode.INVALID_IMAGE, exception);
		}
	}
}
