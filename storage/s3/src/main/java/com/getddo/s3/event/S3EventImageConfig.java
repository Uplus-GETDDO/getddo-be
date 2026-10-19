package com.getddo.s3.event;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/** AWS에서는 기본 자격 증명 체인을 사용한다. S3Mock 주소는 로컬에서만 명시적으로 주입한다. */
@Configuration
public class S3EventImageConfig {
	@Bean(destroyMethod = "close")
	@Lazy
	public S3Client eventImageS3Client(@Value("${getddo.images.region:${AWS_REGION:}}") String region,
			@Value("${getddo.images.endpoint:}") String endpoint) {
		S3ClientBuilder builder = S3Client.builder()
				.httpClientBuilder(UrlConnectionHttpClient.builder()
						.connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(10)))
				.overrideConfiguration(ClientOverrideConfiguration.builder()
						.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(10)).build());
		if (!region.isBlank()) {
			builder.region(Region.of(region));
		}
		if (!endpoint.isBlank()) {
			builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
		}
		return builder.build();
	}
}
