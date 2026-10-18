package com.getddo.s3.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.getddo.core.event.exception.EventErrorCode;
import com.getddo.core.event.exception.EventException;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3EventImageStorageTest {
	@Test
	@DisplayName("S3 실패를 공개 503 업무 오류로 변환하고 진단 원인은 보존한다")
	void preservesStorageFailureCause() {
		// given
		S3Client client = mock(S3Client.class);
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		beans.addBean("s3", client);
		S3EventImageStorage storage = new S3EventImageStorage(beans.getBeanProvider(S3Client.class), "images-test");
		SdkClientException cause = SdkClientException.create("test storage failure");
		when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(cause);
		// when / then
		assertThatThrownBy(() -> storage.upload("events/a.png", "image/png", new byte[]{1}))
				.isInstanceOfSatisfying(EventException.class, exception -> {
					assertThat(exception.getErrorCode()).isEqualTo(EventErrorCode.IMAGE_STORAGE_UNAVAILABLE);
					assertThat(exception.getCause()).isSameAs(cause);
				});
	}

	@Test
	@DisplayName("버킷 미설정 상태에서는 AWS 클라이언트를 생성하거나 업로드하지 않는다")
	void unconfiguredStorageFailsWithoutClient() {
		// given
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		S3EventImageStorage storage = new S3EventImageStorage(beans.getBeanProvider(S3Client.class), "");
		// when / then
		assertThatThrownBy(() -> storage.upload("events/a.png", "image/png", new byte[]{1}))
				.isInstanceOfSatisfying(EventException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(EventErrorCode.IMAGE_STORAGE_UNAVAILABLE));
	}
}
