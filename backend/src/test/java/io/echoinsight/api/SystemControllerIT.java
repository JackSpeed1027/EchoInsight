package io.echoinsight.api;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

/** Uses the isolated local MySQL instance. Does not clear or replace data. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SystemControllerIT {
  @Autowired TestRestTemplate http;

  @Test
  void reportsHealthyDatabase() {
    var response = http.getForEntity("/actuator/health", String.class);
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody()).contains("UP");
  }

  @Test
  void readsMigratedDemoCatalogFromMysql() {
    var response = http.getForEntity("/api/v1/demos", String.class);
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody()).contains("threadpool", "sql-index", "线程池", "synthetic");
  }
}
