package com.getddo.s3.event;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.getddo.core.event.exception.EventErrorCode;
import com.getddo.core.event.exception.EventException;
import com.getddo.core.event.repository.EventImageStorage;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** 비공개 버킷의 images/ 아래에 저장한다. 공개 URL의 /images/와 상대 imageKey는 별도로 관리한다. */
@Component
@Slf4j
public class S3EventImageStorage implements EventImageStorage {
	private final ObjectProvider<S3Client> clients;
	private final String bucket;

	public S3EventImageStorage(ObjectProvider<S3Client> clients,
			@Value("${getddo.images.bucket:}") String bucket) {
		this.clients = clients;
		this.bucket = bucket;
	}

	@Override
	public void upload(String imageKey, String contentType, byte[] contents) {
		if (bucket.isBlank()) {
			throw new EventException(EventErrorCode.IMAGE_STORAGE_UNAVAILABLE);
		}
		try {
			clients.getObject().putObject(PutObjectRequest.builder().bucket(bucket).key("images/" + imageKey)
					.contentType(contentType).contentLength((long) contents.length).build(), RequestBody.fromBytes(contents));
		} catch (SdkException | BeansException exception) {
			log.warn("이벤트 이미지 저장에 실패했습니다.", exception);
			throw new EventException(EventErrorCode.IMAGE_STORAGE_UNAVAILABLE, exception);
		}
	}
}
