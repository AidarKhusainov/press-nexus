package com.nexus.press.app.brief.web;

import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import com.nexus.press.app.brief.format.DailyBriefFormatter;
import com.nexus.press.app.brief.model.BriefImportance;
import com.nexus.press.app.brief.model.DailyBriefItem;
import com.nexus.press.app.brief.usecase.BuildDailyBrief;
import com.nexus.press.app.telegram.usecase.DeliverDailyBriefToTelegramUsers;
import com.nexus.press.app.web.generated.api.BriefApiDelegate;
import com.nexus.press.app.web.generated.model.DailyBrief;
import com.nexus.press.app.web.generated.model.DailyBriefSendResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

@Component
@RequiredArgsConstructor
public class BriefController implements BriefApiDelegate {

	private final BuildDailyBrief buildDailyBrief;
	private final DailyBriefFormatter dailyBriefFormatter;
	private final DeliverDailyBriefToTelegramUsers deliverDailyBriefToTelegramUsers;

	@Override
	public Mono<DailyBrief> getDailyBrief(
		final Integer hours,
		final Integer limit,
		final String lang,
		final ServerWebExchange exchange
	) {
		return buildDailyBrief.execute(request(hours, limit, lang))
			.map(this::toApiBrief);
	}

	@Override
	public Mono<String> getDailyBriefText(
		final Integer hours,
		final Integer limit,
		final String lang,
		final ServerWebExchange exchange
	) {
		return buildDailyBrief.execute(request(hours, limit, lang))
			.map(dailyBriefFormatter::toTelegramMessage);
	}

	@Override
	public Mono<DailyBriefSendResponse> sendDailyBriefNow(final ServerWebExchange exchange) {
		return deliverDailyBriefToTelegramUsers.execute()
			.map(DailyBriefSendResponse::new);
	}

	private BuildDailyBrief.Request request(final Integer hours, final Integer limit, final String lang) {
		final int safeHours = hours == null ? 24 : hours;
		final int safeLimit = limit == null ? 7 : limit;
		final String safeLang = lang == null ? "ru" : lang;
		return new BuildDailyBrief.Request(
			Duration.ofHours(Math.max(1, Math.min(safeHours, 168))),
			Math.max(1, Math.min(safeLimit, 20)),
			safeLang,
			List.of()
		);
	}

	private DailyBrief toApiBrief(final com.nexus.press.app.brief.model.DailyBrief source) {
		final List<com.nexus.press.app.web.generated.model.DailyBriefItem> apiItems = source.items().stream()
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

	private com.nexus.press.app.web.generated.model.DailyBriefItem toApiBriefItem(final DailyBriefItem source) {
		return new com.nexus.press.app.web.generated.model.DailyBriefItem(
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

	private com.nexus.press.app.web.generated.model.DailyBriefItem.ImportanceEnum toApiImportance(final BriefImportance source) {
		return switch (source) {
			case MUST_KNOW -> com.nexus.press.app.web.generated.model.DailyBriefItem.ImportanceEnum.MUST_KNOW;
			case GOOD_TO_KNOW -> com.nexus.press.app.web.generated.model.DailyBriefItem.ImportanceEnum.GOOD_TO_KNOW;
		};
	}
}
