import { Handle, Position } from 'reactflow';

interface TriggerNodeProps {
  data: {
    label?: string;
    icon?: string;
    description?: string;
    [key: string]: any;
  };
  type?: string;
}

const triggerIcons: Record<string, string> = {
  event_match: '🎯',
  log_severity_match: '📋',
  metric_threshold: '📊',
  ui_freeze: '❄️',
  slow_operation: '🐌',
  frame_drop: '🎬',
  http_error_match: '🌐',
  network_loss: '📡',
  slow_request: '⏱️',
  low_memory: '💾',
  battery_drain: '🔋',
  thermal_throttling: '🌡️',
  storage_low: '💿',
  crash_marker: '💥',
  exception_pattern: '⚠️',
  predictive_risk: '🔮',
};

const triggerLabels: Record<string, string> = {
  event_match: 'Event Match',
  log_severity_match: 'Log Severity',
  metric_threshold: 'Metric Threshold',
  ui_freeze: 'UI Freeze',
  slow_operation: 'Slow Operation',
  frame_drop: 'Frame Drops',
  http_error_match: 'HTTP Error',
  network_loss: 'Network Loss',
  slow_request: 'Slow Request',
  low_memory: 'Low Memory',
  battery_drain: 'Battery Drain',
  thermal_throttling: 'Thermal Throttling',
  storage_low: 'Low Storage',
  crash_marker: 'Crash Detected',
  exception_pattern: 'Exception Pattern',
  predictive_risk: 'Predictive Risk',
};

export function TriggerNode({ data, type }: TriggerNodeProps) {
  const icon = data.icon || (type && triggerIcons[type]) || '🎯';
  const label = data.label || (type && triggerLabels[type]) || 'Trigger';

  // Render fields based on data keys
  const fields = Object.entries(data)
    .filter(([key]) => !['label', 'icon', 'description', 'onChange'].includes(key))
    .filter(([_, value]) => value !== undefined && value !== '' && value !== null);

  return (
    <div className="node node-trigger">
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
          <div className="node-empty-state">No configuration required</div>
        )}
      </div>
      <Handle type="source" position={Position.Right} />
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
  if (typeof value === 'object' && value !== null) return JSON.stringify(value);
  return String(value);
}
