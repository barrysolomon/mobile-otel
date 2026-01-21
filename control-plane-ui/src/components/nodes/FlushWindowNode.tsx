import { Handle, Position } from 'reactflow';

interface FlushWindowNodeProps {
  data: {
    minutes: number;
    scope: 'session' | 'device';
    onChange?: (data: { minutes: number; scope: 'session' | 'device' }) => void;
  };
}

export function FlushWindowNode({ data }: FlushWindowNodeProps) {
  return (
    <div className="node node-action">
      <Handle type="target" position={Position.Left} />
      <div className="node-header">
        <div className="node-icon">📤</div>
        <div className="node-title">Flush Window</div>
      </div>
      <div className="node-body">
        <div className="node-field">
          <label>Minutes:</label>
          <input
            type="number"
            value={data.minutes}
            onChange={(e) =>
              data.onChange?.({ ...data, minutes: parseInt(e.target.value) })
            }
            min="1"
            max="60"
          />
        </div>
        <div className="node-field">
          <label>Scope:</label>
          <select
            value={data.scope}
            onChange={(e) =>
              data.onChange?.({
                ...data,
                scope: e.target.value as 'session' | 'device',
              })
            }
          >
            <option value="session">Session</option>
            <option value="device">Device</option>
          </select>
        </div>
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}
