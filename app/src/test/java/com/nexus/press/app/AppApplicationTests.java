package com.nexus.press.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
	"spring.liquibase.enabled=false",
	"spring.main.lazy-initialization=true"
})
class AppApplicationTests {

	@Test
	void contextLoads() {
	}

}
