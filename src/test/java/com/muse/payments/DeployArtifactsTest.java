package com.muse.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DeployArtifactsTest {

    private static String text(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    @DisplayName("runbook env names match properties the process actually binds")
    void deployDocUsesBoundVariables() throws Exception {
        String deploy = text("docs/deploy.md");
        String app = text("src/main/resources/application.yml");

        assertThat(app).contains("migration-only: ${APP_MIGRATION_ONLY:false}");
        assertThat(deploy).contains("MANAGEMENT_OTLP_TRACING_ENDPOINT");
        assertThat(deploy).doesNotContain("OTEL_EXPORTER_OTLP_ENDPOINT");
        assertThat(deploy).doesNotContain("ECS JSON");
        assertThat(deploy).doesNotContain("scale the consumer only");
        assertThat(deploy).contains("DATABASE_URL");
        assertThat(deploy).contains("scripts/k8s-apply.sh");
    }

    @Test
    @DisplayName("kubernetes notes match the manifests")
    void kubernetesReadmeMatchesManifests() throws Exception {
        String readme = text("deploy/k8s/README.md");
        String staging = text("deploy/k8s/overlays/staging/kustomization.yaml");
        String secret = text("deploy/k8s/base/secret.example.yaml");

        assertThat(readme).contains("scripts/k8s-apply.sh");
        assertThat(readme).contains("port 80");
        assertThat(readme).contains("targetPort 8080");
        assertThat(readme).doesNotContain("Restricts ingress to the ingress controller");
        assertThat(readme).contains("kubectl rollout undo deployment/payments-deployment -n payments-staging");
        assertThat(readme).doesNotContain("kubectl create secret");

        int minReplicas = staging.indexOf("path: /spec/minReplicas");
        assertThat(minReplicas).isGreaterThan(-1);
        assertThat(staging.substring(minReplicas, minReplicas + 60)).contains("value: 1");

        assertThat(secret).contains("JWT_PRIVATE_KEY: \"CHANGE_ME\"");
        assertThat(secret).contains("JWT_PUBLIC_KEY: \"CHANGE_ME\"");
        assertThat(secret).doesNotContain("BEGIN PRIVATE KEY");
    }

    @Test
    @DisplayName("smoke script bounds curl and checks its tools")
    void smokeScriptChecksDependenciesAndTimeouts() throws Exception {
        String script = text("scripts/validate-api.sh");
        assertThat(script).contains("command -v");
        assertThat(script).contains("--connect-timeout");
        assertThat(script).contains("--max-time");
        assertThat(script).contains("READY_TIMEOUT_SECONDS");
        assertThat(script).contains("NOTIFY_TIMEOUT_SECONDS");
    }

    @Test
    @DisplayName("Postman collection asserts a status for every request and rotates the email")
    void postmanCollectionAssertsStatuses() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Path.of("src/test/postman/payments-v1.postman_collection.json").toFile());
        assertThat(root.toString()).doesNotContain("postman-user@example.com");
        JsonNode items = root.get("item");
        assertThat(items).isNotEmpty();
        boolean rotatesEmail = false;
        for (JsonNode item : items) {
            String name = item.get("name").asText();
            boolean hasStatus = false;
            for (JsonNode event : item.path("event")) {
                String exec = event.path("script").path("exec").toString();
                if ("test".equals(event.path("listen").asText()) && exec.contains("pm.response.to.have.status")) {
                    hasStatus = true;
                }
                if ("prerequest".equals(event.path("listen").asText()) && exec.contains("Date.now()")) {
                    rotatesEmail = true;
                }
            }
            assertThat(hasStatus).as("status assertion for %s", name).isTrue();
        }
        assertThat(rotatesEmail).isTrue();
    }
}
