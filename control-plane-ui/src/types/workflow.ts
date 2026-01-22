// Graph format (for React Flow editing)
export interface WorkflowGraph {
  id: string;
  name: string;
  enabled: boolean;
  entryNodeId: string;
  nodes: GraphNode[];
  edges: GraphEdge[];
}

export interface GraphEdge {
  id: string;
  source: string;
  target: string;
}

export type GraphNode =
  // Event Triggers
  | EventMatchNode
  | LogSeverityMatchNode
  | MetricThresholdNode
  // Performance Triggers
  | UIFreezeNode
  | SlowOperationNode
  | FrameDropNode
  // Network Triggers
  | HttpErrorMatchNode
  | NetworkLossNode
  | SlowRequestNode
  // Device Health Triggers
  | LowMemoryNode
  | BatteryDrainNode
  | ThermalThrottlingNode
  | StorageLowNode
  // Crash/Error Triggers
  | CrashMarkerNode
  | ExceptionPatternNode
  // Predictive Triggers
  | PredictiveRiskNode
  // Logic Nodes
  | AnyNode
  | AllNode
  // Actions
  | AnnotateTriggerNode
  | FlushWindowNode
  | SetSamplingNode
  | SendAlertNode
  | AdjustConfigNode;

// Trigger Nodes
export interface EventMatchNode {
  id: string;
  type: 'event_match';
  position: { x: number; y: number };
  data: {
    eventName: string;
    predicates: Predicate[];
  };
}

export interface HttpErrorMatchNode {
  id: string;
  type: 'http_error_match';
  position: { x: number; y: number };
  data: {
    statusMin: number;
    routeContains?: string;
  };
}

export interface CrashMarkerNode {
  id: string;
  type: 'crash_marker';
  position: { x: number; y: number };
  data: Record<string, never>;
}

// Additional Event Triggers
export interface LogSeverityMatchNode {
  id: string;
  type: 'log_severity_match';
  position: { x: number; y: number };
  data: {
    minSeverity: 'DEBUG' | 'INFO' | 'WARN' | 'ERROR' | 'FATAL';
    bodyContains?: string;
  };
}

export interface MetricThresholdNode {
  id: string;
  type: 'metric_threshold';
  position: { x: number; y: number };
  data: {
    metricName: string;
    operator: '>' | '>=' | '<' | '<=';
    threshold: number;
  };
}

// Performance Triggers
export interface UIFreezeNode {
  id: string;
  type: 'ui_freeze';
  position: { x: number; y: number };
  data: {
    durationMs: number;
  };
}

export interface SlowOperationNode {
  id: string;
  type: 'slow_operation';
  position: { x: number; y: number };
  data: {
    operationName: string;
    thresholdMs: number;
  };
}

export interface FrameDropNode {
  id: string;
  type: 'frame_drop';
  position: { x: number; y: number };
  data: {
    droppedFrames: number;
    windowMs: number;
  };
}

// Network Triggers
export interface NetworkLossNode {
  id: string;
  type: 'network_loss';
  position: { x: number; y: number };
  data: {
    consecutiveFailures?: number;
  };
}

export interface SlowRequestNode {
  id: string;
  type: 'slow_request';
  position: { x: number; y: number };
  data: {
    thresholdMs: number;
    route?: string;
  };
}

// Device Health Triggers
export interface LowMemoryNode {
  id: string;
  type: 'low_memory';
  position: { x: number; y: number };
  data: {
    availableMb: number;
  };
}

export interface BatteryDrainNode {
  id: string;
  type: 'battery_drain';
  position: { x: number; y: number };
  data: {
    drainRatePercPerMin: number;
  };
}

export interface ThermalThrottlingNode {
  id: string;
  type: 'thermal_throttling';
  position: { x: number; y: number };
  data: {
    minLevel: 'LIGHT' | 'MODERATE' | 'SEVERE' | 'CRITICAL';
  };
}

export interface StorageLowNode {
  id: string;
  type: 'storage_low';
  position: { x: number; y: number };
  data: {
    availableMb: number;
  };
}

// Crash/Error Triggers
export interface ExceptionPatternNode {
  id: string;
  type: 'exception_pattern';
  position: { x: number; y: number };
  data: {
    exceptionType: string;
    messagePattern?: string;
  };
}

// Predictive Triggers
export interface PredictiveRiskNode {
  id: string;
  type: 'predictive_risk';
  position: { x: number; y: number };
  data: {
    riskType: 'crash' | 'network_loss' | 'performance_degradation' | 'battery_drain';
    minScore: number;
  };
}

// Logic Nodes
export interface AnyNode {
  id: string;
  type: 'any';
  position: { x: number; y: number };
  data: Record<string, never>;
}

export interface AllNode {
  id: string;
  type: 'all';
  position: { x: number; y: number };
  data: Record<string, never>;
}

// Action Nodes
export interface AnnotateTriggerNode {
  id: string;
  type: 'annotate_trigger';
  position: { x: number; y: number };
  data: {
    triggerId: string;
    reason: string;
  };
}

export interface FlushWindowNode {
  id: string;
  type: 'flush_window';
  position: { x: number; y: number };
  data: {
    minutes: number;
    scope: 'session' | 'device';
  };
}

export interface SetSamplingNode {
  id: string;
  type: 'set_sampling';
  position: { x: number; y: number };
  data: {
    rate: number;
    durationMinutes: number;
  };
}

export interface SendAlertNode {
  id: string;
  type: 'send_alert';
  position: { x: number; y: number };
  data: {
    severity: 'info' | 'warning' | 'critical';
    message: string;
    channels: ('email' | 'slack' | 'pagerduty')[];
  };
}

export interface AdjustConfigNode {
  id: string;
  type: 'adjust_config';
  position: { x: number; y: number };
  data: {
    parameter: string;
    value: string | number | boolean;
    durationMinutes?: number;
  };
}

export interface Predicate {
  attr: string;
  op: '==' | '!=' | '>' | '>=' | '<' | '<=' | 'contains' | 'regex';
  value: string | number | boolean;
}

// DSL format (for device execution)
export interface DSLConfig {
  version: number;
  limits: {
    diskMb: number;
    ramEvents: number;
    retentionHours: number;
  };
  workflows: DSLWorkflow[];
}

export interface DSLWorkflow {
  id: string;
  enabled: boolean;
  trigger: DSLTrigger;
  actions: DSLAction[];
}

export interface DSLTrigger {
  any?: DSLCondition[];
  all?: DSLCondition[];
}

export interface DSLCondition {
  event?: string;
  where?: Predicate[];
}

export type DSLAction =
  | {
      type: 'annotate_trigger';
      trigger_id: string;
      reason: string;
    }
  | {
      type: 'flush_window';
      minutes: number;
      scope: 'session' | 'device';
    }
  | {
      type: 'set_sampling';
      rate: number;
      duration_minutes: number;
    };

// Config version (from Gateway)
export interface ConfigVersion {
  version: number;
  graph_json: string;
  dsl_json: string;
  published_at: string;
  published_by: string;
  is_active: boolean;
}

// Device heartbeat
export interface DeviceHeartbeat {
  device_id: string;
  app_id: string;
  session_id: string;
  buffer_usage_mb: number;
  last_triggers: string[];
  config_version: number;
  timestamp: string;
}
