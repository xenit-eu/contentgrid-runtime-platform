# Manually deploying an app

If you're not deploying through the ContentGrid Console, several resources need to be made by hand. Everything listed
here goes into the [contentgrid-apps](./install.md#namespaces) namespace, unless specified otherwise.

Resources in the apps namespace are discovered by their labels (e.g. `app.contentgrid.com/service-type`) by services
running in the system namespace, which allows e.g. the gateway to route the correct requests and find the correct
Keycloak client for it.


## 1. Prerequisites

The runtime platform can be shared by several apps. See [install.md](./install.md) on how to set it up. The following
must already be provisioned for each individual app you want to deploy:

- A Keycloak realm with a gateway and a webapp client, see [keycloak-setup.md](./keycloak-setup.md)
  - The secret and configmaps described in that document can already be deployed to the apps namespace
  - This document also has you choose the URLs for the app's frontend and backend. We will use `cg-api.example.com`
    and `navigator.example.com` in this document
- A Postgres database
- An object storage bucket (S3 compatible)
- The zip for the blueprint artifact, either baked into the image or reachable over S3
  - If you bake it into a custom appserver image, also set the `CONTENTGRID_APPSERVER_BLUEPRINTARTIFACT_LOCATION` env
    var in the image itself, rather than in the yaml here

### Application ID, Deployment ID

Apps have an `application-id` and `deployment-id`. When deployed through the Console both of these are UUIDs, but you
can choose your own values to track deployments. The policy package is based on the deployment id and it **MUST**
change when you do a deployment that has updated permission policies. The safe thing is to update it every time, which
is how the Console does it. Additionally, the policy package must not contain dashes.

This document uses these example values:
- application id: `cg-acme`
- deployment id: `cg-acme-1`
- policy package: `contentgrid.userapps.cgacme1`


## 2. Secrets for the app

Create the following Secrets in your Kubernetes cluster. You may use something like external-secrets to manage this, or
sops to encrypt them.

The secret for connecting to the database, `cg-acme-db`:

```yaml
spring.datasource.url: jdbc:postgresql://10.20.30.41:5432/acme
spring.datasource.username: acme
spring.datasource.password: <password>
```

The secret for connecting to the bucket with the app's documents, `cg-acme-sto`:

```yaml
contentgrid.appserver.content-store.type: s3
contentgrid.appserver.content.s3.url: https://s3.example.com
contentgrid.appserver.content.s3.bucket: acme-documents
contentgrid.appserver.content.s3.region: fr-par
contentgrid.appserver.content.s3.accessKey: SCWACCESSKEY
contentgrid.appserver.content.s3.secretKey: 11111111-1111-1111-1111-111111111111
```

These secrets don't need particular names or labels, they just need to be mounted by the Deployment (see below). The
creation of the `blueprint-artifact-obj` secret is already taken care of by the platform install.

## 3. Deployment, Service

Apps use a generic docker image, loading their blueprint (model, policy and automations) as a zip from the blueprint
bucket on startup. The app-specific things to change in the yaml for the Deployment and Service are:

- the location of that zip
- the application id
- the deployment id
- the policy package

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api-cg-acme-1
  namespace: contentgrid-apps
  labels:
    app.contentgrid.com/application-id: cg-acme
    app.contentgrid.com/deployment-id: cg-acme-1
    app.contentgrid.com/service-type: api
    app.kubernetes.io/managed-by: contentgrid
spec:
  replicas: 1
  selector:
    matchLabels:
      app.contentgrid.com/deployment-id: cg-acme-1
      app.contentgrid.com/service-type: api
  template:
    metadata:
      labels:
        app.contentgrid.com/application-id: cg-acme
        app.contentgrid.com/deployment-id: cg-acme-1
        app.contentgrid.com/service-type: api
        app.kubernetes.io/managed-by: contentgrid
    spec:
      automountServiceAccountToken: false
      enableServiceLinks: false
      initContainers:
        - name: api-init
          image: ghcr.io/xenit-eu/contentgrid-appserver:0.1.6
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: initContainer
            - name: SPRING_CONFIG_IMPORT
              value: configtree:/etc/app/config/*/
            - name: CONTENTGRID_APPSERVER_BLUEPRINTARTIFACT_LOCATION
              value: s3:blueprint-artifacts/artifacts/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.zip
            - name: CONTENTGRID_SYSTEM_APPLICATIONID
              value: cg-acme
            - name: CONTENTGRID_SYSTEM_DEPLOYMENTID
              value: cg-acme-1
          volumeMounts:
            - { name: rtp-config, mountPath: /etc/app/config/runtime, readOnly: true }
            - { name: db, mountPath: /etc/app/config/db, readOnly: true }
            - { name: storage, mountPath: /etc/app/config/objectstorage, readOnly: true }
            - { name: blueprint, mountPath: /etc/app/config/blueprint, readOnly: true }
      containers:
        - name: api
          image: ghcr.io/xenit-eu/contentgrid-appserver:0.1.6
          env:
            - name: SPRING_CONFIG_IMPORT
              value: configtree:/etc/app/config/*/
            - name: CONTENTGRID_APPSERVER_BLUEPRINTARTIFACT_LOCATION
              value: s3:blueprint-artifacts/artifacts/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.zip
            - name: CONTENTGRID_SYSTEM_APPLICATIONID
              value: cg-acme
            - name: CONTENTGRID_SYSTEM_DEPLOYMENTID
              value: cg-acme-1
            - name: CONTENTGRID_SYSTEM_POLICYPACKAGE
              value: contentgrid.userapps.cgacme1
            - name: CONTENTGRID_EVENTS_WEBHOOKCONFIGURL
              value: http://api-cg-acme-1.contentgrid-apps.svc.cluster.local:8081/actuator/webhooks
          ports:
            - { name: http, containerPort: 8080 }
            - { name: management, containerPort: 8081 }
          startupProbe:
            httpGet: { path: /actuator/health/liveness, port: management }
            periodSeconds: 5
            failureThreshold: 60
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: management }
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: management }
          resources:
            requests: { cpu: 500m, memory: 512Mi }
            limits: { memory: 2Gi }
          volumeMounts:
            - { name: rtp-config, mountPath: /etc/app/config/runtime, readOnly: true }
            - { name: db, mountPath: /etc/app/config/db, readOnly: true }
            - { name: storage, mountPath: /etc/app/config/objectstorage, readOnly: true }
            - { name: blueprint, mountPath: /etc/app/config/blueprint, readOnly: true }
      volumes:
        - name: rtp-config
          configMap: { name: runtime-platform-config, optional: true }
        - name: db
          secret: { secretName: cg-acme-db }
        - name: storage
          secret: { secretName: cg-acme-sto }
        - name: blueprint
          secret: { secretName: blueprint-artifact-obj }
---
apiVersion: v1
kind: Service
metadata:
  name: api-cg-acme-1
  namespace: contentgrid-apps
  annotations:
    authz.contentgrid.com/policy-package: contentgrid.userapps.cgacme1
  labels:
    app.contentgrid.com/application-id: cg-acme
    app.contentgrid.com/deployment-id: cg-acme-1
    app.contentgrid.com/service-type: api
    app.kubernetes.io/managed-by: contentgrid
spec:
  selector:
    app.contentgrid.com/deployment-id: cg-acme-1
    app.contentgrid.com/service-type: api
  ports:
    - { name: http, port: 8080, targetPort: http }
    - { name: management, port: 8081, targetPort: management }
```

## 4. Verification

1. `kubectl -n contentgrid-apps get pods` should show the app as a running, healthy pod.
2. `kubectl -n contentgrid-system get ingress` should show the ingresses Pathfinder made from the routing configmaps.
3. `https://cg-api.example.com/` should return a 401, and a 200 with a bearer token for a user in the realm.
4. `https://navigator.example.com/` should show the frontend, and let you log in.

## 5. Updating

Download the new blueprint zip, upload it to the blueprint bucket, and re-apply the above Deployment with an updated
`CONTENTGRID_APPSERVER_BLUEPRINTARTIFACT_LOCATION`, an updated deployment id, and an updated policy package.

## Variations

### An app image of its own

An image can be built for a specific app with the blueprint baked in, in which case you can set
`CONTENTGRID_APPSERVER_BLUEPRINTARTIFACT_LOCATION` in the image itself, pointing to the file path with a `file:` prefix
(rather than `s3:`). In this case you don't need to set up the blueprint bucket. Everything else stays the same, but
you have to build and push a new image for every update.
