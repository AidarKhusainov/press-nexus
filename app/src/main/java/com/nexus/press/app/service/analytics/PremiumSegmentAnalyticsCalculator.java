package com.nexus.press.app.service.analytics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.nexus.press.app.service.premium.PremiumSegmentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class PremiumSegmentAnalyticsCalculator {

	private final PremiumSegmentResolver premiumSegmentResolver;

	public List<PremiumIntentSegmentReport> build(
		final List<DeliveredUserTopics> deliveredUsers,
		final List<PremiumIntentAggregate> premiumIntentAggregates
	) {
		final Map<String, Integer> deliveredUsersBySegment = new LinkedHashMap<>();
		for (final DeliveredUserTopics deliveredUser : deliveredUsers) {
			final String segment = premiumSegmentResolver.resolve(deliveredUser.topics());
			deliveredUsersBySegment.merge(segment, 1, Integer::sum);
		}

		final Map<String, PremiumIntentAggregate> intentBySegment = new LinkedHashMap<>();
		for (final PremiumIntentAggregate aggregate : premiumIntentAggregates) {
			intentBySegment.put(normalizeSegment(aggregate.segment()), aggregate);
		}

		final List<String> orderedSegments = new ArrayList<>(deliveredUsersBySegment.keySet());
		for (final String segment : intentBySegment.keySet()) {
			if (!deliveredUsersBySegment.containsKey(segment)) {
				orderedSegments.add(segment);
			}
		}

		return orderedSegments.stream()
			.map(segment -> {
				final int deliveredUsersCount = deliveredUsersBySegment.getOrDefault(segment, 0);
				final PremiumIntentAggregate aggregate = intentBySegment.getOrDefault(segment, new PremiumIntentAggregate(segment, 0, 0));
				return new PremiumIntentSegmentReport(
					segment,
					deliveredUsersCount,
					aggregate.intentUsers(),
					aggregate.intentEvents(),
					percent(aggregate.intentUsers(), deliveredUsersCount)
				);
			})
			.sorted(Comparator.comparingInt(PremiumIntentSegmentReport::intentUsers).reversed()
				.thenComparing(Comparator.comparingInt(PremiumIntentSegmentReport::deliveredUsers).reversed())
				.thenComparing(PremiumIntentSegmentReport::segment))
			.toList();
	}

	private String normalizeSegment(final String segment) {
		return StringUtils.hasText(segment) ? segment.strip().toLowerCase() : "general";
	}

	private double percent(final int numerator, final int denominator) {
		if (denominator <= 0) {
			return 0.0;
		}
		return Math.round((numerator * 1000.0 / denominator)) / 10.0;
	}

	public record DeliveredUserTopics(List<String> topics) {
	}

	public record PremiumIntentAggregate(
		String segment,
		int intentEvents,
		int intentUsers
	) {
	}
}
