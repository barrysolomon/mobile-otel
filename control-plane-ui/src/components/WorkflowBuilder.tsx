import { useCallback, useMemo } from 'react';
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
} from 'reactflow';
import 'reactflow/dist/style.css';

import { EventMatchNode } from './nodes/EventMatchNode';
import { FlushWindowNode } from './nodes/FlushWindowNode';
import { LogicNode } from './nodes/LogicNode';
import type { WorkflowGraph } from '../types/workflow';

interface WorkflowBuilderProps {
  workflow: WorkflowGraph;
  onChange: (workflow: WorkflowGraph) => void;
}

export function WorkflowBuilder({ workflow, onChange }: WorkflowBuilderProps) {
  const [nodes, setNodes, onNodesChange] = useNodesState(workflow.nodes as Node[]);
  const [edges, setEdges, onEdgesChange] = useEdgesState(
    workflow.edges.map((e) => ({ ...e, type: 'smoothstep' }))
  );

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

  return (
    <div className="workflow-builder">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChangeHandler}
        onEdgesChange={onEdgesChangeHandler}
        onConnect={onConnect}
        nodeTypes={nodeTypes}
        fitView
      >
        <Background />
        <Controls />
        <MiniMap />
      </ReactFlow>
    </div>
  );
}
