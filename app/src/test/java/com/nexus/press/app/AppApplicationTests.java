package com.nexus.press.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.nexus.press.app.service.scheduler.ScheduledDailyBriefTask;
import com.nexus.press.app.service.scheduler.ScheduledNewsFetchTask;
import com.nexus.press.app.service.scheduler.ScheduledNewsPipelineTask;
import com.nexus.press.app.service.scheduler.ScheduledProductReportTask;

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
	private ScheduledDailyBriefTask scheduledDailyBriefTask;

	@MockBean
	private ScheduledProductReportTask scheduledProductReportTask;

	@Test
	void contextLoads() {
	}

}
