package com.awardwatch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
	"seats-aero.api-key=test-key-not-a-secret",
	"spring.autoconfigure.exclude="
		+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
		+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
		+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
class AwardWatchApplicationTests {

	@Test
	void contextLoads() {
	}

}
