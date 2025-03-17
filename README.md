# ContentGrid Runtime Platform setup

Helm chart to set up a ContentGrid Runtime Platform.

## Upgrading helm chart

`helm -n contentgrid-system upgrade contentgrid-rtp ./contentgrid-rtp-helm`

## Debugging integration test with a remote JVM debugger

Steps to attach a debugger to one of the projects in the integration tests:

1. Set `<project>.debug=true`, e.g. if you want to debug pathfinder you need to set `pathdinder.debug=true`. This property adds `JAVA_TOOL_OPTIONS` environment variable for debugging and removes the probes.
2. Put a breakpoint on `K8sTestUtils.waitUntilDeploymentsReady()` in the `beforeAll` method and run the integration test in debug mode.
3. Use k9s or kubectl to forward port 5005 of the pod. You can find the `kubeconfig.yaml` in /tmp  with `ls -latr /tmp/kubeconfig*.yml` .
4. Open the project in IntelliJ and add a new `Run/Debug configuration` of type `Remote JVM Debug`, specify the chosen local port and give the configuration a name.
5. Debug with the new configuration.
6. (If the container has started successfully and need to try again: delete the pod, it will be automatically restarted, but you'll need to port-forward again)
