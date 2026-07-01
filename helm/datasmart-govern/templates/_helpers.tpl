{{/*
DataSmart Govern Helm helper 模板。

这些 helper 的作用是把命名、标签、镜像拼接等重复规则集中起来，避免每个 Deployment 模板各写一遍。
这样做的收益：
1. 多服务 chart 中，资源命名必须稳定，否则升级、回滚和监控指标会出现漂移；
2. Kubernetes label 是服务发现、NetworkPolicy、Prometheus 抓取和故障排查的共同语言，必须统一；
3. 镜像仓库、tag 和企业私有 registry 经常在不同环境中变化，集中拼接能降低 values 覆盖成本。
*/}}

{{- define "datasmart-govern.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "datasmart-govern.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name (include "datasmart-govern.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "datasmart-govern.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" -}}
{{- end -}}

{{- define "datasmart-govern.labels" -}}
helm.sh/chart: {{ include "datasmart-govern.chart" . }}
app.kubernetes.io/name: {{ include "datasmart-govern.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: datasmart-govern
{{- end -}}

{{- define "datasmart-govern.selectorLabels" -}}
app.kubernetes.io/name: {{ include "datasmart-govern.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "datasmart-govern.serviceName" -}}
{{- $root := index . "root" -}}
{{- $name := index . "name" -}}
{{- printf "%s-%s" (include "datasmart-govern.fullname" $root) $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "datasmart-govern.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "datasmart-govern.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "datasmart-govern.image" -}}
{{- $root := index . "root" -}}
{{- $image := index . "image" -}}
{{- $repository := required "service image.repository is required" $image.repository -}}
{{- $tag := default $root.Values.global.imageTag $image.tag -}}
{{- $registry := default $root.Values.global.imageRegistry $image.registry -}}
{{- if $registry -}}
{{- printf "%s/%s:%s" (trimSuffix "/" $registry) $repository $tag -}}
{{- else -}}
{{- printf "%s:%s" $repository $tag -}}
{{- end -}}
{{- end -}}
