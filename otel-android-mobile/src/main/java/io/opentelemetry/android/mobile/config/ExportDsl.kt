// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.config

@MobileOtelDslMarker
class ExportDsl {
    var endpoint: String? = null

    /**
     * OTLP wire protocol. Defaults to [OtlpProtocol.HTTP_PROTOBUF] to match
     * [MobileConfig]. Set [OtlpProtocol.GRPC] when the endpoint is a gRPC
     * collector port (typically `:4317`) — otherwise the SDK POSTs HTTP/protobuf
     * to a gRPC listener and every export fails. Without this field the DSL had
     * no way to select gRPC, so all DSL-configured apps were locked to HTTP.
     */
    var protocol: OtlpProtocol = OtlpProtocol.HTTP_PROTOBUF
    var mode: ExportMode = ExportMode.CONDITIONAL
    var headers: Map<String, String>? = null
    var timeoutSeconds: Long = 30
    var maxRetries: Int = 3
    var traceIntervalSeconds: Long = 30
    var metricIntervalSeconds: Long = 60
}
