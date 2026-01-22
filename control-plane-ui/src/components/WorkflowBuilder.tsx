import { useCallback, useMemo, useState } from 'react';
import ReactFlow, {
  Background,
  Controls,
  MiniMap,
  addEdge,
  useNodesState,
  useEdgesState,
  type Connection,
  type Edge,
  type Node,
  type NodeTypes,
  type OnDragOver,
} from 'reactflow';
import 'reactflow/dist/style.css';

import { EventMatchNode } from './nodes/EventMatchNode';
import { FlushWindowNode } from './nodes/FlushWindowNode';
import { LogicNode } from './nodes/LogicNode';
import { TriggerNode } from './nodes/TriggerNode';
import { ActionNode } from './nodes/ActionNode';
import type { WorkflowGraph } from '../types/workflow';

// Node type definitions for the palette
const nodeTemplates = [
  // Event Triggers
  {
    type: 'event_match',
    category: 'Event Triggers',
    label: 'Event Match',
    icon: '🎯',
    description: 'Match events by name',
    defaultData: {
      eventName: '',
      predicates: [],
    },
  },
  {
    type: 'log_severity_match',
    category: 'Event Triggers',
    label: 'Log Severity',
    icon: '📋',
    description: 'Match logs by severity level',
    defaultData: {
      minSeverity: 'ERROR',
      bodyContains: '',
    },
  },
  {
    type: 'metric_threshold',
    category: 'Event Triggers',
    label: 'Metric Threshold',
    icon: '📊',
    description: 'Trigger on metric threshold',
    defaultData: {
      metricName: '',
      operator: '>',
      threshold: 0,
    },
  },

  // Performance Triggers
  {
    type: 'ui_freeze',
    category: 'Performance',
    label: 'UI Freeze',
    icon: '❄️',
    description: 'Detect UI freezes/ANRs',
    defaultData: {
      durationMs: 2000,
    },
  },
  {
    type: 'slow_operation',
    category: 'Performance',
    label: 'Slow Operation',
    icon: '🐌',
    description: 'Detect slow operations',
    defaultData: {
      operationName: '',
      thresholdMs: 1000,
    },
  },
  {
    type: 'frame_drop',
    category: 'Performance',
    label: 'Frame Drops',
    icon: '🎬',
    description: 'Detect dropped frames',
    defaultData: {
      droppedFrames: 30,
      windowMs: 1000,
    },
  },

  // Network Triggers
  {
    type: 'http_error_match',
    category: 'Network',
    label: 'HTTP Error',
    icon: '🌐',
    description: 'Match HTTP error codes',
    defaultData: {
      statusMin: 500,
      routeContains: '',
    },
  },
  {
    type: 'network_loss',
    category: 'Network',
    label: 'Network Loss',
    icon: '📡',
    description: 'Detect network disconnection',
    defaultData: {
      consecutiveFailures: 3,
    },
  },
  {
    type: 'slow_request',
    category: 'Network',
    label: 'Slow Request',
    icon: '⏱️',
    description: 'Detect slow HTTP requests',
    defaultData: {
      thresholdMs: 3000,
      route: '',
    },
  },

  // Device Health Triggers
  {
    type: 'low_memory',
    category: 'Device Health',
    label: 'Low Memory',
    icon: '💾',
    description: 'Detect low memory conditions',
    defaultData: {
      availableMb: 50,
    },
  },
  {
    type: 'battery_drain',
    category: 'Device Health',
    label: 'Battery Drain',
    icon: '🔋',
    description: 'Detect rapid battery drain',
    defaultData: {
      drainRatePercPerMin: 1.0,
    },
  },
  {
    type: 'thermal_throttling',
    category: 'Device Health',
    label: 'Thermal Throttling',
    icon: '🌡️',
    description: 'Detect device overheating',
    defaultData: {
      minLevel: 'MODERATE',
    },
  },
  {
    type: 'storage_low',
    category: 'Device Health',
    label: 'Low Storage',
    icon: '💿',
    description: 'Detect low storage space',
    defaultData: {
      availableMb: 100,
    },
  },

  // Crash/Error Triggers
  {
    type: 'crash_marker',
    category: 'Crash/Error',
    label: 'Crash Detected',
    icon: '💥',
    description: 'Trigger on app crash',
    defaultData: {},
  },
  {
    type: 'exception_pattern',
    category: 'Crash/Error',
    label: 'Exception Pattern',
    icon: '⚠️',
    description: 'Match exception types',
    defaultData: {
      exceptionType: '',
      messagePattern: '',
    },
  },

  // Predictive Triggers
  {
    type: 'predictive_risk',
    category: 'Predictive',
    label: 'Predictive Risk',
    icon: '🔮',
    description: 'ML-based risk prediction',
    defaultData: {
      riskType: 'crash',
      minScore: 0.7,
    },
  },

  // Logic Nodes
  {
    type: 'any',
    category: 'Logic',
    label: 'Any (OR)',
    icon: '🔀',
    description: 'Match if any condition is true',
    defaultData: {},
  },
  {
    type: 'all',
    category: 'Logic',
    label: 'All (AND)',
    icon: '🔗',
    description: 'Match if all conditions are true',
    defaultData: {},
  },

  // Actions
  {
    type: 'flush_window',
    category: 'Actions',
    label: 'Flush Window',
    icon: '📤',
    description: 'Flush buffered events',
    defaultData: {
      minutes: 2,
      scope: 'session',
    },
  },
  {
    type: 'set_sampling',
    category: 'Actions',
    label: 'Set Sampling',
    icon: '🎲',
    description: 'Adjust sampling rate',
    defaultData: {
      rate: 100,
      durationMinutes: 10,
    },
  },
  {
    type: 'annotate_trigger',
    category: 'Actions',
    label: 'Annotate Event',
    icon: '🏷️',
    description: 'Add trigger annotation',
    defaultData: {
      triggerId: '',
      reason: '',
    },
  },
  {
    type: 'send_alert',
    category: 'Actions',
    label: 'Send Alert',
    icon: '🚨',
    description: 'Send notification alert',
    defaultData: {
      severity: 'warning',
      message: '',
      channels: ['email'],
    },
  },
  {
    type: 'adjust_config',
    category: 'Actions',
    label: 'Adjust Config',
    icon: '⚙️',
    description: 'Change runtime config',
    defaultData: {
      parameter: '',
      value: '',
      durationMinutes: 0,
    },
  },
];

interface WorkflowBuilderProps {
  workflow: WorkflowGraph;
  onChange: (workflow: WorkflowGraph) => void;
}

export function WorkflowBuilder({ workflow, onChange }: WorkflowBuilderProps) {
  const [nodes, setNodes, onNodesChange] = useNodesState(workflow.nodes as Node[]);
  const [edges, setEdges, onEdgesChange] = useEdgesState(
    workflow.edges.map((e) => ({ ...e, type: 'smoothstep' }))
  );
  const [nodeIdCounter, setNodeIdCounter] = useState(1000);

  const nodeTypes: NodeTypes = useMemo(
    () => ({
      // Event Triggers
      event_match: EventMatchNode,
      log_severity_match: TriggerNode,
      metric_threshold: TriggerNode,

      // Performance Triggers
      ui_freeze: TriggerNode,
      slow_operation: TriggerNode,
      frame_drop: TriggerNode,

      // Network Triggers
      http_error_match: TriggerNode,
      network_loss: TriggerNode,
      slow_request: TriggerNode,

      // Device Health Triggers
      low_memory: TriggerNode,
      battery_drain: TriggerNode,
      thermal_throttling: TriggerNode,
      storage_low: TriggerNode,

      // Crash/Error Triggers
      crash_marker: TriggerNode,
      exception_pattern: TriggerNode,

      // Predictive Triggers
      predictive_risk: TriggerNode,

      // Logic Nodes
      any: LogicNode,
      all: LogicNode,

      // Actions
      flush_window: FlushWindowNode,
      set_sampling: ActionNode,
      annotate_trigger: ActionNode,
      send_alert: ActionNode,
      adjust_config: ActionNode,
    }),
    []
  );

  const onConnect = useCallback(
    (params: Connection) => {
      setEdges((eds) => addEdge({ ...params, type: 'smoothstep' }, eds));

      // Update workflow
      onChange({
        ...workflow,
        edges: [...edges, { id: `${params.source}-${params.target}`, source: params.source!, target: params.target! }],
      });
    },
    [edges, onChange, setEdges, workflow]
  );

  const onNodesChangeHandler = useCallback(
    (changes: any) => {
      onNodesChange(changes);
      // Sync back to workflow
      onChange({
        ...workflow,
        nodes: nodes as any,
      });
    },
    [nodes, onChange, onNodesChange, workflow]
  );

  const onEdgesChangeHandler = useCallback(
    (changes: any) => {
      onEdgesChange(changes);
      // Sync back to workflow
      onChange({
        ...workflow,
        edges: edges.map((e) => ({ id: e.id, source: e.source, target: e.target })),
      });
    },
    [edges, onChange, onEdgesChange, workflow]
  );

  const onDragStart = (event: React.DragEvent, nodeTemplate: typeof nodeTemplates[0]) => {
    event.dataTransfer.setData('application/reactflow', JSON.stringify(nodeTemplate));
    event.dataTransfer.effectAllowed = 'move';
  };

  const onDragOver: OnDragOver = useCallback((event) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  }, []);

  const onDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault();

      const reactFlowBounds = event.currentTarget.getBoundingClientRect();
      const templateData = event.dataTransfer.getData('application/reactflow');

      if (!templateData) return;

      const template = JSON.parse(templateData);

      // Calculate position relative to the React Flow canvas
      const position = {
        x: event.clientX - reactFlowBounds.left - 100,
        y: event.clientY - reactFlowBounds.top - 50,
      };

      const newNodeId = `node_${nodeIdCounter}`;
      setNodeIdCounter((prev) => prev + 1);

      const newNode: Node = {
        id: newNodeId,
        type: template.type,
        position,
        data: { ...template.defaultData },
      };

      setNodes((nds) => [...nds, newNode]);
      onChange({
        ...workflow,
        nodes: [...nodes, newNode] as any,
      });
    },
    [nodeIdCounter, nodes, onChange, setNodes, workflow]
  );

  return (
    <div className="workflow-builder-container">
      <div className="node-palette">
        <h3>Node Palette</h3>
        <div className="palette-sections">
          {[
            'Event Triggers',
            'Performance',
            'Network',
            'Device Health',
            'Crash/Error',
            'Predictive',
            'Logic',
            'Actions',
          ].map((category) => (
            <div key={category} className="palette-section">
              <div className="palette-category">{category}</div>
              {nodeTemplates
                .filter((t) => t.category === category)
                .map((template) => (
                  <div
                    key={template.type}
                    className="palette-node"
                    draggable
                    onDragStart={(e) => onDragStart(e, template)}
                  >
                    <span className="palette-node-icon">{template.icon}</span>
                    <div className="palette-node-info">
                      <div className="palette-node-label">{template.label}</div>
                      <div className="palette-node-description">{template.description}</div>
                    </div>
                  </div>
                ))}
            </div>
          ))}
        </div>
      </div>
      <div className="workflow-canvas">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChangeHandler}
          onEdgesChange={onEdgesChangeHandler}
          onConnect={onConnect}
          onDragOver={onDragOver}
          onDrop={onDrop}
          nodeTypes={nodeTypes}
          fitView
        >
          <Background />
          <Controls />
          <MiniMap />
        </ReactFlow>
      </div>
    </div>
  );
}
