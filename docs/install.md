# Installing the runtime platform

The runtime platform is normally installed on a Kubernetes cluster with Cilium, haproxy as ingress, cert-manager
for automatically getting Let's Encrypt certs, and external-secrets for secret management. Refer to the sections
at the end if you want to diverge from this.

## 1. Prerequisites

- `kubectl` and `helm` installed on your machine
- A Kubernetes cluster with the following installed:
  - Cilium (or see [No Cilium](#no-cilium))
  - an ingress controller, we default to haproxy
  - [cert-manager](https://cert-manager.io/), configured with a ClusterIssuer, we default to `letsencrypt-production`
    (otherwise see [No cert-manager](#no-cert-manager))
  - [external-secrets](https://external-secrets.io/) configured with a `ClusterSecretStore`
    (otherwise see [No external-secrets](#no-external-secrets))
- This cluster should be able to reach `ghcr.io`, `quay.io` and `docker.io`
- A Postgres db for Keycloak
- An S3-compatible object storage bucket for putting the blueprints

### Domains

These should resolve to the ingress' IP address. cert-manager will issue their certificates, including for the
ingresses that Pathfinder generates per deployed app. Substitute `.example.com` with your domain.

| Hostname                 | Value                          | Serves                                     |
|--------------------------|--------------------------------|--------------------------------------------|
| `auth.example.com`       | `keycloak.host`                | Keycloak                                   |
| `extensions.example.com` | `tokenmonger.host`             | Tokenmonger, which issues extension tokens |
| `*.apps.example.com`     | `userapps.defaultDomainSuffix` | The default suffix for app domains         |

**Important: These names must resolve to the correct pod both from a user's laptop and from inside the cluster.**
In order to validate JWT tokens, the gateway resolves the issuer's domain name.

### Namespaces

The chart works across two namespaces, one for the actual apps that are deployed, and one for the system to support
them. This chart is installed into the latter.

```shell
kubectl create namespace contentgrid-apps
kubectl create namespace contentgrid-system
```

You can change these names, by setting the value `userapps.namespace`, and by using the --namespace flag on Helm,
respectively.

## 2. Write a values file

Copy one of our environments in [`contentgrid-rtp-helm/envs/`](../contentgrid-rtp-helm/envs/) to `my-customer.yaml` as
a starting point. Everything not set there has a default in
[`contentgrid-rtp-helm/values.yaml`](../contentgrid-rtp-helm/values.yaml).

These are the values that definitely have to change for a new cluster:

```yaml
# my-customer.yaml
secretStoreName: my-secretstore

keycloak:
  host: auth.example.com
  db:
    secretKey: path:/rtp/keycloak-db-password # "path:" is Scaleway syntax, adjust to your provider

keycloakx:
  database:
    hostname: 10.20.30.40 # must be an IP: it's also used as a CIDR in Keycloak's egress policy
    port: "5432"
  # Overriding extraEnv replaces the block in values.yaml, so repeat JAVA_OPTS_APPEND.
  # The bootstrap admin is only used while no admin account exists yet; you remove it in step 4.
  extraEnv: |
    - name: KC_BOOTSTRAP_ADMIN_USERNAME
      value: "admin"
    - name: KC_BOOTSTRAP_ADMIN_PASSWORD
      value: "<a throwaway password>"
    - name: JAVA_OPTS_APPEND
      value: >-
        -XX:+UseContainerSupport
        -XX:MaxRAMPercentage=50.0
        -Djava.awt.headless=true

tokenmonger:
  host: extensions.example.com

certificates:
  defaultIssuer: letsencrypt-production # name of an existing ClusterIssuer

userapps:
  namespace: contentgrid-apps
  defaultDomainSuffix: apps.example.com
  # Regex matching the IP range of pods in the contentgrid-system namespace. Needed because apps only trust
  # X-Forwarded-* headers from IP addresses matching this. The default is Scaleway-specific.
  forwardHeadersTrustedIp: '10\.42\.\d{1,3}\.\d{1,3}'
  # Apps are default-deny too, so their databases and object storage need to be listed
  # here to be reachable.
  database:
    - ip: 10.20.30.41
      port: "5432"
  objectstorage:
    - fqdn:
        name: s3.example.com
        pattern: "*.s3.example.com"
      port: "443"

liaison:
  renditions:
    uriTemplate: https://renditions.example.com/renditions/get/pdf{?url}
  extract:
    baseUrl: https://extract.example.com/extract/

# Surveyor exports metrics for our Management Platform to collect. If you're installing this chart on your own infra,
# there's nothing to collect those metrics.
surveyor:
  enabled: false

development: false # true forces `imagePullPolicy: Always` for chart development
```

If you have cert-manager configured, but not with Let's Encrypt, also set `certificates.issuers`.

If you have a different ingress controller than haproxy, set `ingressClassName` and `userapps.ingressClassName`.

## 3. Secrets

In the Postgres instance you have for Keycloak, create a database `keycloak` with a user `keycloak`, and put that
user's password in the store for external secrets at the path `/rtp/keycloak-db-password`.

For the bucket you have for blueprints, create an entry in your secret store at the path
`/rtp/blueprint-artifacts-objectstorage`, structured like this:

```json
{
  "contentgrid.appserver.blueprint-artifact.s3.endpoint": "https://s3.fr-par.scw.cloud",
  "contentgrid.appserver.blueprint-artifact.s3.region": "fr-par",
  "contentgrid.appserver.blueprint-artifact.s3.access-key": "SCWACCESSKEY",
  "contentgrid.appserver.blueprint-artifact.s3.secret-key": "00000000-0000-0000-0000-000000000000"
}
```

Substitute the values with what your object storage provider needs.

## 4. Install

```shell
helm dependency update ./contentgrid-rtp-helm
helm -n contentgrid-system upgrade --install contentgrid-rtp ./contentgrid-rtp-helm \
  --values ./contentgrid-rtp-helm/values.yaml --values ./my-customer.yaml
```

Some pre-install jobs will run, for e.g. configuring the rabbitmq. These jobs need to talk to the Kubernetes API, so
if it hangs there is probably something misconfigured with your NetworkPolicies.

It's expected that the Tokenmonger deployment doesn't become healthy yet.

## 5. Post-install

### Configure Tokenmonger extensions

Tokenmonger needs the `tokenmonger-extensions` configmap to start up. This configmap is not managed by Helm because it
semi-frequently needs manual editing (to add extensions). There's a Helm chart for installing Renditions, so we'll
start with the following (substitute example.com with your domain):

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: tokenmonger-extensions
  namespace: contentgrid-system
data:
  contentgrid.tokenmonger.extensions.registration.renditions.extension-id: renditions
  contentgrid.tokenmonger.extensions.registration.renditions.resource-uris: https://renditions.example.com/
  # Ensure a different registration for another extension, e.g. for the extract service:
  # contentgrid.tokenmonger.extensions.registration.extract.extension-id: extract
  # contentgrid.tokenmonger.extensions.registration.extract.resource-uris: https://extract.example.com/
```

The `extension-id` in the config must match the _Extension ID_ field on the corresponding service account in Keycloak.
See [the extension documentation](https://github.com/xenit-eu/contentgrid-system-design/blob/main/runbooks/automation-system-registration.md#keycloak-1)
for more information.

### Keycloak

Log in to Keycloak at https://auth.example.com/ using the credentials you filled in in the values file, then change the
password to something secure. Now you can remove `KC_BOOTSTRAP_ADMIN_USERNAME` and `KC_BOOTSTRAP_ADMIN_PASSWORD` from
the values file.

The chart automatically imports the `extensions` realm, used by Tokenmonger. Read
[docs/keycloak-setup.md](./keycloak-setup.md) for instructions on setting up a realm for the users of an app.

## 6. Verification

1. `kubectl -n contentgrid-system get pods` should show healthy running pods, completed jobs.
2. `https://auth.example.com/` should show the Keycloak with a working certificate.
3. `https://extensions.example.com/authentication/system` should return a HTML page.

## Variations

### No Cilium

The chart checks whether Cilium is installed. If it isn't, it makes normal NetworkPolicies instead, and you have to
define the following in your values file:

- `apiserver.cidr`: CIDR for the IP of the Kubernetes API
- `ingress.public_ip.cidr`: CIDR for the public IP of the ingress controller
- `keycloak.smtp_ip.cidr`: CIDR for the IP of the SMTP server used by Keycloak
- `userapps.objectstorage`: This is an array, provide an object with `ip` and `port` keys, e.g.:
  ```yaml
  userapps:
    objectstorage:
      - ip: 10.20.30.42
        port: "443"
  ```

### No cert-manager

If certificates can't be issued for you, obtain them yourself and create each one by hand as a `kubernetes.io/tls`
secret with `tls.crt` and `tls.key`:

| Secret name           | Hostname                  | Purpose                                            |
|-----------------------|---------------------------|----------------------------------------------------|
| `keycloak-tls`        | `auth.example.com`        | The Keycloak ingress                               |
| `tokenmonger-tls`     | `extensions.example.com`  | The Tokenmonger ingress                            |
| _varies_              | The app's domains         | The ingresses Pathfinder generates per app         |

Pathfinder picks the names in that last row itself, so you can only create those secrets after it has created the
ingress: run `kubectl -n contentgrid-system get ingress -o yaml` and read `spec.tls[].secretName`. An ingress with
a missing secret serves the ingress controller's default certificate, which is usually how you notice. Alternatively,
if you will only run a small amount of known apps, you can disable Pathfinder and create the ingresses yourself.

### No Let's Encrypt (but with cert-manager)

Set `certificates.issuers` in your values file.

### No haproxy

Set `ingressClassName` and `userapps.ingressClassName` to whatever else you use for ingress.

### No external-secrets

The chart checks whether the `external-secrets.io/v1` API is present. If not, it doesn't create any ExternalSecrets.
In this case, you create the secrets of step 3 yourself instead of putting them in a secret store.

For the Keycloak database password:

```shell
kubectl -n contentgrid-system create secret generic keycloak-database  --from-literal=password='<keycloak db password>'
```

For the blueprint bucket, one key per line of the JSON in step 3:

```shell
kubectl -n contentgrid-apps create secret generic blueprint-artifact-obj \
  --from-literal=contentgrid.appserver.blueprint-artifact.s3.endpoint='https://s3.fr-par.scw.cloud' \
  --from-literal=contentgrid.appserver.blueprint-artifact.s3.region='fr-par' \
  --from-literal=contentgrid.appserver.blueprint-artifact.s3.access-key='SCWACCESSKEY' \
  --from-literal=contentgrid.appserver.blueprint-artifact.s3.secret-key='00000000-0000-0000-0000-000000000000'
```

### More than one Keycloak replica

The chart defaults to one replica. If you raise `keycloakx.replicas`, add
`-Djgroups.dns.query={{ include "keycloak.fullname" . }}-headless` to `JAVA_OPTS_APPEND` so Keycloak can form its
cluster.

### Monitoring the platform from outside

Set `surveyor.enabled: true` and `surveyor.pegman.host` if something does need to collect the platform's metrics. That
adds a hostname, a certificate, and a secret at `surveyor.pegman.systems.secretKey` holding the credentials for the
systems it reports to.

### OpenShift

#### Routes

OpenShift converts each `Ingress` into `Route` resources, and something in this conversion breaks for our ingresses,
seemingly related to having more than one service per path. We need a route for `/config.js` on an app's webapp domain
(should go to `liaison-service`) and `/.well-known/jwks.json` on its gateway domain (should go to `slingshot-service`).
Check both after deploying an app, and check Tokenmonger's routes too. To fix this, read the generated Route that _was_
created for `/` on that host, then manually create a copy of it with a new name, the correct `spec.path` and the
correct `spec.to.name`.

#### Security Context Constraints

We've had problems with OpenShift running in the `restricted-v2` SCC: Keycloak won't start because the keycloakx
subchart wants to set `fsGroup` and `runAsUser` to 1000, and the SCC doesn't allow this. We need to set it to null so
that OpenShift will assign valid values, but we couldn't just set the value on the chart to null because the particular
ArgoCD version used had a bug where you couldn't override things with null. We worked around this with a kustomize
patch to remove the fields from the `keycloak` StatefulSet (this does allow setting to null) so that OpenShift assigns
valid `fsGroup` and `runAsUser` values itself.
