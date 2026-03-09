package com.nexus.press.app.service.premium;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PremiumSegmentResolver {

	public String resolve(final List<String> topics) {
		if (topics == null || topics.isEmpty()) {
			return "general";
		}
		final Set<String> topicSet = Set.copyOf(topics);
		int economy = 0;
		int tech = 0;
		int news = 0;
		int lifestyle = 0;

		for (final String topic : topicSet) {
			if ("economy".equals(topic) || "business".equals(topic)) {
				economy++;
				continue;
			}
			if ("technology".equals(topic) || "science".equals(topic)) {
				tech++;
				continue;
			}
			if ("world".equals(topic) || "russia".equals(topic) || "politics".equals(topic) || "society".equals(topic)) {
				news++;
				continue;
			}
			if ("sports".equals(topic) || "culture".equals(topic)) {
				lifestyle++;
			}
		}

		int nonZeroBuckets = 0;
		nonZeroBuckets += economy > 0 ? 1 : 0;
		nonZeroBuckets += tech > 0 ? 1 : 0;
		nonZeroBuckets += news > 0 ? 1 : 0;
		nonZeroBuckets += lifestyle > 0 ? 1 : 0;

		if (nonZeroBuckets == 0) {
			return "general";
		}
		if (nonZeroBuckets > 1) {
			return "mixed";
		}
		if (economy > 0) {
			return "economy";
		}
		if (tech > 0) {
			return "tech";
		}
		if (news > 0) {
			return "news";
		}
		return "lifestyle";
	}
}
