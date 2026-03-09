package com.nexus.press.app.controller;

import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import com.nexus.press.app.service.brief.DailyBriefFormatter;
import com.nexus.press.app.service.brief.DailyBriefService;
import com.nexus.press.app.service.delivery.DailyBriefDeliveryService;
import com.nexus.press.app.web.generated.api.BriefApiDelegate;
import com.nexus.press.app.web.generated.model.DailyBrief;
import com.nexus.press.app.web.generated.model.DailyBriefItem;
import com.nexus.press.app.web.generated.model.DailyBriefSendResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

@Component
@RequiredArgsConstructor
public class BriefController implements BriefApiDelegate {

	private final DailyBriefService dailyBriefService;
	private final DailyBriefFormatter dailyBriefFormatter;
	private final DailyBriefDeliveryService dailyBriefDeliveryService;

	@Override
	public Mono<DailyBrief> getDailyBrief(
		final Integer hours,
		final Integer limit,
		final String lang,
		final ServerWebExchange exchange
	) {
		final int safeHours = hours == null ? 24 : hours;
		final int safeLimit = limit == null ? 7 : limit;
		final String safeLang = lang == null ? "ru" : lang;
		final var lookback = Duration.ofHours(Math.max(1, Math.min(safeHours, 168)));
		final int items = Math.max(1, Math.min(safeLimit, 20));
		return dailyBriefService.buildBrief(lookback, items, safeLang)
			.map(this::toApiBrief);
	}

	@Override
	public Mono<String> getDailyBriefText(
		final Integer hours,
		final Integer limit,
		final String lang,
		final ServerWebExchange exchange
	) {
		final int safeHours = hours == null ? 24 : hours;
		final int safeLimit = limit == null ? 7 : limit;
		final String safeLang = lang == null ? "ru" : lang;
		final var lookback = Duration.ofHours(Math.max(1, Math.min(safeHours, 168)));
		final int items = Math.max(1, Math.min(safeLimit, 20));
		return dailyBriefService.buildBrief(lookback, items, safeLang)
			.map(dailyBriefFormatter::toTelegramMessage);
	}

	@Override
	public Mono<DailyBriefSendResponse> sendDailyBriefNow(final ServerWebExchange exchange) {
		return dailyBriefDeliveryService.deliverNow()
			.map(DailyBriefSendResponse::new);
	}

	private DailyBrief toApiBrief(final com.nexus.press.app.service.brief.model.DailyBrief source) {
		final List<DailyBriefItem> apiItems = source.items().stream()
			.map(this::toApiBriefItem)
			.toList();
		return new DailyBrief(
			source.generatedAt(),
			source.from(),
			source.to(),
			source.language(),
			apiItems
		);
	}

	private DailyBriefItem toApiBriefItem(final com.nexus.press.app.service.brief.model.DailyBriefItem source) {
		return new DailyBriefItem(
			source.newsId(),
			source.title(),
			source.url(),
			source.media(),
			source.eventAt(),
			toApiImportance(source.importance()),
			source.whatHappened(),
			source.whyImportant(),
			source.whatNext()
		);
	}

	private DailyBriefItem.ImportanceEnum toApiImportance(final com.nexus.press.app.service.brief.model.BriefImportance source) {
		return switch (source) {
			case MUST_KNOW -> DailyBriefItem.ImportanceEnum.MUST_KNOW;
			case GOOD_TO_KNOW -> DailyBriefItem.ImportanceEnum.GOOD_TO_KNOW;
		};
	}
}
