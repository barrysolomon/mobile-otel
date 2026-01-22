import { useEffect, useState } from 'react';
import type { DeviceHeartbeat } from '../types/workflow';
import { gatewayAPI } from '../api/gateway';

interface DeviceWithCompliance extends DeviceHeartbeat {
  device_group?: string;
  expected_config_version?: string;
  config_compliant?: boolean;
}

export function DeviceMonitor() {
  const [devices, setDevices] = useState<DeviceWithCompliance[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [autoRefresh, setAutoRefresh] = useState(true);

  const fetchHeartbeats = async () => {
    try {
      setError(null);
      const result = await gatewayAPI.getHeartbeats(100);

      if (result.heartbeats && Array.isArray(result.heartbeats)) {
        // Parse last_triggers JSON string for each heartbeat
        const parsedDevices = result.heartbeats.map((hb: any) => {
          let triggers: string[] = [];
          try {
            if (hb.last_triggers) {
              triggers = JSON.parse(hb.last_triggers);
            }
          } catch (e) {
            console.warn('Failed to parse triggers for device:', hb.device_id);
          }

          return {
            ...hb,
            last_triggers: triggers,
            buffer_usage_mb: parseFloat(hb.buffer_usage_mb || 0),
          };
        });

        // Fetch device details to get group and expected config
        const devicesWithCompliance = await Promise.all(
          parsedDevices.map(async (device: DeviceWithCompliance) => {
            try {
              const deviceDetail = await gatewayAPI.getDevice(device.device_id);
              const deviceGroup = deviceDetail.device_group || 'default';

              // Get active config for the device's group
              let expectedVersion = null;
              let isCompliant = true;
              try {
                const activeConfig = await gatewayAPI.getActiveOTELConfig(deviceGroup);
                if (activeConfig) {
                  expectedVersion = activeConfig.version;
                  isCompliant = device.config_version === parseInt(expectedVersion);
                }
              } catch (e) {
                // No active config for group - that's ok
              }

              return {
                ...device,
                device_group: deviceGroup,
                expected_config_version: expectedVersion,
                config_compliant: isCompliant,
              };
            } catch (e) {
              // Device not registered - that's ok, just show heartbeat
              return device;
            }
          })
        );

        setDevices(devicesWithCompliance);
      } else {
        setDevices([]);
      }

      setLoading(false);
    } catch (err: any) {
      console.error('Failed to fetch heartbeats:', err);
      setError(err.message || 'Failed to fetch heartbeats');
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchHeartbeats();

    if (!autoRefresh) return;

    const interval = setInterval(fetchHeartbeats, 10000); // Poll every 10s

    return () => clearInterval(interval);
  }, [autoRefresh]);

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

  const getComplianceStats = () => {
    const compliant = devices.filter((d) => d.config_compliant === true).length;
    const nonCompliant = devices.filter((d) => d.config_compliant === false).length;
    const unknown = devices.filter((d) => d.config_compliant === undefined).length;
    return { compliant, nonCompliant, unknown };
  };

  const stats = getComplianceStats();

  if (loading) {
    return (
      <div className="device-monitor">
        <div className="loading-state">Loading heartbeats...</div>
      </div>
    );
  }

  return (
    <div className="device-monitor">
      <div className="monitor-header">
        <div>
          <h2>Live Device Heartbeats</h2>
          <p className="monitor-subtitle">Real-time device activity and configuration compliance</p>
        </div>
        <div className="monitor-controls">
          <label className="auto-refresh-toggle">
            <input
              type="checkbox"
              checked={autoRefresh}
              onChange={(e) => setAutoRefresh(e.target.checked)}
            />
            <span>Auto-refresh (10s)</span>
          </label>
          <button className="btn-refresh" onClick={fetchHeartbeats}>
            🔄 Refresh
          </button>
        </div>
      </div>

      {error && (
        <div className="monitor-error">
          ⚠️ {error}
        </div>
      )}

      <div className="monitor-stats">
        <div className="stat-card">
          <div className="stat-value">{devices.length}</div>
          <div className="stat-label">Active Devices</div>
        </div>
        <div className="stat-card stat-success">
          <div className="stat-value">{stats.compliant}</div>
          <div className="stat-label">Compliant</div>
        </div>
        <div className="stat-card stat-warning">
          <div className="stat-value">{stats.nonCompliant}</div>
          <div className="stat-label">Non-Compliant</div>
        </div>
        <div className="stat-card stat-info">
          <div className="stat-value">{stats.unknown}</div>
          <div className="stat-label">Unknown</div>
        </div>
      </div>

      <div className="device-list">
        {devices.map((device) => (
          <div key={device.device_id} className="device-card">
            <div className="device-status">
              <div className={`status-indicator ${getStatusColor(device.timestamp)}`} />
              <div className="device-id">{device.device_id}</div>
              {device.device_group && (
                <span className="device-group-badge">{device.device_group}</span>
              )}
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
                <span className="value">
                  v{device.config_version}
                  {device.expected_config_version && device.config_compliant !== undefined && (
                    <span className={`compliance-badge ${device.config_compliant ? 'compliant' : 'non-compliant'}`}>
                      {device.config_compliant ? '✓ Compliant' : `⚠ Expected v${device.expected_config_version}`}
                    </span>
                  )}
                </span>
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

      {devices.length === 0 && !loading && (
        <div className="empty-state">
          <p>No device heartbeats received</p>
          <p className="empty-hint">
            Devices will appear here when they send heartbeats to the gateway
          </p>
        </div>
      )}
    </div>
  );
}
