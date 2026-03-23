package com.nexus.press.app.analytics.persistence.query;

import reactor.core.publisher.Mono;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import com.nexus.press.app.analytics.model.DeliveredUserTopics;
import com.nexus.press.app.analytics.model.PremiumIntentAggregate;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class ProductReportQuery {

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

	public Mono<ReportSnapshot> fetch(final LocalDate reportDate) {
		final OffsetDateTime from = reportDate.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
		final OffsetDateTime to = from.plusDays(1);

		return Mono.zip(
				fetchFeedbackStats(from, to),
				fetchDeliveryUsers(from, to),
				fetchPremiumIntentStats(from, to),
				fetchRetentionStats(reportDate.minusDays(1), 1),
				fetchRetentionStats(reportDate.minusDays(7), 7),
				fetchDeliveredUserTopics(from, to),
				fetchPremiumIntentStatsBySegment(from, to)
			)
			.map(tuple -> new ReportSnapshot(
				reportDate,
				from,
				to,
				tuple.getT1(),
				tuple.getT2(),
				tuple.getT3(),
				tuple.getT4(),
				tuple.getT5(),
				tuple.getT6(),
				tuple.getT7()
			));
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

	private Mono<List<DeliveredUserTopics>> fetchDeliveredUserTopics(final OffsetDateTime from, final OffsetDateTime to) {
		return db.sql(DELIVERED_USERS_TOPICS_SQL)
			.bind("fromTs", from)
			.bind("toTs", to)
			.map((row, metadata) -> new DeliveredUserTopics(parseTopics(row.get("topics_csv", String.class))))
			.all()
			.collectList();
	}

	private Mono<List<PremiumIntentAggregate>> fetchPremiumIntentStatsBySegment(
		final OffsetDateTime from,
		final OffsetDateTime to
	) {
		return db.sql(PREMIUM_INTENT_BY_SEGMENT_SQL)
			.bind("fromTs", from)
			.bind("toTs", to)
			.map((row, metadata) -> new PremiumIntentAggregate(
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

	public record ReportSnapshot(
		LocalDate reportDate,
		OffsetDateTime from,
		OffsetDateTime to,
		FeedbackStats feedbackStats,
		int deliveredUsers,
		PremiumIntentStats premiumIntentStats,
		RetentionStats d1RetentionStats,
		RetentionStats d7RetentionStats,
		List<DeliveredUserTopics> deliveredUserTopics,
		List<PremiumIntentAggregate> premiumIntentBySegment
	) {
	}

	public record FeedbackStats(
		int totalEvents,
		int usefulCount,
		int noiseCount,
		int anxiousCount,
		int feedbackUsers
	) {
	}

	public record PremiumIntentStats(
		int totalEvents,
		int intentUsers
	) {
	}

	public record RetentionStats(
		int cohortSize,
		int retainedUsers
	) {
	}
}
