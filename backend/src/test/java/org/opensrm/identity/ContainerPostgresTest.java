package org.opensrm.identity;

import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ContainerPostgresTest extends SecurityHttpAcceptance {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("open-srm-database:0.8.0-local").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("open_srm").withUsername("opensrm_test").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        // PER_CLASS tests initialize the Spring context before JUnit's beforeAll callbacks.
        // Start here so resolving datasource suppliers cannot precede the mapped port.
        postgres.start();
        properties.add("spring.datasource.url",postgres::getJdbcUrl);
        properties.add("spring.datasource.username",postgres::getUsername);
        properties.add("spring.datasource.password",postgres::getPassword);
    }
}

