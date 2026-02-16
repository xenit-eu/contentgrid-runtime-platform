{{- define "rabbitmq_host" -}}
{{ .Values.rabbitmq.fullnameOverride }}.{{ default .Release.Namespace .Values.rabbitmq.namespaceOverride | trunc 63 | trimSuffix "-" }}.svc.cluster.local
{{- end -}}

{{ define "contentgrid.probes" }}
startupProbe:
    httpGet:
        path: /actuator/health/liveness
        port: {{ print . }}
    failureThreshold: 30
livenessProbe:
    httpGet:
        path: /actuator/health/liveness
        port: {{ print . }}
readinessProbe:
    httpGet:
        path: /actuator/health/readiness
        port: {{ print . }}
{{ end }}

{{- define "nodeSelection" -}}
{{- if and .Values.pods .Values.pods.tolerations }}
tolerations:
{{ toYaml .Values.pods.tolerations | indent 2 }}
{{- end }}
{{- if and .Values.pods .Values.pods.nodeSelector }}
nodeSelector:
{{ toYaml .Values.pods.nodeSelector | indent 2 }}
{{- end }}
{{- end }}