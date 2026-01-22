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
import type { WorkflowGraph } from '../types/workflow';

// Node type definitions for the palette
const nodeTemplates = [
  {
    type: 'event_match',
    category: 'Triggers',
    label: 'Event Match',
    icon: '🎯',
    description: 'Match events by name',
    defaultData: {
      event_name: '',
      predicates: [],
    },
  },
  {
    type: 'flush_window',
    category: 'Actions',
    label: 'Flush Window',
    icon: '💾',
    description: 'Flush events to storage',
    defaultData: {
      duration_ms: 5000,
    },
  },
  {
    type: 'any',
    category: 'Logic',
    label: 'Any (OR)',
    icon: '🔀',
    description: 'Match if any condition is true',
    defaultData: {
      operator: 'any',
    },
  },
  {
    type: 'all',
    category: 'Logic',
    label: 'All (AND)',
    icon: '🔗',
    description: 'Match if all conditions are true',
    defaultData: {
      operator: 'all',
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
      event_match: EventMatchNode,
      flush_window: FlushWindowNode,
      any: LogicNode,
      all: LogicNode,
      // Add other node types as needed
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
          {['Triggers', 'Logic', 'Actions'].map((category) => (
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
