# ContentGrid Runtime Platform setup

Helm chart to set up a ContentGrid Runtime Platform.

## Upgrading helm chart

`helm -n contentgrid-system upgrade contentgrid-rtp ./contentgrid-rtp-helm`

## Debugging integration test with a remote JVM debugger

Steps to attach a debugger to one of the projects in the integration tests:

1. Set `<project>.debug=true`, e.g. if you want to debug pathfinder you need to set `pathdinder.debug=true`.
2. Put a breakpoint on `K8sTestUtils.waitUntilDeploymentsReady()` in the `beforeAll` method and run the integration test in debug mode.
3. Use k9s to connect with the kubernetes cluster with the kubeconfig file in the `/tmp` folder.
4. Navigate in k9s to the pod and enable port forwarding with `SHIFT+F`, Container Port should end with 5005 and choose a Local Port.
5. Open the project in IntelliJ and add a new `Run/Debug configuration` of type `Remote JVM Debug`, specify the chosen local port and give the configuration a name.
6. Debug with the new configuration.
7. (If the container has started successfully and need to try again: delete the pod in k9s, it will be automatically restarted, but you'll need to port-forward again)
