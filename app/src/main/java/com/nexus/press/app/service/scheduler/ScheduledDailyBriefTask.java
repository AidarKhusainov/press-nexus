package com.nexus.press.app.service.scheduler;

import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import com.nexus.press.app.config.property.TelegramProperties;
import com.nexus.press.app.service.delivery.DailyBriefDeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledDailyBriefTask {

	private final DailyBriefDeliveryService dailyBriefDeliveryService;
	private final TelegramProperties telegramProperties;
	private Disposable subscription;

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		final TelegramProperties.Delivery delivery = telegramProperties.delivery();
		if (!delivery.enabled()) {
			log.info("Планировщик daily brief отключен (platform.telegram.delivery.enabled=false)");
			return;
		}

		final Duration interval = delivery.interval() == null
			? Duration.ofHours(24)
			: delivery.interval();

		subscription = Flux.interval(Duration.ofSeconds(30), interval)
			.concatMap(tick -> dailyBriefDeliveryService.deliverNow()
				.onErrorResume(error -> {
					log.warn("Ошибка в scheduled daily brief delivery на тике {}", tick, error);
					return Mono.empty();
				}))
			.doOnNext(sentCount -> log.info("Daily brief отправлен в {} Telegram чатов", sentCount))
			.subscribe(
				null,
				error -> log.error("Планировщик daily brief остановлен из-за необработанной ошибки", error)
			);
	}

	@PreDestroy
	public void stop() {
		if (subscription != null && !subscription.isDisposed()) {
			subscription.dispose();
			log.info("Daily brief scheduler stopped");
		}
	}
}
