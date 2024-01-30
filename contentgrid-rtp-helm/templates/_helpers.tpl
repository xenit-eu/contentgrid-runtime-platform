{{- define "rabbitmq_host" -}}
{{ .Values.rabbitmq.fullnameOverride }}.{{ default .Release.Namespace .Values.rabbitmq.namespaceOverride | trunc 63 | trimSuffix "-" }}.svc.cluster.local
{{- end -}}
