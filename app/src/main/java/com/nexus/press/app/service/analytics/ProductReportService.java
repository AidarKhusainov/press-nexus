package com.nexus.press.app.service.analytics;

import reactor.core.publisher.Mono;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ProductReportService {

	private static final String FEEDBACK_STATS_SQL = """
		SELECT
			COUNT(*) AS total_events,
			COUNT(*) FILTER (WHERE event_type = 'useful') AS useful_count,
			COUNT(*) FILTER (WHERE event_type = 'noise') AS noise_count,
			COUNT(*) FILTER (WHERE event_type = 'anxious') AS anxious_count,
			COUNT(DISTINCT user_id) AS feedback_users
		FROM feedback_events
		WHERE occurred_at >= :fromTs
		  AND occurred_at < :toTs
		""";

	private static final String DELIVERY_USERS_SQL = """
		SELECT COUNT(*) AS delivered_users
		FROM users
		WHERE last_delivery_at >= :fromTs
		  AND last_delivery_at < :toTs
		""";

	private static final String PREMIUM_INTENT_SQL = """
		SELECT
			COUNT(*) AS total_events,
			COUNT(DISTINCT user_id) AS intent_users
		FROM premium_intent_events
		WHERE occurred_at >= :fromTs
		  AND occurred_at < :toTs
		""";

	private static final String RETENTION_SQL = """
		SELECT
			COUNT(*) AS cohort_size,
			COUNT(*) FILTER (
				WHERE EXISTS (
					SELECT 1
					FROM feedback_events f
					WHERE f.user_id = u.id
					  AND f.occurred_at >= :activityFrom
					  AND f.occurred_at < :activityTo
				)
			) AS retained_users
		FROM users u
		WHERE u.onboarded_at >= :cohortFrom
		  AND u.onboarded_at < :cohortTo
		""";

	private static final String DELIVERED_USERS_TOPICS_SQL = """
		SELECT COALESCE(string_agg(ut.topic_slug, ',' ORDER BY ut.topic_slug), '') AS topics_csv
		FROM users u
		LEFT JOIN user_topics ut ON ut.user_id = u.id
		WHERE u.last_delivery_at >= :fromTs
		  AND u.last_delivery_at < :toTs
		GROUP BY u.id
		""";

	private static final String PREMIUM_INTENT_BY_SEGMENT_SQL = """
		SELECT
			COALESCE(NULLIF(BTRIM(segment), ''), 'general') AS segment,
			COUNT(*) AS total_events,
			COUNT(DISTINCT user_id) AS intent_users
		FROM premium_intent_events
		WHERE occurred_at >= :fromTs
		  AND occurred_at < :toTs
		GROUP BY COALESCE(NULLIF(BTRIM(segment), ''), 'general')
		""";

	private final DatabaseClient db;
	private final ProductReportFormatter productReportFormatter;
	private final PremiumSegmentAnalyticsCalculator premiumSegmentAnalyticsCalculator;

	public Mono<ProductDailyReport> buildDailyReport(final LocalDate reportDate) {
		final LocalDate date = reportDate == null ? LocalDate.now(ZoneId.systemDefault()).minusDays(1) : reportDate;
		final OffsetDateTime from = date.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
		final OffsetDateTime to = from.plusDays(1);

		return Mono.zip(
				fetchFeedbackStats(from, to),
				fetchDeliveryUsers(from, to),
				fetchPremiumIntentStats(from, to),
				fetchRetentionStats(date.minusDays(1), 1),
				fetchRetentionStats(date.minusDays(7), 7),
				fetchDeliveredUserTopics(from, to),
				fetchPremiumIntentStatsBySegment(from, to)
			)
			.map(tuple -> {
				final FeedbackStats feedback = tuple.getT1();
				final int deliveredUsers = tuple.getT2();
				final PremiumIntentStats premium = tuple.getT3();
				final RetentionStats d1 = tuple.getT4();
				final RetentionStats d7 = tuple.getT5();
				final List<PremiumSegmentAnalyticsCalculator.DeliveredUserTopics> deliveredUserTopics = tuple.getT6();
				final List<PremiumSegmentAnalyticsCalculator.PremiumIntentAggregate> premiumIntentBySegment = tuple.getT7();

				final int qualityFeedbackBase = feedback.usefulCount() + feedback.noiseCount() + feedback.anxiousCount();
				final List<PremiumIntentSegmentReport> premiumIntentSegments = premiumSegmentAnalyticsCalculator.build(
					deliveredUserTopics,
					premiumIntentBySegment
				);
				return new ProductDailyReport(
					date,
					from,
					to,
					deliveredUsers,
					feedback.totalEvents(),
					feedback.feedbackUsers(),
					feedback.usefulCount(),
					feedback.noiseCount(),
					feedback.anxiousCount(),
					percent(feedback.usefulCount(), qualityFeedbackBase),
					percent(feedback.noiseCount(), qualityFeedbackBase),
					percent(feedback.feedbackUsers(), deliveredUsers),
					premium.totalEvents(),
					premium.intentUsers(),
					percent(premium.intentUsers(), deliveredUsers),
					d1.cohortSize(),
					d1.retainedUsers(),
					percent(d1.retainedUsers(), d1.cohortSize()),
					d7.cohortSize(),
					d7.retainedUsers(),
					percent(d7.retainedUsers(), d7.cohortSize()),
					premiumIntentSegments
				);
			});
	}

	public Mono<String> buildDailyReportText(final LocalDate reportDate) {
		return buildDailyReport(reportDate).map(productReportFormatter::toText);
	}

	private Mono<FeedbackStats> fetchFeedbackStats(final OffsetDateTime from, final OffsetDateTime to) {
		return db.sql(FEEDBACK_STATS_SQL)
			.bind("fromTs", from)
			.bind("toTs", to)
			.map((row, metadata) -> new FeedbackStats(
				numberAsInt(row.get("total_events", Number.class)),
				numberAsInt(row.get("useful_count", Number.class)),
				numberAsInt(row.get("noise_count", Number.class)),
				numberAsInt(row.get("anxious_count", Number.class)),
				numberAsInt(row.get("feedback_users", Number.class))
			))
			.one()
			.defaultIfEmpty(new FeedbackStats(0, 0, 0, 0, 0));
	}

	private Mono<Integer> fetchDeliveryUsers(final OffsetDateTime from, final OffsetDateTime to) {
		return db.sql(DELIVERY_USERS_SQL)
			.bind("fromTs", from)
			.bind("toTs", to)
			.map((row, metadata) -> numberAsInt(row.get("delivered_users", Number.class)))
			.one()
			.defaultIfEmpty(0);
	}

	private Mono<PremiumIntentStats> fetchPremiumIntentStats(final OffsetDateTime from, final OffsetDateTime to) {
		return db.sql(PREMIUM_INTENT_SQL)
			.bind("fromTs", from)
			.bind("toTs", to)
			.map((row, metadata) -> new PremiumIntentStats(
				numberAsInt(row.get("total_events", Number.class)),
				numberAsInt(row.get("intent_users", Number.class))
			))
			.one()
			.defaultIfEmpty(new PremiumIntentStats(0, 0));
	}

	private Mono<RetentionStats> fetchRetentionStats(final LocalDate cohortDate, final int activityDays) {
		final OffsetDateTime cohortFrom = cohortDate.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
		final OffsetDateTime cohortTo = cohortFrom.plusDays(1);
		final OffsetDateTime activityFrom = cohortTo;
		final OffsetDateTime activityTo = cohortTo.plusDays(activityDays);

		return db.sql(RETENTION_SQL)
			.bind("cohortFrom", cohortFrom)
			.bind("cohortTo", cohortTo)
			.bind("activityFrom", activityFrom)
			.bind("activityTo", activityTo)
			.map((row, metadata) -> new RetentionStats(
				numberAsInt(row.get("cohort_size", Number.class)),
				numberAsInt(row.get("retained_users", Number.class))
			))
			.one()
			.defaultIfEmpty(new RetentionStats(0, 0));
	}

	private Mono<List<PremiumSegmentAnalyticsCalculator.DeliveredUserTopics>> fetchDeliveredUserTopics(
		final OffsetDateTime from,
		final OffsetDateTime to
	) {
		return db.sql(DELIVERED_USERS_TOPICS_SQL)
			.bind("fromTs", from)
			.bind("toTs", to)
			.map((row, metadata) -> new PremiumSegmentAnalyticsCalculator.DeliveredUserTopics(
				parseTopics(row.get("topics_csv", String.class))
			))
			.all()
			.collectList();
	}

	private Mono<List<PremiumSegmentAnalyticsCalculator.PremiumIntentAggregate>> fetchPremiumIntentStatsBySegment(
		final OffsetDateTime from,
		final OffsetDateTime to
	) {
		return db.sql(PREMIUM_INTENT_BY_SEGMENT_SQL)
			.bind("fromTs", from)
			.bind("toTs", to)
			.map((row, metadata) -> new PremiumSegmentAnalyticsCalculator.PremiumIntentAggregate(
				row.get("segment", String.class),
				numberAsInt(row.get("total_events", Number.class)),
				numberAsInt(row.get("intent_users", Number.class))
			))
			.all()
			.collectList();
	}

	private int numberAsInt(final Number value) {
		return value == null ? 0 : value.intValue();
	}

	private List<String> parseTopics(final String topicsCsv) {
		if (!StringUtils.hasText(topicsCsv)) {
			return List.of();
		}
		return Arrays.stream(topicsCsv.split(","))
			.map(String::strip)
			.filter(StringUtils::hasText)
			.toList();
	}

	private double percent(final int numerator, final int denominator) {
		if (denominator <= 0) {
			return 0.0;
		}
		return Math.round((numerator * 1000.0 / denominator)) / 10.0;
	}

	private record FeedbackStats(
		int totalEvents,
		int usefulCount,
		int noiseCount,
		int anxiousCount,
		int feedbackUsers
	) {
	}

	private record PremiumIntentStats(
		int totalEvents,
		int intentUsers
	) {
	}

	private record RetentionStats(
		int cohortSize,
		int retainedUsers
	) {
	}
}
