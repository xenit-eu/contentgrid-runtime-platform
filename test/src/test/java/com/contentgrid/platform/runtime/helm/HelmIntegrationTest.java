package com.contentgrid.platform.runtime.helm;

import com.contentgrid.helm.Helm;
import com.contentgrid.helm.HelmInstallCommand.InstallOption;
import com.contentgrid.junit.jupiter.docker.registry.DockerRegistryCache;
import com.contentgrid.junit.jupiter.externalsecrets.ClusterSecretStore;
import com.contentgrid.junit.jupiter.externalsecrets.FakeSecretStore;
import com.contentgrid.junit.jupiter.helm.HelmClient;
import com.contentgrid.junit.jupiter.k8s.K8sTestUtils;
import com.contentgrid.junit.jupiter.k8s.KubernetesTestCluster;
import com.contentgrid.junit.jupiter.k8s.providers.K3sCiliumDefaultDenyCoreDNSClusterProvider;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;




@Slf4j
@DockerRegistryCache(name = "docker.io", proxy = "https://registry-1.docker.io")
@DockerRegistryCache(name = "quay.io", proxy = "https://quay.io")
@DockerRegistryCache(name = "ghcr.io", proxy = "https://ghcr.io")
@DockerRegistryCache(name = "docker.contentgrid.com", proxy = "https://docker.contentgrid.com")
@Testcontainers
@KubernetesTestCluster(providers = K3sCiliumDefaultDenyCoreDNSClusterProvider.class)
@HelmClient
@FakeSecretStore
class HelmIntegrationTest {

    static Helm helm;

    @Container
    static PostgreSQLContainer<?> pgKeycloak = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("keycloak")
            .withUsername("keycloak")
            .withPassword("keycloak");

    static KubernetesClient kubernetesClient;

    static ClusterSecretStore fakeClusterSecretStore;

    @BeforeAll
    static void beforeAll() {
        fakeClusterSecretStore.addSecrets(Map.of(
                "keycloak.db.password", pgKeycloak.getPassword(),
                "surveyor.pegman.systems.secret", "{\"hello\": \"world\"}"));

        var chart = Path.of("../contentgrid-rtp-helm").toAbsolutePath().normalize();

        log.info("Build chart dependencies {}", chart);
        helm.repository().add("rabbitmq", "https://charts.bitnami.com/bitnami");
        helm.repository().add("keycloakx", "https://codecentric.github.io/helm-charts");
        helm.dependency().build(chart);

        log.info("Install chart {}", chart);
        var releaseName = "test";

        helm.install().chart(releaseName, chart,
                InstallOption.values(Map.of(
                        "secretStoreName", fakeClusterSecretStore.getName(),
                        "keycloak.db.secretKey", "keycloak.db.password",
                        "surveyor.pegman.systems.secretKey", "surveyor.pegman.systems.secret",
                        "keycloakx.database.hostname", pgKeycloak.getHost(),
                        "keycloakx.database.port", pgKeycloak.getFirstMappedPort(),
                        "keycloak.host", "auth.contentgrid.test",
                        "surveyor.pegman.host", "metrics.contentgrid.test",
                        "tokenmonger.host", "extensions.contentgrid.test",
                        "ingressClassName", "",
                        "keycloak.protocol", "http")),
                InstallOption.values(Map.of(
                        "keycloakx.extraEnv",
                            """
                                - name: KEYCLOAK_ADMIN
                                  value: "admin"
                                - name: KEYCLOAK_ADMIN_PASSWORD
                                  value: "admin"
                                - name: JAVA_OPTS_APPEND
                                  value: >-
                                    -XX:+UseContainerSupport
                                    -XX:MaxRAMPercentage=50.0
                                    -Djava.awt.headless=true
                                    -Djgroups.dns.query={{ include "keycloak.fullname" . }}-headless
                                """
                )),
                InstallOption.arguments("--set-file", "keycloak.extraRealms.apprealm\\.json="+
                        Path.of("src/test/resources/keycloak/apprealm.json").toAbsolutePath().normalize()));

        K8sTestUtils.waitUntilDeploymentsReady(10 * 60,
                List.of("gateway", "liaison", "navigator", "pathfinder", "pathfinder-for-webapp",
                        "slingshot", "surveyor-cgapp-api-exporter", "surveyor-pegman",
                        "surveyor-postgres-exporter", "solon" ,"tokenmonger"), kubernetesClient);
    }


    @Test
    void testHelm() throws UnknownHostException {

    }

}
