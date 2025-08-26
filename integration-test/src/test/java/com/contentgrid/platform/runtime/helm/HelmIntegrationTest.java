package com.contentgrid.platform.runtime.helm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.contentgrid.helm.Helm;
import com.contentgrid.helm.HelmInstallCommand.InstallOption;
import com.contentgrid.junit.jupiter.docker.registry.DockerRegistryCache;
import com.contentgrid.junit.jupiter.externalsecrets.ClusterSecretStore;
import com.contentgrid.junit.jupiter.externalsecrets.FakeSecretStore;
import com.contentgrid.junit.jupiter.helm.HelmChart;
import com.contentgrid.junit.jupiter.helm.HelmChartHandle;
import com.contentgrid.junit.jupiter.helm.HelmClient;
import com.contentgrid.junit.jupiter.k8s.KubernetesTestCluster;
import com.contentgrid.junit.jupiter.k8s.providers.K3sTestcontainersClusterProvider;
import com.contentgrid.junit.jupiter.k8s.wait.KubernetesResourceWaiter;
import com.contentgrid.junit.jupiter.k8s.wait.ResourceMatcher;
import com.contentgrid.platform.runtime.helm.HelmIntegrationTest.CustomClusterProvider;
import com.contentgrid.testcontainers.k3s.customizer.ClusterDomainsK3sContainerCustomizer;
import com.contentgrid.testcontainers.k3s.customizer.LoggingK3sContainerCustomizer;
import com.contentgrid.testcontainers.k3s.customizer.cilium.DefaultDenyCiliumK3sContainerCustomizer;
import com.contentgrid.testcontainers.k3s.customizer.ingress.TraefikIngressK3sContainerCustomizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Slf4j
@DockerRegistryCache(name = "docker.io", proxy = "https://registry-1.docker.io" )
@DockerRegistryCache(name = "quay.io", proxy = "https://quay.io" )
@DockerRegistryCache(name = "ghcr.io", proxy = "https://ghcr.io" )
@Testcontainers
@KubernetesTestCluster(providers = CustomClusterProvider.class)
@HelmClient
@FakeSecretStore
class HelmIntegrationTest {

    @Slf4j
    public static class CustomClusterProvider extends K3sTestcontainersClusterProvider {
        public CustomClusterProvider() {
            configure(DefaultDenyCiliumK3sContainerCustomizer.class);
            configure(ClusterDomainsK3sContainerCustomizer.class, customizer -> customizer.withDomains(
                    "auth.contentgrid.test",
                    "metrics.contentgrid.test",
                    "extensions.contentgrid.test"
            ));
            configure(LoggingK3sContainerCustomizer.class, customizer -> customizer.withLogger(log));
            configure(TraefikIngressK3sContainerCustomizer.class);
            customize(container -> {
                container.withStartupTimeout(Duration.ofMinutes(15));
            });
            customize(container -> {
                var args = new ArrayList<>(Arrays.asList(container.getCommandParts()));
                args.add("--kube-controller-manager-arg=concurrent-deployment-syncs=1");
                args.add("--kube-controller-manager-arg=concurrent-replicaset-syncs=1");
                container.setCommandParts(args.toArray(String[]::new));
            });
        }
    }

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

    @HelmChart(chart = "file:../contentgrid-rtp-helm")
    static HelmChartHandle rtpChart;

    static final String APP_NAMESPACE = "appnamespace";

    @BeforeAll
    static void beforeAll() {
        fakeClusterSecretStore.addSecrets(Map.of(
                "keycloak.db.password", pgKeycloak.getPassword(),
                "surveyor.pegman.systems.secret", "{\"hello\": \"world\"}" ));
        //create the app namespace
        var namespace = new NamespaceBuilder().withNewMetadata().withName(APP_NAMESPACE).endMetadata().build();
        kubernetesClient.namespaces().resource(namespace).serverSideApply();

        var installed = rtpChart.install(
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
                        "userapps.defaultDomainSuffix", "apps.contentgrid.test",
                        "userapps.ingressClassName", ""
                )));

        new KubernetesResourceWaiter(kubernetesClient)
                .include(installed)
                .exclude(Deployment.class, ResourceMatcher.named("openpolicyagent"))
                .await(wait -> wait.atMost(10, TimeUnit.MINUTES));


    }


    @Test
    void testDeployApplication() throws UnknownHostException, JsonProcessingException {
        var k8sAppClientConfig = kubernetesClient.getConfiguration();
        k8sAppClientConfig.setNamespace(APP_NAMESPACE);
        var appClient = new KubernetesClientBuilder().withConfig(k8sAppClientConfig).build();

        var dbSecret = new SecretBuilder()
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
        appClient.secrets().resource(dbSecret).create();

        //deploy src/test/resources/testapp/manifest.yaml
        var manifestInputStream = HelmIntegrationTest.class.getClassLoader()
                .getResourceAsStream("testapp/manifests.yaml" );
        appClient.load(manifestInputStream).serverSideApply();

        new KubernetesResourceWaiter(kubernetesClient)
                .include(Deployment.class, ResourceMatcher.named("api-d-7631ce24-4843-4661-814a-19fea8f0b470").inNamespace(APP_NAMESPACE))
                        .include(Deployment.class, ResourceMatcher.named("openpolicyagent"))
                .await(wait -> wait.atMost(1, TimeUnit.MINUTES));


        var gwSecret = new SecretBuilder()
                .withNewMetadata()
                .withGenerateName("gateway-iam-" )
                .withAnnotations(Map.of(
                        "gw.sp.captain.contentgrid.com/confidential-client-id", "db03b46f-2f58-402e-87c5-76849505bb7f"
                ))
                .withLabels(Map.of(
                        "app.contentgrid.com/app-id", "3e5ad186-1fe3-40d5-8891-404571597e30",
                        "app.contentgrid.com/application-id", "3e5ad186-1fe3-40d5-8891-404571597e30",
                        "app.contentgrid.com/service-type", "gateway",
                        "app.kubernetes.io/managed-by", "contentgrid",
                        "captain.contentgrid.com/resource-id", "b83bc515-65ff-45b9-84cd-05fa002104dd"
                ))
                .endMetadata()
                .withType("Opaque" )
                .addToStringData("contentgrid.idp.client-id",
                        "contentgrid-app-gateway-ccaa8db6-2514-4680-a2ad-01de8cab8922" )
                .addToStringData("contentgrid.idp.client-secret", "7fe30b6e-f104-4bf4-9510-e1165eb12865" )
                .addToStringData("contentgrid.idp.issuer-uri",
                        "http://auth.contentgrid.test/realms/cg-fff710df-7947-403a-8f45-a3fa97b9b4b2" )
                .build();

        appClient.secrets().resource(gwSecret).create();

        var dockerHostAddress = InetAddress.getByName(DockerClientFactory.instance().dockerHostIpAddress());

        var client = getRestClient(Map.of(
                "auth.contentgrid.test", new InetAddress[]{dockerHostAddress},
                "3e5ad186-1fe3-40d5-8891-404571597e30.apps.contentgrid.test", new InetAddress[]{dockerHostAddress}
        ));

        var response = client
                .post()
                .uri("http://auth.contentgrid.test/realms/cg-fff710df-7947-403a-8f45-a3fa97b9b4b2/protocol/openid-connect/token" )
                .body(new LinkedMultiValueMap(Map.of(
                        "grant_type", List.of("client_credentials"),
                        "client_id", List.of("rtp-integration-tester"),
                        "client_secret", List.of("rtp-integration-tester")
                )))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .retrieve()
                .toEntity(String.class);

        var objectMapper = new ObjectMapper();
        var jsonNode = objectMapper.readTree(response.getBody());
        var accessToken = jsonNode.get("access_token").asText();

        var suppliersResponse = client
                .get()
                .uri("http://3e5ad186-1fe3-40d5-8891-404571597e30.apps.contentgrid.test/suppliers" )
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .toEntity(String.class);

        // client has access to suppliers
        assertEquals(HttpStatus.OK, suppliersResponse.getStatusCode());

        // Expect an exception due to 403 response. The client has no access to invoices via the policies in the app
        HttpClientErrorException exception = assertThrows(HttpClientErrorException.Forbidden.class, () -> {
            client.get()
                    .uri("http://3e5ad186-1fe3-40d5-8891-404571597e30.apps.contentgrid.test/invoices")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .toEntity(String.class); // This line throws the exception
        });

        // Assert that the response status was 403
        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());

    }

    static RestClient getRestClient(Map<String, InetAddress[]> hosts) {
        var connectionManager = new BasicHttpClientConnectionManager(
                RegistryBuilder.<ConnectionSocketFactory>create()
                        .register("http", PlainConnectionSocketFactory.getSocketFactory())
                        .register("https", SSLConnectionSocketFactory.getSocketFactory())
                        .build(),
                null,
                null,
                new SystemDefaultDnsResolver() {
                    @Override
                    public InetAddress[] resolve(final String host) throws UnknownHostException {
                        var hostsEntry = hosts.get(host);
                        if (hostsEntry != null) {
                            return hostsEntry;
                        }

                        return super.resolve(host);
                    }
                });

        var httpClient = HttpClientBuilder.create()
                .setConnectionManager(connectionManager)
                .build();

        var restClientBuilder = RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient));

        return restClientBuilder.build();
    }

}
