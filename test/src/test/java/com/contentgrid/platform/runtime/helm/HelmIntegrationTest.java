package com.contentgrid.platform.runtime.helm;

import com.contentgrid.helm.Helm;
import com.contentgrid.helm.HelmInstallCommand.InstallOption;
import com.contentgrid.junit.jupiter.externalsecrets.FakeSecretStore;
import com.contentgrid.junit.jupiter.helm.HelmClient;
import com.contentgrid.junit.jupiter.k8s.K8sTestUtils;
import com.contentgrid.junit.jupiter.k8s.KubernetesTestCluster;
import com.contentgrid.junit.jupiter.k8s.providers.K3sCiliumDefaultDenyCoreDNSClusterProvider;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Slf4j
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

    static KubernetesClient client;

    @BeforeAll
    static void beforeAll() {
        var chart = Path.of("../contentgrid-rtp-helm").toAbsolutePath().normalize();

        log.info("Build chart dependencies {}", chart);
        helm.repository().add("rabbitmq", "https://charts.bitnami.com/bitnami");
        helm.repository().add("keycloakx", "https://codecentric.github.io/helm-charts");
        helm.dependency().build(chart);

        log.info("Install chart {}", chart);
        var releaseName = "test";

        var result = helm.install().chart(releaseName, chart,
                InstallOption.values(Map.of(
                        "keycloakx.database.hostname", pgKeycloak.getHost(),
                        "keycloakx.database.port", pgKeycloak.getFirstMappedPort(),
                        "keycloak.host", "auth.contentgrid.test",
                        "surveyor.pegman.host", "metrics.contentgrid.test",
                        "tokenmonger.host", "extensions.contentgrid.test"
                )));

        K8sTestUtils.waitUntilDeploymentsReady(10*60, List.of("gateway"), client);
    }


    @Test
    void testHelm() {

    }
}
