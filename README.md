# ContentGrid Runtime Platform

This repository is a Helm chart for the ContentGrid Runtime Platform. The Runtime Platform is where we run our user's
ContentGrid apps. This chart is only for things to support the apps, the apps themselves are deployed via the
ContentGrid Management Platform.

The platform handles HTTP routing, authentication, authorization, event delivery, and observability for all deployed
user apps.

## Components

| Component       | Role                                                                                                                           |
|-----------------|--------------------------------------------------------------------------------------------------------------------------------|
| **Gateway**     | HTTP reverse proxy that routes traffic to user apps, validates JWT tokens, and discovers deployed apps dynamically             |
| **Keycloak**    | Identity provider handling user authentication for the platform and user applications                                          |
| **OPA**         | Evaluates attribute-based access control (ABAC) policies for user apps                                                         |
| **Solon**       | Distributes permission policy bundles to OPA, discovers user apps and fetches their policy via the management port             |
| **Pathfinder**  | Manages ingress routing rules for the user apps                                                                                |
| **Navigator**   | Serves the ContentGrid Navigator frontend at the user application's domain (`<uuid>.<region>.contentgrid.app`)                 |
| **Liaison**     | Serves a JavaScript configuration snippet on the user app domain so that Navigator can connect to the correct backend          |
| **Tokenmonger** | Issues OAuth2 tokens for extensions to reach the apps, scoped to specific tasks                                                |
| **Slingshot**   | Consumes events from the message queue and delivers them to webhooks configured by user apps                                   |
| **RabbitMQ**    | Message broker for event-driven processing between platform components                                                         |
| **Surveyor**    | Collects platform metrics and ships them to Surveyor in the Management Platform                                                |

## Configuration

The following values must be provided when installing the Helm chart:

| Value                          | Description                                                                                                                                 |
|--------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| `userapps.defaultDomainSuffix` | Default domain suffix for user applications (e.g. `apps.example.com`)                                                                       |
| `apiserver.cidr`               | CIDR of the Kubernetes API server (required when not using Cilium)                                                                          |
| `ingress.public_ip.cidr`       | CIDR of the ingress controller public IP (required when not using Cilium)                                                                   |
| `keycloak.smtp_ip.cidr`        | CIDR of the SMTP server used by Keycloak (required when not using Cilium)                                                                   |
| `secretStoreName`              | Name of the `SecretStore` resource used by [external-secrets.io](https://external-secrets.io/). Otherwise you must create secrets manually. |

All optional configuration is documented in [`contentgrid-rtp-helm/values.yaml`](contentgrid-rtp-helm/values.yaml).

### Environments

Environment-specific value overrides live in [`contentgrid-rtp-helm/envs/`](contentgrid-rtp-helm/envs/):

- `scw-prod.yaml`: Our production environment, lives at `*.eu-west-1.contentgrid.app` and `*.eu-west-1.contentgrid.cloud`
- `scw-sandbox.yaml`: Our sandbox environment, lives at `*.sandbox.contentgrid.app` and `*.sandbox.contentgrid.cloud`
- `scw-drp.yaml`: Disaster recovery, not current in use

For a new customer-specific environment, create a new Helm chart that depends on this one.

### Secrets

Secrets are managed via [ExternalSecrets](https://external-secrets.io/), referencing the `SecretStore` configured by
`secretStoreName`. Secret references follow the pattern `path:/rtp/<secret-name>` (e.g. `path:/rtp/keycloak-db-password`).

On first install, the chart bootstraps JWT signing keys for the gateway automatically via pre-install hooks.

For special customer environments that can't reach our Secret Manager, another way of providing secrets must be
provided by the customer's environment.

## Deploying

To upgrade or install the chart:

```shell
helm -n contentgrid-system upgrade contentgrid-rtp ./contentgrid-rtp-helm --values ./contentgrid-rtp-helm/values.yaml --values ./contentgrid-rtp-helm/envs/scw-prod.yaml
```

Replace `scw-prod.yaml` with the appropriate environment file if needed.

## Build artifacts

The Helm chart is published as an OCI package to `ghcr.io/xenit-eu/contentgrid-rtp-helm` on each git tag.

## Debugging

### Enabling access logs

Set `gateway.accessLogging=true` to enable reactor-netty HTTP access logs on the gateway (adds `-Dreactor.netty.http.server.accessLogEnabled=true` to `JAVA_TOOL_OPTIONS`).

### Debugging an integration test with a remote JVM debugger

Steps to attach a debugger to one of the services during integration tests:

1. Set `<project>.debug=true`, e.g. `pathfinder.debug=true`. This adds `JAVA_TOOL_OPTIONS` for remote debugging and removes the liveness/readiness probes.
2. Put a breakpoint on `K8sTestUtils.waitUntilDeploymentsReady()` in the `beforeAll` method and run the integration test in debug mode.
3. Use k9s or kubectl to forward port 5005 of the pod. Find the `kubeconfig.yaml` in `/tmp` with `ls -latr /tmp/kubeconfig*.yml`.
4. In IntelliJ, add a new Run/Debug configuration of type **Remote JVM Debug**, specify the local port, and give it a name.
5. Debug with the new configuration.
6. If the container has already started and you need to retry: delete the pod (it restarts automatically), then re-forward the port.
