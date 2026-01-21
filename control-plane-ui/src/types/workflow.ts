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
  | EventMatchNode
  | HttpErrorMatchNode
  | CrashMarkerNode
  | AnyNode
  | AllNode
  | AnnotateTriggerNode
  | FlushWindowNode
  | SetSamplingNode;

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
