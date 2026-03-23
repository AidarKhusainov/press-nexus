package com.nexus.press.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.nexus.press.app.analytics.job.ScheduledProductReportTask;
import com.nexus.press.app.news.job.ScheduledNewsFetchTask;
import com.nexus.press.app.news.job.ScheduledNewsPipelineTask;
import com.nexus.press.app.telegram.job.ScheduledTelegramDailyBriefTask;

@SpringBootTest(properties = {
	"spring.liquibase.enabled=false",
	"spring.main.lazy-initialization=true",
	"management.tracing.enabled=false",
	"management.prometheus.metrics.export.enabled=false",
	"management.metrics.binders.logback.enabled=false"
})
class AppApplicationTests {

	@MockBean
	private ScheduledNewsFetchTask scheduledNewsFetchTask;

	@MockBean
	private ScheduledNewsPipelineTask scheduledNewsPipelineTask;

	@MockBean
	private ScheduledTelegramDailyBriefTask scheduledTelegramDailyBriefTask;

	@MockBean
	private ScheduledProductReportTask scheduledProductReportTask;

	@Test
	void contextLoads() {
	}

}
