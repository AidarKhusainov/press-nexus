package com.nexus.press.app.telegram.usecase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.nexus.press.app.brief.format.DailyBriefFormatter;
import com.nexus.press.app.brief.model.DailyBrief;
import com.nexus.press.app.brief.usecase.BuildDailyBrief;
import com.nexus.press.app.config.property.TelegramProperties;
import com.nexus.press.app.observability.AppMetrics;
import com.nexus.press.app.profile.model.DigestFrequency;
import com.nexus.press.app.profile.model.UserProfile;
import com.nexus.press.app.profile.usecase.FindUsersDueForDigest;
import com.nexus.press.app.profile.usecase.MarkDigestDelivered;
import com.nexus.press.app.telegram.integration.TelegramBotGateway;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliverDailyBriefToTelegramUsersTest {

	@Mock
	private BuildDailyBrief buildDailyBrief;
	@Mock
	private DailyBriefFormatter dailyBriefFormatter;
	@Mock
	private TelegramBotGateway telegramBotGateway;
	@Mock
	private FindUsersDueForDigest findUsersDueForDigest;
	@Mock
	private MarkDigestDelivered markDigestDelivered;
	@Mock
	private AppMetrics appMetrics;
	@Mock
	private Timer.Sample timerSample;

	private DeliverDailyBriefToTelegramUsers service;

	@BeforeEach
	void setUp() {
		when(appMetrics.startJobTimer()).thenReturn(timerSample);
	}

	@Test
	void executeReturnsZeroWhenDeliveryDisabled() {
		service = new DeliverDailyBriefToTelegramUsers(
			buildDailyBrief,
			dailyBriefFormatter,
			telegramBotGateway,
			properties(false, List.of(), null),
			findUsersDueForDigest,
			markDigestDelivered,
			appMetrics
		);

		assertEquals(0, service.execute().block());

		verify(findUsersDueForDigest, never()).execute(any());
		verify(appMetrics).jobSuccess(eq("daily_brief_delivery"), eq(timerSample));
	}

	@Test
	void executeBuildsAndDeliversPersonalizedBriefForDueUsers() {
		final UserProfile user = profile("12345", "ru", DigestFrequency.EVERY_2_DAYS, List.of("world", "economy"));
		final DailyBrief brief = emptyBrief("ru");
		service = new DeliverDailyBriefToTelegramUsers(
			buildDailyBrief,
			dailyBriefFormatter,
			telegramBotGateway,
			properties(true, List.of(), "bot-token"),
			findUsersDueForDigest,
			markDigestDelivered,
			appMetrics
		);

		when(findUsersDueForDigest.execute(any())).thenReturn(Flux.just(user));
		when(buildDailyBrief.execute(any(BuildDailyBrief.Request.class))).thenReturn(Mono.just(brief));
		when(dailyBriefFormatter.toTelegramHeader(brief)).thenReturn("header");
		when(telegramBotGateway.sendMessage(eq("bot-token"), eq("12345"), anyString())).thenReturn(Mono.empty());
		when(markDigestDelivered.execute(eq("12345"), any())).thenReturn(Mono.empty());

		assertEquals(1, service.execute().block());

		verify(buildDailyBrief).execute(argThat(request ->
			request != null
				&& request.maxItems() == 7
				&& "ru".equals(request.language())
				&& List.of("world", "economy").equals(List.copyOf(request.topics()))
				&& request.lookback() != null
		));
		verify(telegramBotGateway).sendMessage("bot-token", "12345", "header");
		verify(telegramBotGateway).sendMessage("bot-token", "12345", "За выбранный период подтвержденных новостей пока нет.");
		verify(markDigestDelivered).execute(eq("12345"), any());
	}

	@Test
	void executeFallsBackToConfiguredLegacyChatIdsWhenNoUsersDue() {
		final DailyBrief brief = emptyBrief("ru");
		service = new DeliverDailyBriefToTelegramUsers(
			buildDailyBrief,
			dailyBriefFormatter,
			telegramBotGateway,
			properties(true, List.of("chat-1", "chat-2"), "bot-token"),
			findUsersDueForDigest,
			markDigestDelivered,
			appMetrics
		);

		when(findUsersDueForDigest.execute(any())).thenReturn(Flux.empty());
		when(buildDailyBrief.execute(any(BuildDailyBrief.Request.class))).thenReturn(Mono.just(brief));
		when(dailyBriefFormatter.toTelegramHeader(brief)).thenReturn("header");
		when(telegramBotGateway.sendMessage(eq("bot-token"), anyString(), anyString())).thenReturn(Mono.empty());

		assertEquals(2, service.execute().block());

		verify(buildDailyBrief).execute(argThat(request ->
			request != null
				&& Duration.ofHours(24).equals(request.lookback())
				&& request.maxItems() == 7
				&& "ru".equals(request.language())
				&& request.topics().isEmpty()
		));
		verify(telegramBotGateway).sendMessage("bot-token", "chat-1", "header");
		verify(telegramBotGateway).sendMessage("bot-token", "chat-1", "За выбранный период подтвержденных новостей пока нет.");
		verify(telegramBotGateway).sendMessage("bot-token", "chat-2", "header");
		verify(telegramBotGateway).sendMessage("bot-token", "chat-2", "За выбранный период подтвержденных новостей пока нет.");
		verify(markDigestDelivered, never()).execute(anyString(), any());
	}

	private TelegramProperties properties(
		final boolean enabled,
		final List<String> chatIds,
		final String botToken
	) {
		return new TelegramProperties(
			null,
			new TelegramProperties.Bot(botToken),
			new TelegramProperties.Delivery(enabled, chatIds, Duration.ofHours(24), Duration.ofHours(24), 7, "ru"),
			null
		);
	}

	private DailyBrief emptyBrief(final String language) {
		return new DailyBrief(
			OffsetDateTime.parse("2026-03-09T10:00:00Z"),
			OffsetDateTime.parse("2026-03-08T10:00:00Z"),
			OffsetDateTime.parse("2026-03-09T10:00:00Z"),
			language,
			List.of()
		);
	}

	private UserProfile profile(
		final String chatId,
		final String language,
		final DigestFrequency frequency,
		final List<String> topics
	) {
		return new UserProfile(
			UUID.randomUUID(),
			chatId,
			777L,
			"tester",
			"Test",
			language,
			"UTC",
			frequency,
			true,
			OffsetDateTime.parse("2026-03-08T10:00:00Z"),
			OffsetDateTime.parse("2026-03-07T10:00:00Z"),
			OffsetDateTime.parse("2026-03-07T09:00:00Z"),
			OffsetDateTime.parse("2026-03-08T11:00:00Z"),
			topics
		);
	}
}
