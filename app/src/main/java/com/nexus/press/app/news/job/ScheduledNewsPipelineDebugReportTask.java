package com.nexus.press.app.news.job;

import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import com.nexus.press.app.config.property.NewsPipelineProperties;
import com.nexus.press.app.config.property.TelegramProperties;
import com.nexus.press.app.news.format.NewsPipelineDebugReportFormatter;
import com.nexus.press.app.news.support.PipelineRuntimeStats;
import com.nexus.press.app.news.usecase.BuildNewsPipelineDebugReport;
import com.nexus.press.app.telegram.integration.TelegramBotGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledNewsPipelineDebugReportTask {

	private final BuildNewsPipelineDebugReport buildNewsPipelineDebugReport;
	private final NewsPipelineDebugReportFormatter newsPipelineDebugReportFormatter;
	private final TelegramBotGateway telegramBotGateway;
	private final NewsPipelineProperties newsPipelineProperties;
	private final TelegramProperties telegramProperties;
	private final PipelineRuntimeStats pipelineRuntimeStats;
	private Disposable subscription;

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		if (!newsPipelineProperties.isDebugReportEnabled()) {
			log.info("Планировщик pipeline debug report отключен (press.news.pipeline.debug-report-enabled=false)");
			return;
		}
		if (telegramProperties.delivery().chatIds().isEmpty()) {
			log.info("Планировщик pipeline debug report отключен: нет platform.telegram.delivery.chat-ids");
			return;
		}
		if (telegramProperties.bot().token().isBlank()) {
			log.info("Планировщик pipeline debug report отключен: пустой platform.telegram.bot.token");
			return;
		}

		final Duration interval = newsPipelineProperties.getDebugReportInterval();
		subscription = Flux.interval(Duration.ofMinutes(1), interval)
			.concatMap(tick -> sendReport()
				.onErrorResume(error -> {
					log.warn("Ошибка в scheduled pipeline debug report на тике {}", tick, error);
					return Mono.empty();
				}))
			.subscribe(
				null,
				error -> log.error("Планировщик pipeline debug report остановлен из-за необработанной ошибки", error)
			);
	}

	private Mono<Void> sendReport() {
		return buildNewsPipelineDebugReport.execute()
			.map(newsPipelineDebugReportFormatter::toTelegramMessage)
			.flatMap(message -> Flux.fromIterable(telegramProperties.delivery().chatIds())
				.concatMap(chatId -> telegramBotGateway.sendMessage(
					telegramProperties.bot().token(),
					chatId,
					message
				))
				.then())
			.doOnSuccess(ignored -> {
				pipelineRuntimeStats.reset();
				log.info("Pipeline debug report отправлен в {} Telegram чатов",
					telegramProperties.delivery().chatIds().size());
			});
	}

	@PreDestroy
	public void stop() {
		if (subscription != null && !subscription.isDisposed()) {
			subscription.dispose();
			log.info("Pipeline debug report scheduler stopped");
		}
	}
}
