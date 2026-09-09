package com.awardwatch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "seats-aero.api-key=test-key-not-a-secret")
class AwardWatchApplicationTests {

	@Test
	void contextLoads() {
	}

}
