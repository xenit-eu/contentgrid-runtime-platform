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
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
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
@DockerRegistryCache(name = "docker.io", proxy = "https://registry-1.docker.io" )
@DockerRegistryCache(name = "quay.io", proxy = "https://quay.io" )
@DockerRegistryCache(name = "ghcr.io", proxy = "https://ghcr.io" )
@DockerRegistryCache(name = "docker.contentgrid.com", proxy = "https://docker.contentgrid.com" )
@Testcontainers
@KubernetesTestCluster(providers = K3sCiliumDefaultDenyCoreDNSClusterProvider.class)
@HelmClient
@FakeSecretStore
class HelmIntegrationTest {

    static Helm helm;

    @Container
    static PostgreSQLContainer<?> pgKeycloak = new PostgreSQLContainer<>("postgres:15" )
            .withDatabaseName("keycloak" )
            .withUsername("keycloak" )
            .withPassword("keycloak" );

    @Container
    static PostgreSQLContainer<?> appDatabase = new PostgreSQLContainer<>("postgres:15" )
            .withDatabaseName("appdb" )
            .withUsername("appuser" )
            .withPassword("apppassword" );

    static KubernetesClient kubernetesClient;

    static ClusterSecretStore fakeClusterSecretStore;

    static final String APP_NAMESPACE = "appnamespace";

    @BeforeAll
    static void beforeAll() {
        fakeClusterSecretStore.addSecrets(Map.of(
                "keycloak.db.password", pgKeycloak.getPassword(),
                "surveyor.pegman.systems.secret", "{\"hello\": \"world\"}" ));

        var chart = Path.of("../contentgrid-rtp-helm" ).toAbsolutePath().normalize();

        log.info("Build chart dependencies {}", chart);
        helm.repository().add("rabbitmq", "https://charts.bitnami.com/bitnami" );
        helm.repository().add("keycloakx", "https://codecentric.github.io/helm-charts" );
        helm.dependency().build(chart);

        log.info("Install chart {}", chart);
        var releaseName = "test";


        //create the app namespace
        var namespace = new NamespaceBuilder().withNewMetadata().withName(APP_NAMESPACE).endMetadata().build();
        kubernetesClient.namespaces().resource(namespace).serverSideApply();

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
                        "keycloak.protocol", "http" )),
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
                InstallOption.arguments("--set-file", "keycloak.extraRealms.apprealm\\.json=" +
                        Path.of("src/test/resources/keycloak/apprealm.json" ).toAbsolutePath().normalize()),
                InstallOption.values(Map.of(
                        "userapps.database[0].ip", appDatabase.getHost(),
                        "userapps.database[0].port", appDatabase.getFirstMappedPort().toString(),
                        "userapps.namespace", APP_NAMESPACE,
                        "userapps.defaultDomainSuffix", "apps.contentgrid.test"
                )));

        K8sTestUtils.waitUntilDeploymentsReady(10 * 60,
                List.of("gateway", "liaison", "navigator", "pathfinder", "pathfinder-for-webapp",
                        "slingshot", "surveyor-cgapp-api-exporter", "surveyor-pegman",
                        "surveyor-postgres-exporter", "solon", "tokenmonger" ), kubernetesClient);
    }


    @Test
    void testDeployApplication() {
        var k8sAppClientConfig = kubernetesClient.getConfiguration();
        k8sAppClientConfig.setNamespace(APP_NAMESPACE);
        var appClient = new KubernetesClientBuilder().withConfig(k8sAppClientConfig).build();

        var secret = new SecretBuilder()
                .withNewMetadata()
                .withName("3e5ad186-1fe3-40d5-8891-404571597e30-db" )
                .withAnnotations(Map.of(
                        "api.sp.captain.contentgrid.com/db-access-credentials-id",
                        "cg-c2ebcae8-a3b2-4f2d-8109-e89aaa920756-293162d7-ee7b-406b-b3d4"
                ))
                .withLabels(Map.of(
                        "app.contentgrid.com/app-id", "3e5ad186-1fe3-40d5-8891-404571597e30",
                        "app.contentgrid.com/application-id", "3e5ad186-1fe3-40d5-8891-404571597e30",
                        "app.contentgrid.com/service-type", "api",
                        "app.kubernetes.io/managed-by", "contentgrid",
                        "captain.contentgrid.com/resource-id", "1064246e-db41-4023-8368-776c03285962"
                ))
                .endMetadata()
                .withType("Opaque" )
                .addToStringData("spring.datasource.password", appDatabase.getPassword())
                .addToStringData("spring.datasource.url", appDatabase.getJdbcUrl())
                .addToStringData("spring.datasource.username", appDatabase.getUsername())
                .build();
        //deploy the secret
        appClient.secrets().resource(secret).create();

        //deploy src/test/resources/testapp/manifest.yaml
        var manifestInputStream = HelmIntegrationTest.class.getClassLoader().getResourceAsStream("testapp/manifests.yaml" );
        appClient.load(manifestInputStream).serverSideApply();

        K8sTestUtils.waitUntilDeploymentsReady(10 * 60,
                List.of("api-d-7631ce24-4843-4661-814a-19fea8f0b470" ), appClient);
    }

}
