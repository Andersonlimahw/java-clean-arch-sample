package com.muse.payments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.context.WebServerApplicationContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(value = 4, unit = TimeUnit.MINUTES)
class MigrationJobIT extends SharedContainers {

    @Test
    @DisplayName("Migration mode applies Flyway and the process exits 0 even if Flyway was disabled externally")
    void migrationProcessExitsZeroAfterFlyway() throws Exception {
        List<String> args = new ArrayList<>();
        args.add("--app.migration-only=true");
        args.add("--spring.flyway.enabled=false");
        args.addAll(infrastructureArgs(postgres.getJdbcUrl()));

        ProcessResult result = runApplication(args);

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("Flyway migrations applied");
        assertThat(appliedMigrations(postgres.getJdbcUrl())).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("Migration mode exits non-zero when Flyway fails and the process does not stay up")
    void migrationProcessExitsNonZeroWhenFlywayFails() throws Exception {
        String jdbcUrl = databaseUrl("migration_fail");
        createDatabase("migration_fail");

        List<String> args = new ArrayList<>();
        args.add("--app.migration-only=true");
        args.add("--spring.flyway.locations=classpath:db/migration,classpath:db/migration-broken");
        args.add("--spring.flyway.fail-on-missing-locations=true");
        args.addAll(infrastructureArgs(jdbcUrl));

        ProcessResult result = runApplication(args);

        assertThat(result.exitCode()).as(result.output()).isNotZero();
        assertThat(result.output()).contains("Migration failed");
    }

    @Test
    @DisplayName("Normal startup still serves HTTP and does not exit")
    void normalApplicationStartsWebServer() throws Exception {
        List<String> args = new ArrayList<>();
        args.add("--server.port=0");
        args.addAll(infrastructureArgs(postgres.getJdbcUrl()));

        ApplicationLaunch launch = PaymentsApplication.launch(args.toArray(String[]::new));
        try {
            assertThat(launch.migrationOnly()).isFalse();
            assertThat(launch.context()).isNotNull();
            assertThat(launch.context().isRunning()).isTrue();
            assertThat(launch.webApplicationType()).isEqualTo(WebApplicationType.SERVLET);
            assertThat(launch.context()).isInstanceOf(WebServerApplicationContext.class);

            String port = launch.context().getEnvironment().getProperty("local.server.port");
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(response.statusCode()).isEqualTo(200);
        } finally {
            if (launch.context() != null) {
                launch.context().close();
            }
        }
    }

    private static List<String> infrastructureArgs(String jdbcUrl) {
        return List.of(
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.data.redis.host=" + redis.getHost(),
                "--spring.data.redis.port=" + redis.getMappedPort(6379),
                "--spring.rabbitmq.host=" + rabbit.getHost(),
                "--spring.rabbitmq.port=" + rabbit.getAmqpPort(),
                "--spring.rabbitmq.username=" + rabbit.getAdminUsername(),
                "--spring.rabbitmq.password=" + rabbit.getAdminPassword()
        );
    }

    private static void createDatabase(String name) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            try (ResultSet existing = statement.executeQuery(
                    "select 1 from pg_database where datname = '" + name + "'")) {
                if (existing.next()) {
                    return;
                }
            }
            statement.executeUpdate("create database " + name);
        }
    }

    private static String databaseUrl(String name) {
        String jdbcUrl = postgres.getJdbcUrl();
        return jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/')) + "/" + name;
    }

    private static int appliedMigrations(String jdbcUrl) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "select count(*) from flyway_schema_history where success")) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static ProcessResult runApplication(List<String> appArgs) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(PaymentsApplication.class.getName());
        command.addAll(appArgs);

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.startVirtualThread(() -> {
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (Exception ex) {
                output.append("failed to read process output: ").append(ex).append('\n');
            }
        });

        if (!process.waitFor(3, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            reader.join(5_000);
            throw new AssertionError("application process did not exit\n" + output);
        }
        reader.join(5_000);
        return new ProcessResult(process.exitValue(), output.toString());
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
