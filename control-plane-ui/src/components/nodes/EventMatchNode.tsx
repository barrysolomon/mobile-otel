import { Handle, Position } from 'reactflow';
import type { Predicate } from '../../types/workflow';

interface EventMatchNodeProps {
  data: {
    eventName: string;
    predicates: Predicate[];
    onChange?: (data: { eventName: string; predicates: Predicate[] }) => void;
  };
}

export function EventMatchNode({ data }: EventMatchNodeProps) {
  return (
    <div className="node node-trigger">
      <div className="node-header">
        <div className="node-icon">🎯</div>
        <div className="node-title">Event Match</div>
      </div>
      <div className="node-body">
        <div className="node-field">
          <label>Event Name:</label>
          <input
            type="text"
            value={data.eventName}
            onChange={(e) =>
              data.onChange?.({ ...data, eventName: e.target.value })
            }
            placeholder="e.g., ui.freeze"
          />
        </div>
        {data.predicates.length > 0 && (
          <div className="node-field">
            <label>Conditions:</label>
            <div className="node-predicates">
              {data.predicates.map((pred, idx) => (
                <div key={idx} className="predicate">
                  {pred.attr} {pred.op} {String(pred.value)}
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}
