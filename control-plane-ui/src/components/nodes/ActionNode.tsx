import { Handle, Position } from 'reactflow';

interface ActionNodeProps {
  data: {
    label?: string;
    icon?: string;
    description?: string;
    [key: string]: any;
  };
  type?: string;
}

const actionIcons: Record<string, string> = {
  flush_window: '📤',
  set_sampling: '🎲',
  annotate_trigger: '🏷️',
  send_alert: '🚨',
  adjust_config: '⚙️',
};

const actionLabels: Record<string, string> = {
  flush_window: 'Flush Window',
  set_sampling: 'Set Sampling',
  annotate_trigger: 'Annotate Event',
  send_alert: 'Send Alert',
  adjust_config: 'Adjust Config',
};

export function ActionNode({ data, type }: ActionNodeProps) {
  const icon = data.icon || (type && actionIcons[type]) || '⚡';
  const label = data.label || (type && actionLabels[type]) || 'Action';

  // Render fields based on data keys
  const fields = Object.entries(data)
    .filter(([key]) => !['label', 'icon', 'description', 'onChange'].includes(key))
    .filter(([_, value]) => value !== undefined && value !== '' && value !== null);

  return (
    <div className="node node-action">
      <Handle type="target" position={Position.Left} />
      <div className="node-header">
        <div className="node-icon">{icon}</div>
        <div className="node-title">{label}</div>
      </div>
      <div className="node-body">
        {fields.length > 0 && (
          <div className="node-fields">
            {fields.map(([key, value]) => (
              <div key={key} className="node-field-display">
                <label>{formatFieldName(key)}:</label>
                <span>{formatFieldValue(value)}</span>
              </div>
            ))}
          </div>
        )}
        {fields.length === 0 && (
          <div className="node-empty-state">Configure action...</div>
        )}
      </div>
    </div>
  );
}

function formatFieldName(name: string): string {
  return name
    .replace(/([A-Z])/g, ' $1')
    .replace(/_/g, ' ')
    .replace(/^./, (str) => str.toUpperCase())
    .trim();
}

function formatFieldValue(value: any): string {
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  if (Array.isArray(value)) return value.join(', ');
  if (typeof value === 'object' && value !== null) return JSON.stringify(value);
  return String(value);
}
