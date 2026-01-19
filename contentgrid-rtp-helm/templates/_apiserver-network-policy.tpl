{{/*
  contentgrid.apiserver-network-policy
  parameters:
    rootScope: should be '$'
    annotations: annotations for the NetworkPolicy, optional
    policyLabels: labels for the NetworkPolicy, optional
    selectorLabels: labels for the selector used in the NetworkPolicy
*/}}
{{- define "contentgrid.apiserver-network-policy" }}
{{- if .rootScope.Capabilities.APIVersions.Has "cilium.io/v2" }}
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
{{- else }}
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
{{- end }}
metadata:
  name: {{ .name }}
{{- if hasKey . "annotations" }}
  annotations:
    {{- .annotations | toYaml | nindent 4 }}
{{- end }}
{{- if hasKey . "policyLabels" }}
  labels:
    {{- .policyLabels | toYaml | nindent 4 }}
{{- end }}
spec:
{{- if .rootScope.Capabilities.APIVersions.Has "cilium.io/v2" }}
  endpointSelector:
    matchLabels:
      {{- .selectorLabels | toYaml | nindent 6 }}
  egress:
    - toEntities:
        - kube-apiserver
{{- else }}
  policyTypes:
    - Egress
  podSelector:
    matchLabels:
      {{- .selectorLabels | toYaml | nindent 6 }}
  egress:
    - to:
      - ipBlock:
          cidr: {{ .rootScope.Values.apiserver.cidr }}
      ports:
      - protocol: TCP
        port: 6443
{{- end }}
{{- end }}
