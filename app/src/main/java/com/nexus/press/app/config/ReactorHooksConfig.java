package com.nexus.press.app.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Hooks;

@Slf4j
@Component
public class ReactorHooksConfig {

	@PostConstruct
	public void registerHooks() {
		Hooks.onErrorDropped(throwable -> {
			if (isExpectedDroppedNetworkError(throwable)) {
				log.debug("Dropped reactive network error: {}", throwable.getMessage());
				return;
			}
			log.error("Dropped reactive error", throwable);
		});
	}

	@PreDestroy
	public void resetHooks() {
		Hooks.resetOnErrorDropped();
	}

	private boolean isExpectedDroppedNetworkError(final Throwable throwable) {
		return throwable instanceof WebClientRequestException
			|| throwable instanceof IOException;
	}
}
