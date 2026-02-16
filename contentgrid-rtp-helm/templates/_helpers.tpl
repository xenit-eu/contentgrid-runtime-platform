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
{{- if .Values.tolerations }}
tolerations:
{{ toYaml .Values.tolerations | indent 2 }}
{{- end }}
{{- if .Values.nodeSelector }}
nodeSelector:
{{ toYaml .Values.nodeSelector | indent 2 }}
{{- end }}
{{- end }}