# ContentGrid Runtime Platform Compose setup

This setup allows running one or more ContentGrid applications on a docker-compose based Runtime Platform.

Note that this is an _example setup_ that can be used for evaluation and development, it is not a production-ready setup.

## Setup

1. Start all services with `./compose.sh up -d`. (This runs `docker compose up -d` with all compose files)
2. You can now access the services:
     - Navigator is running on [http://localhost:8085](http://localhost:8085); you can log in with username `watson`, password `watson`.
     - The application API is running on [http://localhost:8080](http://localhost:8080), it should redirect you to the HAL explorer
     - Keycloak is available on [http://172.17.0.1:8082](http://172.17.0.1:8082), you can log in with username `admin`, password `admin`.
     - Tokenmonger is available on [http://172.17.0.1:8081](http://172.17.0.1:8081), which you can use for extension development

### Networking

#### Hostname

The application needs to resolve URIs from both the users' browser and different containers.

Linux-based development environments can make use of the `docker0` network bridge and use `172.17.0.1` as the host, which is accessible from both the host system and the containers.

This is not relevant for non-dev environments, as you typically use a public DNS name there.

#### OSX

Given the [current limitations](https://docs.docker.com/desktop/features/networking/networking-how-tos/#connect-a-container-to-a-service-on-the-host) in the Docker Desktop for Mac networking stack, there are adaptations necessary.

> Because of the way networking is implemented in Docker Desktop for Mac, you cannot see a docker0 interface on the host. This interface is actually within the virtual machine.

The workaround uses two features specific for Docker Desktop for Mac:
- containers on OSX automatically resolve `host.docker.internal` to the internal docker VM
- all ports exposed on the internal VM are also mapped to the OSX host system

That means that if we make `host.docker.internal` resolvable on the host system to 127.0.0.1, both the users' browser and containers will correctly resolve http://host.docker.internal:<port>.

##### Steps

1. Add `host.docker.internal` to your hosts file (this will ask for your password to edit _/etc/hosts_):

```
echo "127.0.0.1 host.docker.internal" | sudo tee -a /etc/hosts`
```

2. Verify you can now ping `host.docker.internal`:

```
$ ping -c 3 host.docker.internal
PING host.docker.internal (127.0.0.1) 56(84) bytes of data.
64 bytes from localhost (127.0.0.1): icmp_seq=1 ttl=64 time=0.048 ms
64 bytes from localhost (127.0.0.1): icmp_seq=2 ttl=64 time=0.055 ms
64 bytes from localhost (127.0.0.1): icmp_seq=3 ttl=64 time=0.054 ms
```

3. Set the the env variable `DOCKER_HOST_IP` to `host.docker.internal` when running the startup script:

```
DOCKER_HOST_IP=host.docker.internal ./compose.sh up -d
```

The navigator should now be available in your browser at [http://host.docker.internal:8085/](http://host.docker.internal:8085/)


## Structure

 * `bootstrap/<service-name>`: Configuration to automatically set up a specific service (mostly generating crypto keys and provisioning sample data). This can also easily be set up manually for production, but that's tedious for a development setup.
 * `config/<service-name>`: Runtime configuration for a specific service. This has to be customized to a specific environment
    * `config/app/application.yml`: Spring configuration for the application
    * `config/gateway/app.yml`: Spring configuration for the gateway to configure the application
 * `docker-compose.*.yml`: docker-compose file for a specific slice of functionality

## Deploying a specific application

There are currently 2 scenarios that can be:

 1. You have a docker image for an application
    1. Edit `docker-compose.app.yml` to use the application image for the `app-api` service
    2. Remove everything except `application.yml` from `config/app`
    3. Extract the `/app/resources/rego/policy.rego` file from the docker image and place it in `config/app/rego/policy.rego`.
 2. You have the configuration zip for an application
    1. Unpack the configuration zip into `config/app`

As a final step, you need to edit the `config/app/rego/policy.rego` file to replace the placeholder with a valid OPA package name.
Also update `config/gateway/app.yml` to set the `policy-package` configuration there to the package name that you chose.
Failure to do this will result in OPA refusing to load the policy, and all requests to the application will be rejected with HTTP 403 Forbidden.

After deploying an updated application, the `gateway`, `opa` and `app-api` services need to be restarted so they pick up the new configuration.

## Production-ready setup

These docker-compose files are not suitable for production as-is, but can be taken as a base.

For a production setup, take care of the following:

 * Keycloak should be configured to run with an external database
 * Crypto keys should be generated *once* and mounted from the filesystem
 * Gateway should be configured with the correct keycloak application
 * The RabbitMQ users should be used for apps and slingshot (instead of currently the superuser)
 * Postgres & RabbitMQ should be configured with a volume for persistence
 * Default passwords should be changed to strong passwords
 * A reverse proxy needs to be put in front, to route based on host. Separate hostnames must be configured for:
     - keycloak
     - tokenmonger
     - app-api
     - navigator
 * Remove the test-webhook-receiver service
