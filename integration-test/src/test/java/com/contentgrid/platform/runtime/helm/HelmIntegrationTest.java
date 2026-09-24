package com.contentgrid.platform.runtime.helm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.contentgrid.hateoas.client.hal.HalDocument;
import com.contentgrid.hateoas.client.hal.HalLink;
import com.contentgrid.hateoas.client.hal.forms.HalFormsClient;
import com.contentgrid.helm.HelmInstallCommand.InstallOption;
import com.contentgrid.junit.jupiter.docker.registry.DockerRegistryCache;
import com.contentgrid.junit.jupiter.externalsecrets.ClusterSecretStore;
import com.contentgrid.junit.jupiter.externalsecrets.FakeSecretStore;
import com.contentgrid.junit.jupiter.helm.HelmChart;
import com.contentgrid.junit.jupiter.helm.HelmChartHandle;
import com.contentgrid.junit.jupiter.helm.HelmClient;
import com.contentgrid.junit.jupiter.k8s.KubernetesTestCluster;
import com.contentgrid.junit.jupiter.k8s.log.KubernetesResourceLogger;
import com.contentgrid.junit.jupiter.k8s.providers.K3sTestcontainersClusterProvider;
import com.contentgrid.junit.jupiter.k8s.wait.KubernetesResourceWaiter;
import com.contentgrid.junit.jupiter.k8s.resource.ResourceMatcher;
import com.contentgrid.junit.jupiter.k8s.resource.AwaitableResource;
import com.contentgrid.platform.runtime.helm.HelmIntegrationTest.CustomClusterProvider;
import com.contentgrid.testcontainers.k3s.customizer.ClusterDomainsK3sContainerCustomizer;
import com.contentgrid.testcontainers.k3s.customizer.FakeCpuK3sContainerCustomizer;
import com.contentgrid.testcontainers.k3s.customizer.LoggingK3sContainerCustomizer;
import com.contentgrid.testcontainers.k3s.customizer.MemoryLimitK3sContainerCustomizer;
import com.contentgrid.testcontainers.k3s.customizer.MemoryLimitK3sContainerCustomizer.SizeUnit;
import com.contentgrid.testcontainers.k3s.customizer.cilium.DefaultDenyCiliumK3sContainerCustomizer;
import com.contentgrid.testcontainers.k3s.customizer.ingress.TraefikIngressK3sContainerCustomizer;
import tools.jackson.databind.json.JsonMapper;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.IPBlockBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeerBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPortBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.minio.MakeBucketArgs;
import io.minio.MinioAsyncClient;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.assertj.core.api.Condition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Slf4j
@DockerRegistryCache(name = "docker.io", proxy = "https://registry-1.docker.io")
@DockerRegistryCache(name = "quay.io", proxy = "https://quay.io")
@DockerRegistryCache(name = "ghcr.io", proxy = "https://ghcr.io")
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
                    "extensions.contentgrid.test",
                    "webhook-receiver.contentgrid.test"
            ));
            configure(LoggingK3sContainerCustomizer.class, customizer -> customizer.withLogger(log));
            configure(TraefikIngressK3sContainerCustomizer.class);
            // Spoof /Pin the resources k3s believes it has, so scheduling behaves the same on every host.
            configure(MemoryLimitK3sContainerCustomizer.class, mem -> mem.withAvailableMemory(12, SizeUnit.GiB));
            configure(FakeCpuK3sContainerCustomizer.class, cpu -> cpu.withCpuCount(8));
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

    @Container
    static PostgreSQLContainer pgKeycloak = new PostgreSQLContainer("postgres:15")
            .withDatabaseName("keycloak")
            .withUsername("keycloak")
            .withPassword("keycloak");

    @Container
    PostgreSQLContainer appDatabase = new PostgreSQLContainer("postgres:15")
            .withDatabaseName("appdb")
            .withUsername("appuser")
            .withPassword("apppassword");

    @Container
    LocalStackContainer appObjectStorage = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.11.1"))
            .withServices("s3")
            .withEnv("ALLOW_NONSTANDARD_REGIONS", "1");

    static KubernetesClient kubernetesClient;

    static ClusterSecretStore fakeClusterSecretStore;

    @HelmChart(chart = "file:../contentgrid-rtp-helm")
    static HelmChartHandle rtpChart;

    static KubernetesResourceLogger kubernetesLogger;

    static final String APP_NAMESPACE = "appnamespace";
    public static final String APP_BUCKET = "app-bucket";

    @BeforeAll
    static void beforeAll()
            throws Exception {
        fakeClusterSecretStore.addSecrets(Map.of(
                "keycloak.db.password", pgKeycloak.getPassword()
        ));
        //create the app namespace
        var namespace = new NamespaceBuilder().withNewMetadata().withName(APP_NAMESPACE).endMetadata().build();
        kubernetesClient.namespaces().resource(namespace).serverSideApply();

        var installed = rtpChart.install(
                InstallOption.values(Map.of(
                        "secretStoreName", fakeClusterSecretStore.getName(),
                        "keycloak.db.secretKey", "keycloak.db.password",
                        "keycloakx.database.hostname", pgKeycloak.getHost(),
                        "keycloakx.database.port", pgKeycloak.getFirstMappedPort(),
                        "keycloak.host", "auth.contentgrid.test",
                        "surveyor.enabled", false,
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
                InstallOption.arguments("--set-file", "keycloak.extraRealms.apprealm\\.json=" +
                        Path.of("src/test/resources/keycloak/apprealm.json").toAbsolutePath().normalize()),
                InstallOption.values(Map.of(
                        "userapps.namespace", APP_NAMESPACE,
                        "userapps.defaultDomainSuffix", "apps.contentgrid.test",
                        "userapps.ingressClassName", ""
                )));


        // Apply webhook-receiver manifest
        var webhookReceiverManifestInputStream = HelmIntegrationTest.class.getClassLoader()
                .getResourceAsStream("testapp/webhook-receiver.yaml");
        var output = kubernetesClient.load(webhookReceiverManifestInputStream)
                .serverSideApply();

        new KubernetesResourceWaiter(kubernetesClient)
                .include(installed)
                .await(wait -> wait.atMost(12, TimeUnit.MINUTES));
    }

    @ParameterizedTest
    @CsvSource({"v1, false", "v2, false", "v2, true"})
    void testDeployApplication(String dockerImageTag, boolean opaSideCar) throws IOException {

        // The test application is maintained here: https://github.com/xenit-eu/contentgrid-rtp-test-app
        var applicationId = deployApplication("ghcr.io/xenit-eu/contentgrid-rtp-test-app:" + dockerImageTag, opaSideCar);
        var appUri = URI.create("http://" + applicationId + ".apps.contentgrid.test");

        var suppliersAdminClient = getHalFormsClient(applicationId, "rtp-integration-tester", "rtp-integration-tester");

        var root = suppliersAdminClient.follow(HalLink.from(appUri));
        // client has access to suppliers
        assertThat(followEntityLink(suppliersAdminClient, root, "supplier", "suppliers")).isNotNull();

        // The client has no access to invoices via the policies in the app
        assertThatThrownBy(() -> followEntityLink(suppliersAdminClient, root, "invoice", "invoices"))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);

        // Admin client can do everything on suppliers and invoices. We use it to setup this scenario.
        var adminClient = getHalFormsClient(applicationId, "invoice-manager", "invoice-manager");

        var profile = adminClient.follow(HalLink.from(appUri.resolve("/profile")));
        var supplierProfile = followEntityLink(adminClient, profile, "supplier", "suppliers");
        var invoiceProfile = followEntityLink(adminClient, profile, "invoice", "invoices");

        // we create 3 suppliers
        var xenitSupplier = createSupplier(adminClient, supplierProfile, "xenit", "123456", "986532");
        var amexioSupplier = createSupplier(adminClient, supplierProfile, "amexio", "785421", "55555");
        var otherSupplier = createSupplier(adminClient, supplierProfile, "other", "987654321", "444444");

        // creating 4 different invoices
        var xenitInvoiceUnder500 = createInvoice(adminClient, invoiceProfile, xenitSupplier, 400);
        var xenitInvoiceOver500 = createInvoice(adminClient, invoiceProfile, xenitSupplier, 600);
        var amexioInvoice = createInvoice(adminClient, invoiceProfile, amexioSupplier, 400);
        var otherInvoice = createInvoice(adminClient, invoiceProfile, otherSupplier, 300);

        // invoice-maintainer can see invoices of xenit and amexio, under total_amount 500
        var invoiceMaintainerRestClient = getRestClient(applicationId, "invoice-maintainer", "invoice-maintainer");
        var invoiceMaintainerClient = HalFormsClient.builder().restClient(invoiceMaintainerRestClient).build();

        var invoices = invoiceMaintainerClient.follow(invoiceProfile.getRequiredLink("describes", "collection"));
        assertThat(invoices.getEmbeddedOrEmpty("item"))
                .map(invoice -> invoice.getSelfLink().getHref())
                .containsExactlyInAnyOrder(xenitInvoiceUnder500.getHref(), amexioInvoice.getHref());

        // check we can access document of xenit and amexio, under total_amount 500
        for (var invoiceLink : List.of(xenitInvoiceUnder500, amexioInvoice)) {
            var invoice = invoiceMaintainerClient.follow(invoiceLink);

            // Check invoice attributes
            assertNotNull(invoice.getField("id"));
            assertEquals(400.0, invoice.getField("total_amount"));
            assertEquals("2024-06-30T21:59:59Z", invoice.getField("received"));
            assertEquals("2025-06-30T21:59:59Z", invoice.getField("pay_before"));
            assertEquals(Map.of(
                    "filename", "test.txt",
                    "mimetype", "text/plain",
                    "length", "Hello world!".length()
            ), invoice.getField("document"));

            // Check relation
            assertFalse(invoice.getFields().containsKey("supplier"));
            assertTrue(invoice.getLink("cg:relation", "supplier").isPresent());

            // fetching content is not a hal-forms request, so it goes over the plain rest-client
            var invoiceDocumentResponse = invoiceMaintainerRestClient.get()
                    .uri(invoice.getRequiredLink("cg:content", "document").getURI())
                    .retrieve()
                    .toEntity(String.class);

            assertEquals(HttpStatus.OK, invoiceDocumentResponse.getStatusCode());
            assertEquals("Hello world!", invoiceDocumentResponse.getBody());
        }

        // check we are not allowed to access the other documents
        for (var invoiceLink : List.of(xenitInvoiceOver500, otherInvoice)) {
            var exception1 = assertThrows(HttpClientErrorException.class, () -> {
                invoiceMaintainerRestClient.get()
                        .uri(invoiceLink.getHref() + "/document")
                        .retrieve()
                        .toEntity(String.class); // this line throws the exception
            });
            // v1 returns 404, v2 returns 403
            assertTrue(Stream.of(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND)
                    .anyMatch(status -> exception1.getStatusCode().isSameCodeAs(status)));
        }

        // Check events for 3 suppliers + 4 invoices events received
        // A log line from nginx looks like this:
        // [WEBHOOK] POST /receive?entity=invoice HTTP/1.1 app_id=be814930-6ccc-4ac1-ad5d-700d020f88ec
        record WebhookRequest(String method, String url, String appId) {}

        try(var rawReceiverLogs = kubernetesClient.apps().deployments()
                .withName("webhook-receiver")
                .getLogReader();
            var reader = new BufferedReader(rawReceiverLogs)
        ) {
            var requestPattern = Pattern.compile("\\[(\\w+)\\]\\s+(\\w+)\\s+([^\\s]+)\\s+HTTP.*app_id=([^\\s]+)");

            var requests = reader.lines()
                    .filter(l -> l.contains("[WEBHOOK]"))
                    .map(line -> {
                        var matcher = requestPattern.matcher(line);
                        if (matcher.find()) {
                            return new WebhookRequest(
                                    matcher.group(2),
                                    matcher.group(3),
                                    matcher.group(4)
                            );
                        }
                        return null;
                    })
                    .filter(req -> req != null)
                    .filter(req -> req.appId().equals(applicationId))
                    .toList();

            // v1 events are slightly broken: creating an invoice with content in one request
            // will trigger both a create and an update event
            var expectedInvoiceEvents = dockerImageTag.equals("v1") ? 8 : 4;
            assertThat(requests)
                    .areExactly(3, new Condition<>(r -> r.url().equals("/receive?entity=supplier"), "supplier webhooks"))
                    .areExactly(expectedInvoiceEvents, new Condition<>(r -> r.url().equals("/receive?entity=invoice"), "invoice webhooks"));

        };


    }

    /**
     * Looks up the entity profile or entity collection through the {@code cg:entity} links of the application profile.
     * <p>
     * v1 applications name those links after the (plural) collection relation, v2 applications after the (singular)
     * entity link name, so multiple candidate names can be passed.
     */
    private static HalDocument followEntityLink(HalFormsClient client, HalDocument profile, String... linkNames) {
        var entityLink = Stream.of(linkNames)
                .map(linkName -> profile.getLink("cg:entity", linkName))
                .flatMap(Optional::stream)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "No cg:entity link named %s".formatted(String.join(" or ", linkNames))));

        if (entityLink.isTemplated()) {
            // Strip templated query parameters
            entityLink = HalLink.from(entityLink.expand(Map.of()));
        }

        return client.follow(entityLink);
    }

    private static HalLink createInvoice(HalFormsClient client, HalDocument invoiceProfile, HalLink supplier,
            double totalAmount) {
        return create(client, invoiceProfile, Map.of(
                "received", "2024-06-30T21:59:59Z",
                "pay_before", "2025-06-30T21:59:59Z",
                "total_amount", String.format(Locale.ROOT, "%.2f", totalAmount),
                "supplier", supplier.getHref(),
                "document", new ClassPathResource("/document/test.txt")
        ));
    }

    static HalLink createSupplier(HalFormsClient client, HalDocument supplierProfile, String name, String telephone,
            String bankAccount) {
        return create(client, supplierProfile, Map.of(
                "name", name,
                "telephone", telephone,
                "bank_account", bankAccount
        ));
    }

    /**
     * Creates an entity with the {@code create-form} hal-forms template of its profile.
     *
     * @return the link to the created entity
     */
    private static HalLink create(HalFormsClient client, HalDocument entityProfile, Map<String, Object> properties) {
        var response = client.requestTemplate(entityProfile, "create-form")
                .properties(properties)
                .execute()
                .toVoidEntity();

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertThat(response.getHeaders().getLocation()).isNotNull();

        return HalLink.from(response.getHeaders().getLocation());
    }

    static HalFormsClient getHalFormsClient(String applicationId, String clientId, String clientSecret) {
        return HalFormsClient.builder()
                .restClient(getRestClient(applicationId, clientId, clientSecret))
                .build();
    }

    @SneakyThrows
    static RestClient getRestClient(String applicationId, String clientId, String clientSecret) {
        var dockerHostAddress = InetAddress.getByName(DockerClientFactory.instance().dockerHostIpAddress());
        var hosts = Map.of(
                "auth.contentgrid.test", new InetAddress[]{dockerHostAddress},
                applicationId + ".apps.contentgrid.test", new InetAddress[]{dockerHostAddress}
        );

        var connectionManager = BasicHttpClientConnectionManager.create(null,
                new SystemDefaultDnsResolver() {
                    @Override
                    public InetAddress[] resolve(final String host) throws UnknownHostException {
                        var hostsEntry = hosts.get(host);
                        if (hostsEntry != null) {
                            return hostsEntry;
                        }

                        return super.resolve(host);
                    }
                },
                RegistryBuilder.<TlsSocketStrategy>create()
                        .register("https", DefaultClientTlsStrategy.createDefault())
                        .build(),
                null
        );

        var httpClient = HttpClientBuilder.create()
                .setConnectionManager(connectionManager)
                .build();

        var restClientBuilder = RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient));

        var client = restClientBuilder.build();

        var response = client
                .post()
                .uri("http://auth.contentgrid.test/realms/cg-fff710df-7947-403a-8f45-a3fa97b9b4b2/protocol/openid-connect/token")
                .body(new LinkedMultiValueMap(Map.of(
                        "grant_type", List.of("client_credentials"),
                        "client_id", List.of(clientId),
                        "client_secret", List.of(clientSecret)
                )))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .retrieve()
                .toEntity(String.class);

        var jsonMapper = JsonMapper.builder().build();
        var jsonNode = jsonMapper.readTree(response.getBody());
        var accessToken = jsonNode.get("access_token").asString();

        restClientBuilder.requestInterceptor((request, body, execution) -> {
            request.getHeaders().setBearerAuth(accessToken);
            return execution.execute(request, body);
        });

        return restClientBuilder.build();
    }

    @SneakyThrows
    private String deployApplication(String dockerImage, boolean opaSidecar) {

        var applicationId = UUID.randomUUID().toString();
        var deploymentId = UUID.randomUUID().toString();
        var policyPackage = "contentgrid.userapps.x" + deploymentId.replace("-", "");
        kubernetesLogger.include(Deployment.class, ResourceMatcher.labelled(Map.of("app.contentgrid.com/app-id", applicationId)).inNamespace(APP_NAMESPACE));

        var k8sAppClientConfig = kubernetesClient.getConfiguration();
        k8sAppClientConfig.setNamespace(APP_NAMESPACE);
        var appClient = new KubernetesClientBuilder().withConfig(k8sAppClientConfig).build();

        var dbSecret = new SecretBuilder()
                .withNewMetadata()
                .withName(applicationId + "-db")
                .withLabels(Map.of(
                        "app.contentgrid.com/app-id", applicationId,
                        "app.contentgrid.com/application-id", applicationId,
                        "app.contentgrid.com/service-type", "api",
                        "app.kubernetes.io/managed-by", "contentgrid"
                ))
                .endMetadata()
                .withType("Opaque")
                .addToStringData("spring.datasource.password", appDatabase.getPassword())
                .addToStringData("spring.datasource.url", appDatabase.getJdbcUrl())
                .addToStringData("spring.datasource.username", appDatabase.getUsername())
                .build();
        //deploy the secret
        appClient.secrets().resource(dbSecret).create();

        var s3Secret = new SecretBuilder()
                .withNewMetadata()
                .withName(applicationId + "-sto")
                .withLabels(Map.of(
                        "app.contentgrid.com/app-id", applicationId,
                        "app.contentgrid.com/application-id", applicationId,
                        "app.contentgrid.com/service-type", "api",
                        "app.kubernetes.io/managed-by", "contentgrid"
                ))
                .endMetadata()
                .withType("Opaque")
                .addToStringData("spring.content.storage.type.default", "s3")
                .addToStringData("spring.content.s3.endpoint", appObjectStorage.getEndpoint().toString())
                .addToStringData("spring.content.s3.bucket", APP_BUCKET)
                .addToStringData("spring.content.s3.region", "none")
                .addToStringData("spring.content.s3.accessKey", appObjectStorage.getAccessKey())
                .addToStringData("spring.content.s3.secretKey", appObjectStorage.getSecretKey())
                .build();

        appClient.secrets().resource(s3Secret).create();

        createEgressNetworkPolicy(appClient, deploymentId + "-db", deploymentId, appDatabase.getHost(), appDatabase.getFirstMappedPort());
        createEgressNetworkPolicy(appClient, deploymentId + "-objectstorage", deploymentId, appObjectStorage.getHost(), appObjectStorage.getFirstMappedPort());

        // Deploy manifest
        var manifestFilename = opaSidecar ? "testapp/manifests-opa_sidecar.yaml" : "testapp/manifests.yaml";
        try (var manifestInputStream = HelmIntegrationTest.class.getClassLoader()
                .getResourceAsStream(manifestFilename)) {
            var manifestContent = new String(manifestInputStream.readAllBytes(), StandardCharsets.UTF_8);

            // Replace variables
            manifestContent = manifestContent
                    .replace("$APP_ID", applicationId)
                    .replace("$DEPLOYMENT_ID", deploymentId)
                    .replace("$POLICY_PACKAGE", policyPackage)
                    .replace("$DOCKER_IMAGE", dockerImage);

            appClient.load(new ByteArrayInputStream(manifestContent.getBytes(StandardCharsets.UTF_8)))
                    .serverSideApply();
        }

        // Create s3 bucket
        try (var mc = MinioAsyncClient.builder()
                .endpoint(appObjectStorage.getEndpoint().toURL())
                .credentials(appObjectStorage.getAccessKey(), appObjectStorage.getSecretKey())
                .build()) {
            mc.makeBucket(MakeBucketArgs.builder()
                    .bucket(APP_BUCKET)
                    .build());
        }

        if (!opaSidecar) {
            kubernetesLogger.include(Deployment.class, ResourceMatcher.named("solon"))
                    .include(Deployment.class, ResourceMatcher.named("openpolicyagent"));
        }

        new KubernetesResourceWaiter(kubernetesClient)
                .include(Deployment.class, ResourceMatcher.named("api-d-" + deploymentId).inNamespace(APP_NAMESPACE))
                .await(wait -> wait.atMost(3, TimeUnit.MINUTES));

        if (!opaSidecar) {
            var solonWaiter = new KubernetesResourceWaiter(kubernetesClient)
                    .include(Deployment.class, ResourceMatcher.named("solon"));

            // Wait until Solon has logged the deployment ID, indicating that it has served the new bundle to OPA
            await()
                    .atMost(1, TimeUnit.MINUTES)
                    .until(() -> solonWaiter.resources()
                            .flatMap(AwaitableResource::logs)
                            .anyMatch(logLine -> logLine.line().contains("+ " + deploymentId))
                    );

            Thread.sleep(1000); // Wait for 1 second so OPA has time to actually activate the bundle

            kubernetesLogger.exclude(Deployment.class, ResourceMatcher.named("solon"))
                    .exclude(Deployment.class, ResourceMatcher.named("openpolicyagent"));
        }

        // we use client credentials, so the secret is not actually used yet by the Gateway
        var gwSecret = new SecretBuilder()
                .withNewMetadata()
                .withGenerateName("gateway-iam-")
                .withLabels(Map.of(
                        "app.contentgrid.com/app-id", applicationId,
                        "app.contentgrid.com/application-id", applicationId,
                        "app.contentgrid.com/service-type", "gateway",
                        "app.kubernetes.io/managed-by", "contentgrid"
                ))
                .endMetadata()
                .withType("Opaque")
                .addToStringData("contentgrid.idp.client-id",
                        "contentgrid-app-gateway-ccaa8db6-2514-4680-a2ad-01de8cab8922")
                .addToStringData("contentgrid.idp.client-secret", "7fe30b6e-f104-4bf4-9510-e1165eb12865")
                .addToStringData("contentgrid.idp.issuer-uri",
                        "http://auth.contentgrid.test/realms/cg-fff710df-7947-403a-8f45-a3fa97b9b4b2")
                .build();

        appClient.secrets().resource(gwSecret).create();

        return applicationId;
    }

    private void createEgressNetworkPolicy(KubernetesClient client, String name, String deploymentId, String ip, int port) {
        var networkPolicy = new NetworkPolicyBuilder()
                .withNewMetadata()
                .withName(name)
                .endMetadata()
                .withNewSpec()
                .withPodSelector(new LabelSelectorBuilder()
                        .addToMatchLabels("app.contentgrid.com/deployment-id", deploymentId)
                        .build())
                .withPolicyTypes("Egress")
                .withEgress(new NetworkPolicyEgressRuleBuilder()
                        .withTo(new NetworkPolicyPeerBuilder()
                                .withIpBlock(new IPBlockBuilder()
                                        .withCidr(ip + "/32")
                                        .build())
                                .build())
                        .withPorts(new NetworkPolicyPortBuilder()
                                .withProtocol("TCP")
                                .withPort(new IntOrString(port))
                                .build())
                        .build())
                .endSpec()
                .build();

        client.network().v1().networkPolicies().resource(networkPolicy).create();
    }

}
