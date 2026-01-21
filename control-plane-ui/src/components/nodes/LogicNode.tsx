import { Handle, Position } from 'reactflow';

interface LogicNodeProps {
  data: {
    type: 'any' | 'all';
  };
}

export function LogicNode({ data }: LogicNodeProps) {
  const isAny = data.type === 'any';

  return (
    <div className="node node-logic">
      <Handle type="target" position={Position.Left} />
      <div className="node-header">
        <div className="node-icon">{isAny ? '∨' : '∧'}</div>
        <div className="node-title">{isAny ? 'ANY (OR)' : 'ALL (AND)'}</div>
      </div>
      <div className="node-body">
        <div className="node-description">
          {isAny
            ? 'Triggers if any condition matches'
            : 'Triggers if all conditions match'}
        </div>
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}
