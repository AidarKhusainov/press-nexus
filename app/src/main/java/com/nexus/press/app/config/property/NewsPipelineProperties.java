package com.nexus.press.app.config.property;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "press.news.pipeline")
public class NewsPipelineProperties {

	private Duration discoveryInterval = Duration.ofMinutes(10);
	private Duration workerInterval = Duration.ofSeconds(30);
	private Duration claimTimeout = Duration.ofMinutes(30);
	private boolean summaryEnabled = true;
	private boolean debugReportEnabled = false;
	private Duration debugReportInterval = Duration.ofMinutes(30);
	private Duration debugReportLookback = Duration.ofHours(24);
	private int debugReportTopClusters = 10;
	private int debugReportMinClusterSize = 5;
	private int debugReportMaxClusterSize = 10;
	private int discoveryPersistConcurrency = 4;
	private int fetchSourceConcurrency = 4;
	private int contentBatchSize = 24;
	private int embeddingBatchSize = 8;
	private int summaryBatchSize = 8;
	private Duration summaryMaturity = Duration.ofMinutes(15);
	private int populateConcurrency = 6;
	private int embeddingConcurrency = 2;
	private int summaryConcurrency = 2;
}
