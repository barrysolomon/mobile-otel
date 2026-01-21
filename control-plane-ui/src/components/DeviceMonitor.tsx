import { useEffect, useState } from 'react';
import type { DeviceHeartbeat } from '../types/workflow';

// Mock data for demo - in production, this would come from Gateway API
const mockDevices: DeviceHeartbeat[] = [
  {
    device_id: 'dev-android-001',
    app_id: 'mobile-observability-demo',
    session_id: 'sess-abc-123',
    buffer_usage_mb: 2.5,
    last_triggers: ['ui-freeze', 'network-error-spike'],
    config_version: 1,
    timestamp: new Date().toISOString(),
  },
  {
    device_id: 'dev-android-002',
    app_id: 'mobile-observability-demo',
    session_id: 'sess-def-456',
    buffer_usage_mb: 1.2,
    last_triggers: ['crash-recovery'],
    config_version: 1,
    timestamp: new Date(Date.now() - 60000).toISOString(),
  },
];

export function DeviceMonitor() {
  const [devices, setDevices] = useState<DeviceHeartbeat[]>(mockDevices);

  useEffect(() => {
    // In production, poll Gateway /status endpoint or use WebSocket
    const interval = setInterval(() => {
      // Refresh device data
      setDevices([...mockDevices]);
    }, 30000);

    return () => clearInterval(interval);
  }, []);

  const getTimeSince = (timestamp: string) => {
    const seconds = Math.floor(
      (Date.now() - new Date(timestamp).getTime()) / 1000
    );
    if (seconds < 60) return `${seconds}s ago`;
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
    return `${Math.floor(seconds / 3600)}h ago`;
  };

  const getStatusColor = (timestamp: string) => {
    const seconds = Math.floor(
      (Date.now() - new Date(timestamp).getTime()) / 1000
    );
    if (seconds < 60) return 'status-online';
    if (seconds < 300) return 'status-warning';
    return 'status-offline';
  };

  return (
    <div className="device-monitor">
      <div className="monitor-header">
        <h2>Connected Devices</h2>
        <div className="device-count">{devices.length} active</div>
      </div>

      <div className="device-list">
        {devices.map((device) => (
          <div key={device.device_id} className="device-card">
            <div className="device-status">
              <div className={`status-indicator ${getStatusColor(device.timestamp)}`} />
              <div className="device-id">{device.device_id}</div>
            </div>

            <div className="device-details">
              <div className="detail-row">
                <span className="label">Session:</span>
                <span className="value">{device.session_id}</span>
              </div>
              <div className="detail-row">
                <span className="label">Buffer:</span>
                <span className="value">{device.buffer_usage_mb.toFixed(2)} MB</span>
              </div>
              <div className="detail-row">
                <span className="label">Config:</span>
                <span className="value">v{device.config_version}</span>
              </div>
              <div className="detail-row">
                <span className="label">Last Seen:</span>
                <span className="value">{getTimeSince(device.timestamp)}</span>
              </div>
            </div>

            {device.last_triggers.length > 0 && (
              <div className="device-triggers">
                <div className="label">Recent Triggers:</div>
                <div className="trigger-chips">
                  {device.last_triggers.map((trigger, idx) => (
                    <span key={idx} className="trigger-chip">
                      {trigger}
                    </span>
                  ))}
                </div>
              </div>
            )}
          </div>
        ))}
      </div>

      {devices.length === 0 && (
        <div className="empty-state">
          <p>No devices connected</p>
          <p className="empty-hint">
            Devices will appear here when they send heartbeats
          </p>
        </div>
      )}
    </div>
  );
}
