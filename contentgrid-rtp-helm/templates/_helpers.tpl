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

{{/*
Renders a container `resources:` block from a component's `resourceRequests` /
`resourceLimits` values. Either side is optional, so e.g. a memory limit without a
CPU limit is supported. Fields are rendered with `toYaml`, so any standard
resource quantity (cpu, memory, ephemeral-storage, ...) can be supplied via values.

Usage: {{ include "contentgrid.resources" (dict "requests" .Values.<comp>.resourceRequests "limits" .Values.<comp>.resourceLimits) | nindent <N> }}
*/}}
{{- define "contentgrid.resources" -}}
{{- if or .requests .limits -}}
resources:
{{- with .requests }}
  requests:
{{- toYaml . | nindent 4 }}
{{- end }}
{{- with .limits }}
  limits:
{{- toYaml . | nindent 4 }}
{{- end }}
{{- end -}}
{{- end -}}