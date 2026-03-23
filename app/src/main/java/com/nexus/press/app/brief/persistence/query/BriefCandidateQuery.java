package com.nexus.press.app.brief.persistence.query;

import reactor.core.publisher.Flux;
import java.time.OffsetDateTime;
import com.nexus.press.app.brief.model.BriefCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BriefCandidateQuery {

	private static final String RECENT_SUMMARIZED_NEWS_SQL = """
		SELECT n.id,
		       n.title,
		       n.url,
		       n.media,
		       COALESCE(n.published_at, n.fetched_at) AS event_at,
		       ns.summary
		FROM news n
		JOIN LATERAL (
		    SELECT s.summary
		    FROM news_summary s
		    WHERE s.news_id = n.id
		      AND (s.lang = :lang OR s.lang = 'ru' OR s.lang = 'en')
		    ORDER BY (s.lang = :lang) DESC, s.created_at DESC
		    LIMIT 1
		) ns ON TRUE
		WHERE n.status_summary = 'DONE'
		  AND COALESCE(n.published_at, n.fetched_at) >= :fromTs
		ORDER BY COALESCE(n.published_at, n.fetched_at) DESC
		LIMIT :lim
		""";

	private final DatabaseClient db;

	public Flux<BriefCandidate> fetch(final OffsetDateTime from, final int limit, final String language) {
		return db.sql(RECENT_SUMMARIZED_NEWS_SQL)
			.bind("lang", language)
			.bind("fromTs", from)
			.bind("lim", limit)
			.map((row, md) -> new BriefCandidate(
				row.get("id", String.class),
				row.get("title", String.class),
				row.get("url", String.class),
				row.get("media", String.class),
				row.get("event_at", OffsetDateTime.class),
				row.get("summary", String.class)
			))
			.all();
	}
}
