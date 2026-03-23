package com.nexus.press.app.news.support;

import java.util.concurrent.atomic.AtomicLong;
import com.nexus.press.app.news.model.PipelineRuntimeCounters;
import org.springframework.stereotype.Component;

@Component
public class PipelineRuntimeStats {

	private final AtomicLong discoveredNews = new AtomicLong();
	private final AtomicLong populatedNews = new AtomicLong();
	private final AtomicLong embeddedNews = new AtomicLong();

	public void recordDiscoveredNews() {
		discoveredNews.incrementAndGet();
	}

	public void recordPopulatedNews() {
		populatedNews.incrementAndGet();
	}

	public void recordEmbeddedNews() {
		embeddedNews.incrementAndGet();
	}

	public PipelineRuntimeCounters snapshot() {
		return new PipelineRuntimeCounters(
			discoveredNews.get(),
			populatedNews.get(),
			embeddedNews.get()
		);
	}

	public void reset() {
		discoveredNews.set(0);
		populatedNews.set(0);
		embeddedNews.set(0);
	}

	public PipelineRuntimeCounters snapshotAndReset() {
		return new PipelineRuntimeCounters(
			discoveredNews.getAndSet(0),
			populatedNews.getAndSet(0),
			embeddedNews.getAndSet(0)
		);
	}
}
