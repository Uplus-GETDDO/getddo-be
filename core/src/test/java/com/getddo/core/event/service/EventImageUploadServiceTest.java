package com.getddo.core.event.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.getddo.core.event.domain.EventImageTarget;
import com.getddo.core.event.exception.EventErrorCode;
import com.getddo.core.event.exception.EventException;
import com.getddo.core.event.repository.EventActorRepository;
import com.getddo.core.event.repository.EventImageStorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class EventImageUploadServiceTest {
	private final UUID admin = UUID.randomUUID();
	private EventActorRepository actors;
	private EventImageStorage storage;
	private EventImageUploadService service;

	@BeforeEach
	void setUp() {
		actors = mock(EventActorRepository.class);
		storage = mock(EventImageStorage.class);
		when(actors.findById(admin)).thenReturn(Optional.of(new EventActorRepository.Actor(true, true)));
		service = new EventImageUploadService(actors, storage);
	}

	@Test
	@DisplayName("JPG와 PNG를 실제 형식으로 확인하고 서버가 생성한 키로 원본 바이트를 저장한다")
	void storesVerifiedJpgAndPngWithUniqueKeys() throws Exception {
		// given
		byte[] png = image("png");
		byte[] jpg = image("jpg");
		// when
		String first = service.upload(admin, EventImageTarget.EVENT, "../같은이름.PNG", "image/png", png);
		String second = service.upload(admin, EventImageTarget.PRIZE, "상품.jpeg", "image/jpeg", jpg);
		// then
		assertThat(first).matches("events/[0-9a-f-]{36}\\.png");
		assertThat(second).matches("prizes/[0-9a-f-]{36}\\.jpg");
		verify(storage).upload(first, "image/png", png);
		verify(storage).upload(second, "image/jpeg", jpg);
	}

	@Test
	@DisplayName("십진 5MB까지 허용하고 5,000,001바이트부터 저장하지 않는다")
	void enforcesDecimalMegabyteBoundary() throws Exception {
		// given: 유효한 이미지 뒤에 패딩을 넣어 파일 전체 바이트 상한을 검증한다.
		byte[] boundary = Arrays.copyOf(image("png"), 5_000_000);
		// when / then
		String key = service.upload(admin, EventImageTarget.EVENT, "a.png", "image/png", boundary);
		verify(storage).upload(key, "image/png", boundary);
		clearInvocations(storage);
		rejects("a.png", "image/png", Arrays.copyOf(boundary, 5_000_001));
		verifyNoInteractions(storage);
	}

	@Test
	@DisplayName("빈 파일·다른 형식·확장자 위장·MIME 불일치·잘린 헤더는 S3 호출 전에 거절한다")
	void rejectsInvalidFilesBeforeStorage() throws Exception {
		// given / when / then
		rejects("a.png", "image/png", new byte[0]);
		rejects("a.gif", "image/gif", image("gif"));
		rejects("a.png", "image/png", image("jpg"));
		rejects("a.jpg", "image/png", image("jpg"));
		rejects("a.png", "image/png", "<svg>fake</svg>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		rejects("a.png", "image/png", Arrays.copyOf(image("png"), 12));
		verifyNoInteractions(storage);
	}

	@Test
	@DisplayName("미등록·비활성·일반 사용자는 이미지를 저장할 수 없다")
	void rejectsNonAdministrators() throws Exception {
		// given
		byte[] png = image("png");
		// when / then
		assertActorRejected(Optional.empty(), EventErrorCode.USER_CONTEXT_REQUIRED, png);
		assertActorRejected(Optional.of(new EventActorRepository.Actor(false, true)), EventErrorCode.ADMIN_REQUIRED, png);
		assertActorRejected(Optional.of(new EventActorRepository.Actor(true, false)), EventErrorCode.ADMIN_REQUIRED, png);
		verifyNoInteractions(storage);
	}

	@Test
	@DisplayName("요청자 누락은 조회 전에 401로, 이미지 대상 누락은 저장 전에 400으로 거절한다")
	void rejectsNullActorAndTargetBeforeStorage() throws Exception {
		// given
		byte[] png = image("png");
		// when / then
		assertThatThrownBy(() -> service.upload(null, EventImageTarget.EVENT, "a.png", "image/png", png))
				.isInstanceOfSatisfying(EventException.class, exception -> {
					assertThat(exception.getErrorCode()).isEqualTo(EventErrorCode.USER_CONTEXT_REQUIRED);
					assertThat(exception.getErrorCode().getStatus()).isEqualTo(401);
				});
		verifyNoInteractions(actors, storage);
		assertThatThrownBy(() -> service.upload(admin, null, "a.png", "image/png", png))
				.isInstanceOfSatisfying(EventException.class, exception -> {
					assertThat(exception.getErrorCode()).isEqualTo(EventErrorCode.INVALID_IMAGE);
					assertThat(exception.getErrorCode().getStatus()).isEqualTo(400);
				});
		verifyNoInteractions(storage);
	}

	private void assertActorRejected(Optional<EventActorRepository.Actor> actor, EventErrorCode expected, byte[] png) {
		when(actors.findById(admin)).thenReturn(actor);
		assertThatThrownBy(() -> service.upload(admin, EventImageTarget.EVENT, "a.png", "image/png", png))
				.isInstanceOfSatisfying(EventException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
	}

	private void rejects(String name, String type, byte[] data) {
		assertThatThrownBy(() -> service.upload(admin, EventImageTarget.EVENT, name, type, data))
				.isInstanceOfSatisfying(EventException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(EventErrorCode.INVALID_IMAGE));
	}

	private byte[] image(String format) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertThat(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, output)).isTrue();
		return output.toByteArray();
	}
}
