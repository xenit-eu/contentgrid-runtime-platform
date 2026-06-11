# Manually configuring Keycloak

When you use the Contentgrid Console to deploy your app, Captain will take care of configuring Keycloak, and making sure the config ends up in the correct configmaps. However, when you're manually installing a runtime platform on a customer's cluster, you need to do this yourself.

We will use **Acme** as the name of our fictional customer in this guide.

## Keycloak

First, change the admin password, and put it in our secret manager. Then create a user for yourself in the default `master` realm, and go to your user's _Role mapping_ tab. Click _Assign role_ > _Realm roles_ and choose admin.

## Realm

Click _Manage realms_ in the top left, then _Create realm_. Give it a name that includes the environment for this customer, e.g. _acme-dev_.

Go to _Realm settings_ in the navigation, set the display name (Acme Test). Then switch to the Themes tab and set _Login theme_ and _Email theme_ to _contentgrid-app_. Finally switch to the _User profile_ tab, where there are four attributes. Edit each one and under _Permissions_ uncheck the _"Who can edit?"_ box that says _User_.

Go to _Users_ in the navigation and add a test user for yourself.

### Clients

Go to _Clients_ in the navigation.

We need to create two clients: a confidential client for the gateway, and a public client for the frontend.

#### Gateway client

Click _Create client_, leave type on _OpenID Connect_, set the client id to something like `contentgrid-app-gateway-acme-dev`. Click Next.

Set _Client authentication_ to `On`, _Authorization_ to `Off`. For _Authentication flow_, check the box for _Standard flow_ and no others. Click Next.

For _Home URL_, enter the URL at which the ContentGrid API can be accessed for this setup. This should match the Ingress that points to the Gateway that it will forward to the API pod. It should look something like `https://cg-api.dev.acme.net/`. Include the https and the trailing slash.

For _Valid redirect URIs_, enter the same url, but with an asterisk appended. For instance `https://cg-api.dev.acme.net/*`.

For both _Valid post logout redirect URIs_ and _Web origins_, enter a plus (`+`) sign. This reuses the value from _Valid redirect URIs_. Click Save.

Go to the Credentials tab. Copy the _Client Secret_ and note it down for a later step (Kubernetes).

#### Webapp client

Go back to _Clients_ and click _Create client_ again. Leave type on _OpenID Connect_, set the client id to something like `contentgrid-webapp-acme-dev`. Click Next.

Set _Client authentication_ and _Authorization_ to `Off`. For _Authentication flow_, check the box for _Standard flow_ and no others. Click Next.

For _Home URL_, enter the URL at which the frontend (navigator by default) can be accessed for this setup. This should match the Ingress that points to the frontend/navigator's pod. It should look something like `https://navigator.dev.acme.net/`. Include the https and the trailing slash.

For _Valid redirect URIs_, enter the same url, but with an asterisk appended. For instance `https://navigator.dev.acme.net/*`.

For both _Valid post logout redirect URIs_ and _Web origins_, enter a plus (`+`) sign. This reuses the value from _Valid redirect URIs_. Click Save.

### Token Mapper

Go to _Client Scopes_ in the navigation, click _Create client scope_, name it `contentgrid`. Set the following:
- **Type**:                                Default
- **Display on consent screen**:           Off
- **Include in token scope**:              Off
- **Include in OpenID Provider Metadata**: On

Save it and go to the _Mappers_ tab.

Create a Token Mapper per attribute the group is meant to have, e.g. `contentgrid:admin`. Configure them as follows:
- **Mapper type**:      User Attribute
- **Name**:             contentgrid:admin
- **User Attribute**:   admin
- **Token Claim Name**: contentgrid:admin
- **Claim JSON Type**:  the actual type of the attribute you want to map, e.g. boolean
- **Add to**: _ID token_; _access token_; _userinfo_; _token introspection_
- Enable **Aggregate attribute values** if the attribute is multi-valued

Click _Groups_ in the navigation, add a new group to test whether the attributes come through. We'll just have a group _admin_ for this example. Create the group, then go to the _Attributes_ tab.

Click _Add attributes_ to add a new line. As key you fill in the User Attribute of the above mapper, in this case `admin`. As value you fill something in that matches the Claim JSON Type of the mapper, in this case it's a boolean, so we'll write `true`.

Click _Clients_ in the navigation again. Go to the webapp client, the _Client Scopes_ tab, Click _Add client scope_, check the box for `contentgrid` and click _Add_ > _Default_.

Then repeat this for the gateway client.

### Extensions

If you're going to use extensions/automations (such as the rendition system), [make sure to follow these steps on how to configure keycloak](https://github.com/xenit-eu/contentgrid-system-design/blob/main/runbooks/automation-system-registration.md#keycloak-1), and don't forget to fill out the Extension ID for the service account.

## Kubernetes

### Gateway issuer

The following steps all take place in the same namespace as where the apps are deployed.

Create a secret named something like `gateway-iam-acme` and the following contents (adjust for your env):

```yaml
data:
  contentgrid.idp.client-id: contentgrid-app-gateway-acme-dev
  contentgrid.idp.client-secret: (the client secret you noted in a previous step)
  contentgrid.idp.issuer-uri: (the url of the realm, e.g. "https://auth.dev.acme.net/realms/acme-dev")
metadata:
  labels:
    app.contentgrid.com/application-id: cg-acme-dev
    app.contentgrid.com/service-type: gateway
```

Optionally add a `contentgrid.idp.additional-issuer-uris` key if something else also creates tokens.

Remember when creating a kubernetes secret, the values should be Base64-encoded and you **don't** encode a trailing newline.

### Gateway with Webapp

Create a configmap named something like `cg-acme-gateway` and the following contents (adjust for your env):

```yaml
data:
  contentgrid.cors.origins: https://navigator.dev.acme.net
  contentgrid.routing.domains: cg-api.dev.acme.net
metadata:
  labels:
    app.contentgrid.com/application-id: cg-acme-dev
    app.contentgrid.com/service-type: gateway
```

Create a configmap named something like `cg-acme-webapp-iam`, and give it the following contents, adjusted for your environment:
```yaml
data:
  contentgrid.oidc.client: contentgrid-webapp-acme-dev
  contentgrid.oidc.issuer: https://auth.dev.acme.net/realms/acme-dev
  contentgrid.routing.domains: navigator.dev.acme.net
metadata:
  labels:
    app.contentgrid.com/application-id: cg-acme-dev
    app.contentgrid.com/service-type: webapp
```

