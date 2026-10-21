package com.getddo.api.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.info.InfoEndpointAutoConfiguration;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsEndpointAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.actuate.web.WebMvcEndpointManagementContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MetricsEndpointExposureTest {

	@Test
	@DisplayName("Actuator 추가 후에도 metrics·info·health·discovery HTTP 경로를 공개하지 않는다")
	void noPublicActuatorEndpoints() {
		// given
		new WebApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withPropertyValues("management.endpoints.web.exposure.include=*")
			.withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
			.withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class, EndpointAutoConfiguration.class,
				WebEndpointAutoConfiguration.class, InfoEndpointAutoConfiguration.class, MetricsEndpointAutoConfiguration.class))
			.withUserConfiguration(WebMvcEndpointManagementContextConfiguration.class)
			.run(context -> {
				// when / then
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(WebEndpointsSupplier.class).getEndpoints()).isEmpty();
				MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
				for (String path : new String[] {"/actuator", "/actuator/metrics", "/actuator/info", "/actuator/health"}) {
					mvc.perform(get(path)).andExpect(status().isNotFound());
				}
			});
	}
}
