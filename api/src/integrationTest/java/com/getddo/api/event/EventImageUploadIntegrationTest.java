package com.getddo.api.event;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.getddo.api.GetddoBeApplication;
import com.getddo.db.common.util.UuidBinary;
import com.getddo.db.support.MySqlTestConfiguration;
import com.jayway.jsonpath.JsonPath;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 HTTP multipart 파서·MySQL 사용자 문맥·S3 어댑터를 함께 검증한다. */
@Testcontainers
@SpringBootTest(classes = GetddoBeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({MySqlTestConfiguration.class, EventImageUploadIntegrationTest.S3Fixture.class})
class EventImageUploadIntegrationTest {
	private static final String BUCKET = "getddo-image-integration";
	@Container
	static final GenericContainer<?> S3 = new GenericContainer<>(DockerImageName.parse(
			"adobe/s3mock@sha256:ab01a6946750f451ca215a47e91030695b260e4003b8a5a6201d25029b8fca92"))
			.withExposedPorts(9090)
			.waitingFor(Wait.forHttp("/").forPort(9090).forStatusCode(200))
			.withStartupTimeout(Duration.ofMinutes(2));
	@LocalServerPort private int port;
	@Autowired private JdbcTemplate jdbc;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final List<UUID> createdUserIds = new ArrayList<>();
	private final List<UUID> createdEventIds = new ArrayList<>();
	private UUID admin;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("getddo.images.bucket", () -> BUCKET);
		registry.add("getddo.images.base-url", () -> endpoint() + "/" + BUCKET + "/images/");
	}

	@BeforeEach
	void setUp() {
		admin = insertUser("ADMIN", null);
	}

	@AfterEach
	void cleanUp() {
		// HTTP 요청의 서비스 트랜잭션은 테스트와 별개다. 이 테스트가 만든 행만 FK 순서대로 삭제한다.
		for (UUID userId : createdUserIds) {
			jdbc.update("delete from audit_logs where actor_id = ?", UuidBinary.toBytes(userId));
		}
		for (UUID eventId : createdEventIds) {
			jdbc.update("delete from event_prizes where event_id = ?", UuidBinary.toBytes(eventId));
			jdbc.update("delete from events where id = ?", UuidBinary.toBytes(eventId));
		}
		for (UUID userId : createdUserIds) {
			jdbc.update("delete from users where id = ?", UuidBinary.toBytes(userId));
		}
	}

	@Test
	@DisplayName("작은 PNG·JPG를 BE로 업로드하고 반환 URL에서 동일 바이트를 조회하며 이벤트·경품 응답에도 연결한다")
	void uploadsSmallImagesAndConnectsEventAndPrizeUrls() throws Exception {
		// given / when
		byte[] png = image("png");
		byte[] jpg = image("jpg");
		HttpResponse<byte[]> eventImage = upload(admin, "ADMIN", "EVENT", "event.png", "image/png", png);
		HttpResponse<byte[]> prizeImage = upload(admin, "ADMIN", "PRIZE", "prize.jpg", "image/jpeg", jpg);
		// then: 실제 S3Mock의 URL·Content-Type·저장 바이트 확인
		String eventKey = verifyUploaded(eventImage, png, "image/png");
		String prizeKey = verifyUploaded(prizeImage, jpg, "image/jpeg");
		String json = """
				{"title":"S3 연동","description":"작은 이미지 확인","imageKey":"%s","eventType":"NO_TICKET",
				"weightingEnabled":false,"membershipRule":"excellent","startsAt":"2099-10-17T00:00:00Z",
				"endsAt":"2099-10-18T00:00:00Z","prizes":[{"rank":1,"name":"이미지 경품","imageKey":"%s","winnerCount":1}]}
				""".formatted(eventKey, prizeKey);
		HttpResponse<byte[]> registered = send("POST", "/api/v1/admin/events", admin, "ADMIN", "application/json",
				json.getBytes(StandardCharsets.UTF_8));
		assertThat(registered.statusCode()).as(body(registered)).isEqualTo(201);
		String id = JsonPath.read(body(registered), "$.data.id");
		createdEventIds.add(UUID.fromString(id));
		for (String path : new String[]{"/api/v1/events/" + id, "/api/v1/admin/events/" + id}) {
			HttpResponse<byte[]> detail = send("GET", path, admin, "ADMIN", null, null);
			assertThat(detail.statusCode()).as(body(detail)).isEqualTo(200);
			assertThat((String) JsonPath.read(body(detail), "$.data.imageUrl")).endsWith("/images/" + eventKey);
			assertThat((String) JsonPath.read(body(detail), "$.data.prizes[0].imageUrl")).endsWith("/images/" + prizeKey);
		}
		for (String path : new String[]{"/api/v1/events", "/api/v1/admin/events"}) {
			HttpResponse<byte[]> list = send("GET", path, admin, "ADMIN", null, null);
			assertThat(list.statusCode()).as(body(list)).isEqualTo(200);
			List<String> imageUrls = JsonPath.read(body(list), "$.data.items[*].imageUrl");
			assertThat(imageUrls).contains(endpoint() + "/" + BUCKET + "/images/" + eventKey);
		}
	}

	@Test
	@DisplayName("실제 multipart 요청에서도 5,000,000바이트는 허용하고 1바이트 초과는 413으로 거절한다")
	void appliesExactFiveMbLimitAtHttpBoundary() throws Exception {
		// given
		byte[] boundary = Arrays.copyOf(image("png"), 5_000_000);
		// when / then
		verifyUploaded(upload(admin, "ADMIN", "EVENT", "limit.png", "image/png", boundary), boundary, "image/png");
		HttpResponse<byte[]> tooLarge = upload(admin, "ADMIN", "EVENT", "limit.png", "image/png",
				Arrays.copyOf(boundary, 5_000_001));
		assertThat(tooLarge.statusCode()).as(body(tooLarge)).isEqualTo(413);
	}

	@Test
	@DisplayName("위장한 이미지와 일반 사용자 요청은 각각 400·403으로 거절한다")
	void rejectsSpoofedImageAndNonAdmin() throws Exception {
		// given / when / then
		HttpResponse<byte[]> fake = upload(admin, "ADMIN", "EVENT", "fake.png", "image/png",
				"not an image".getBytes(StandardCharsets.UTF_8));
		assertThat(fake.statusCode()).as(body(fake)).isEqualTo(400);
		assertThat((String) JsonPath.read(body(fake), "$.code")).isEqualTo("EVENT-018");
		UUID user = insertUser("USER", "excellent");
		HttpResponse<byte[]> forbidden = upload(user, "USER", "EVENT", "a.png", "image/png", image("png"));
		assertThat(forbidden.statusCode()).as(body(forbidden)).isEqualTo(403);
		assertThat((String) JsonPath.read(body(forbidden), "$.code")).isEqualTo("EVENT-006");
	}

	private String verifyUploaded(HttpResponse<byte[]> response, byte[] contents, String type) throws Exception {
		assertThat(response.statusCode()).as(body(response)).isEqualTo(201);
		String key = JsonPath.read(body(response), "$.data.imageKey");
		String url = JsonPath.read(body(response), "$.data.imageUrl");
		HttpResponse<byte[]> fetched = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());
		assertThat(fetched.statusCode()).isEqualTo(200);
		assertThat(fetched.headers().firstValue("Content-Type")).contains(type);
		assertThat(fetched.body()).isEqualTo(contents);
		return key;
	}

	private HttpResponse<byte[]> upload(UUID actor, String role, String target, String filename, String type, byte[] contents)
			throws Exception {
		String boundary = "getddo-" + UUID.randomUUID();
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
				+ "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(contents);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		return send("POST", "/api/v1/admin/events/images?target=" + target, actor, role,
				"multipart/form-data; boundary=" + boundary, body.toByteArray());
	}

	private HttpResponse<byte[]> send(String method, String path, UUID actor, String role, String type, byte[] contents)
			throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
				.timeout(Duration.ofSeconds(60)).header("X-User-ID", actor.toString()).header("X-User-Role", role);
		if ("USER".equals(role)) {
			request.header("X-User-Membership", "excellent");
		}
		if (type != null) {
			request.header("Content-Type", type);
		}
		return http.send(request.method(method, contents == null ? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofByteArray(contents)).build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	private static String endpoint() {
		return "http://" + S3.getHost() + ":" + S3.getMappedPort(9090);
	}

	private String body(HttpResponse<byte[]> response) {
		return new String(response.body(), StandardCharsets.UTF_8);
	}

	private byte[] image(String format) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertThat(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, output))
				.as("테스트 %s 이미지 생성", format).isTrue();
		return output.toByteArray();
	}

	private UUID insertUser(String role, String membership) {
		UUID id = UUID.randomUUID();
		createdUserIds.add(id);
		jdbc.update("insert into users(id,name,role,status,membership,created_at,updated_at) values(?,?,?,'ACTIVE',?,?,?)",
				UuidBinary.toBytes(id), "이미지 테스트", role, membership,
				java.sql.Timestamp.valueOf("2026-10-17 00:00:00"), java.sql.Timestamp.valueOf("2026-10-17 00:00:00"));
		return id;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class S3Fixture {
		/** 운영의 기본 자격 증명 체인을 바꾸지 않고, 이 테스트 전용 클라이언트만 대체한다. */
		@Bean(destroyMethod = "close")
		@Primary
		S3Client testImageS3Client() {
			S3Client client = S3Client.builder().region(Region.AP_NORTHEAST_2).endpointOverride(URI.create(endpoint()))
					.forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(
							AwsBasicCredentials.create("s3mock", "s3mock"))).build();
			client.createBucket(request -> request.bucket(BUCKET));
			return client;
		}
	}
}
