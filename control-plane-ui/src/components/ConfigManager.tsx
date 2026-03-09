import React, { useState, useEffect } from 'react';
import { gatewayAPI } from '../api/gateway';

interface OTELConfig {
  protocol: 'grpc' | 'http';
  collector_endpoint: string;
  auth_token: string;
  dataset: string;
  ram_buffer_size: number;
  disk_buffer_mb: number;
  disk_buffer_ttl_hours: number;
  export_timeout_seconds: number;
  max_export_retries: number;
}

interface SavedConfiguration {
  id: number;
  device_group: string;
  version: string;
  protocol: string;
  collector_endpoint: string;
  auth_token: string;
  dataset: string;
  ram_buffer_size: number;
  disk_buffer_mb: number;
  disk_buffer_ttl_hours: number;
  export_timeout_seconds: number;
  max_export_retries: number;
  environment_vars: string;
  feature_flags: string;
  created_at: string;
  created_by: string;
  is_active: boolean;
}

export const ConfigManager: React.FC = () => {
  const [deviceGroup, setDeviceGroup] = useState('default');
  const [deviceGroups, setDeviceGroups] = useState<any[]>([]);
  const [otelConfig, setOtelConfig] = useState<OTELConfig>({
    protocol: 'grpc',
    collector_endpoint: 'https://your-collector-endpoint:4317',
    auth_token: '',
    dataset: '',
    ram_buffer_size: 5000,
    disk_buffer_mb: 50,
    disk_buffer_ttl_hours: 24,
    export_timeout_seconds: 30,
    max_export_retries: 3,
  });

  const [envVars, setEnvVars] = useState<Record<string, string>>({});
  const [featureFlags, setFeatureFlags] = useState<Record<string, boolean>>({});
  const [savedConfigs, setSavedConfigs] = useState<SavedConfiguration[]>([]);
  const [activeConfig, setActiveConfig] = useState<SavedConfiguration | null>(null);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [rolloutStatus, setRolloutStatus] = useState<any[]>([]);

  useEffect(() => {
    fetchDeviceGroups();
    fetchSavedConfigs();
    fetchRolloutStatus();
  }, []);

  useEffect(() => {
    fetchActiveConfig();
    fetchSavedConfigs();
  }, [deviceGroup]);

  useEffect(() => {
    // Auto-refresh rollout status every 15 seconds
    const interval = setInterval(fetchRolloutStatus, 15000);
    return () => clearInterval(interval);
  }, []);

  const fetchDeviceGroups = async () => {
    try {
      const result = await gatewayAPI.listDeviceGroups();
      setDeviceGroups(result.groups || []);
    } catch (err) {
      console.error('Failed to fetch device groups:', err);
    }
  };

  const fetchRolloutStatus = async () => {
    try {
      const result = await gatewayAPI.getConfigRolloutStatus();
      setRolloutStatus(result.rollout_statuses || []);
    } catch (err) {
      console.error('Failed to fetch rollout status:', err);
    }
  };

  const fetchSavedConfigs = async () => {
    try {
      const result = await gatewayAPI.listOTELConfigs(deviceGroup);
      setSavedConfigs(result.configurations || []);
    } catch (err) {
      console.error('Failed to fetch configurations:', err);
    }
  };

  const fetchActiveConfig = async () => {
    try {
      const config = await gatewayAPI.getActiveOTELConfig(deviceGroup);
      if (config) {
        setActiveConfig(config);
        // Load active config into form
        setOtelConfig({
          protocol: config.protocol as 'grpc' | 'http',
          collector_endpoint: config.collector_endpoint,
          auth_token: config.auth_token || '',
          dataset: config.dataset || '',
          ram_buffer_size: config.ram_buffer_size,
          disk_buffer_mb: config.disk_buffer_mb,
          disk_buffer_ttl_hours: config.disk_buffer_ttl_hours,
          export_timeout_seconds: config.export_timeout_seconds,
          max_export_retries: config.max_export_retries,
        });

        // Parse environment vars and feature flags
        try {
          if (config.environment_vars) {
            setEnvVars(JSON.parse(config.environment_vars));
          }
        } catch (e) {
          setEnvVars({});
        }

        try {
          if (config.feature_flags) {
            setFeatureFlags(JSON.parse(config.feature_flags));
          }
        } catch (e) {
          setFeatureFlags({});
        }
      } else {
        setActiveConfig(null);
      }
    } catch (err: any) {
      if (err.response?.status !== 404) {
        console.error('Failed to fetch active config:', err);
      }
      setActiveConfig(null);
    }
  };

  const handleDeploy = async () => {
    setLoading(true);
    setMessage(null);

    try {
      const response = await gatewayAPI.createOTELConfig({
        device_group: deviceGroup,
        protocol: otelConfig.protocol,
        collector_endpoint: otelConfig.collector_endpoint,
        auth_token: otelConfig.auth_token,
        dataset: otelConfig.dataset,
        ram_buffer_size: otelConfig.ram_buffer_size,
        disk_buffer_mb: otelConfig.disk_buffer_mb,
        disk_buffer_ttl_hours: otelConfig.disk_buffer_ttl_hours,
        export_timeout_seconds: otelConfig.export_timeout_seconds,
        max_export_retries: otelConfig.max_export_retries,
        environment_vars: envVars,
        feature_flags: featureFlags,
      });

      setMessage({
        type: 'success',
        text: `Configuration ${response.version} deployed to ${response.affected_devices} devices in group "${deviceGroup}"`,
      });

      // Refresh lists
      await fetchActiveConfig();
      await fetchSavedConfigs();
    } catch (err: any) {
      console.error('Deploy failed:', err);
      setMessage({
        type: 'error',
        text: `Failed to deploy configuration: ${err.message || 'Unknown error'}`,
      });
    } finally {
      setLoading(false);
    }
  };

  const handleActivateConfig = async (configId: number) => {
    try {
      await gatewayAPI.activateOTELConfig(configId);
      setMessage({ type: 'success', text: 'Configuration activated successfully' });
      await fetchActiveConfig();
      await fetchSavedConfigs();
    } catch (err: any) {
      setMessage({ type: 'error', text: `Failed to activate: ${err.message}` });
    }
  };

  const addEnvVar = () => {
    const key = prompt('Environment variable name:');
    if (key && key.trim()) {
      setEnvVars({ ...envVars, [key.trim()]: '' });
    }
  };

  const removeEnvVar = (key: string) => {
    const newVars = { ...envVars };
    delete newVars[key];
    setEnvVars(newVars);
  };

  const addFeatureFlag = () => {
    const key = prompt('Feature flag name:');
    if (key && key.trim()) {
      setFeatureFlags({ ...featureFlags, [key.trim()]: false });
    }
  };

  const removeFeatureFlag = (key: string) => {
    const newFlags = { ...featureFlags };
    delete newFlags[key];
    setFeatureFlags(newFlags);
  };

  const getEndpointPlaceholder = () => {
    return otelConfig.protocol === 'grpc'
      ? 'https://your-collector-endpoint:4317'
      : 'https://your-collector-endpoint/v1/logs';
  };

  return (
    <div className="config-manager">
      <div className="config-manager-header">
        <h2>OTEL Configuration Management</h2>
        <p className="subtitle">Deploy OpenTelemetry configurations to device groups</p>
      </div>

      {message && (
        <div className={`config-message config-message-${message.type}`}>
          {message.text}
          <button onClick={() => setMessage(null)}>✕</button>
        </div>
      )}

      {rolloutStatus.length > 0 && (
        <div className="rollout-status-panel">
          <h3>Configuration Rollout Status</h3>
          <div className="rollout-cards">
            {rolloutStatus.map((status) => (
              <div key={status.device_group} className="rollout-card">
                <div className="rollout-header">
                  <span className="rollout-group">{status.device_group}</span>
                  <span className="rollout-version">v{status.active_version}</span>
                </div>
                <div className="rollout-progress">
                  <div className="rollout-progress-bar">
                    <div
                      className="rollout-progress-fill"
                      style={{ width: `${status.rollout_percentage}%` }}
                    />
                  </div>
                  <div className="rollout-stats">
                    <span className="rollout-percentage">{status.rollout_percentage}%</span>
                    <span className="rollout-devices">
                      {status.compliant_devices} / {status.total_devices} devices
                    </span>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="config-layout">
        <div className="config-editor">
          <div className="form-section">
            <label className="form-label">Target Device Group</label>
            <select
              className="form-control"
              value={deviceGroup}
              onChange={(e) => setDeviceGroup(e.target.value)}
            >
              {deviceGroups.map((group) => (
                <option key={group.name} value={group.name}>
                  {group.name} ({group.environment})
                </option>
              ))}
            </select>
            {activeConfig && (
              <p className="form-hint">
                Active: v{activeConfig.version} (deployed {new Date(activeConfig.created_at).toLocaleString()})
              </p>
            )}
          </div>

          <div className="form-section">
            <h3>OTEL Configuration</h3>

            <div className="form-group">
              <label className="form-label">Protocol</label>
              <div className="radio-group">
                <label className="radio-label">
                  <input
                    type="radio"
                    value="grpc"
                    checked={otelConfig.protocol === 'grpc'}
                    onChange={(e) => setOtelConfig({ ...otelConfig, protocol: 'grpc' })}
                  />
                  <span>gRPC (port 4317)</span>
                </label>
                <label className="radio-label">
                  <input
                    type="radio"
                    value="http"
                    checked={otelConfig.protocol === 'http'}
                    onChange={(e) => setOtelConfig({ ...otelConfig, protocol: 'http' })}
                  />
                  <span>HTTP (path /v1/signal)</span>
                </label>
              </div>
            </div>

            <div className="form-group">
              <label className="form-label">Collector Endpoint</label>
              <input
                type="text"
                className="form-control"
                value={otelConfig.collector_endpoint}
                onChange={(e) => setOtelConfig({ ...otelConfig, collector_endpoint: e.target.value })}
                placeholder={getEndpointPlaceholder()}
              />
              <p className="form-hint">
                {otelConfig.protocol === 'grpc' ? 'Use :4317 port for gRPC' : 'Include /v1/logs, /v1/traces, or /v1/metrics path'}
              </p>
            </div>

            <div className="form-group">
              <label className="form-label">Auth Token</label>
              <input
                type="password"
                className="form-control"
                value={otelConfig.auth_token}
                onChange={(e) => setOtelConfig({ ...otelConfig, auth_token: e.target.value })}
                placeholder="auth_..."
              />
              <p className="form-hint">Bearer token for authentication (works for both gRPC and HTTP)</p>
            </div>

            <div className="form-group">
              <label className="form-label">Dataset / Tenant ID</label>
              <input
                type="text"
                className="form-control"
                value={otelConfig.dataset}
                onChange={(e) => setOtelConfig({ ...otelConfig, dataset: e.target.value })}
                placeholder="production-mobile"
              />
              <p className="form-hint">Dataset or tenant identifier for multi-tenant systems</p>
            </div>

            <div className="form-row">
              <div className="form-group">
                <label className="form-label">RAM Buffer Size (events)</label>
                <input
                  type="number"
                  className="form-control"
                  value={otelConfig.ram_buffer_size}
                  onChange={(e) => setOtelConfig({ ...otelConfig, ram_buffer_size: parseInt(e.target.value) })}
                />
              </div>

              <div className="form-group">
                <label className="form-label">Disk Buffer (MB)</label>
                <input
                  type="number"
                  className="form-control"
                  value={otelConfig.disk_buffer_mb}
                  onChange={(e) => setOtelConfig({ ...otelConfig, disk_buffer_mb: parseInt(e.target.value) })}
                />
              </div>

              <div className="form-group">
                <label className="form-label">Disk TTL (hours)</label>
                <input
                  type="number"
                  className="form-control"
                  value={otelConfig.disk_buffer_ttl_hours}
                  onChange={(e) => setOtelConfig({ ...otelConfig, disk_buffer_ttl_hours: parseInt(e.target.value) })}
                />
              </div>
            </div>

            <div className="form-row">
              <div className="form-group">
                <label className="form-label">Export Timeout (seconds)</label>
                <input
                  type="number"
                  className="form-control"
                  value={otelConfig.export_timeout_seconds}
                  onChange={(e) => setOtelConfig({ ...otelConfig, export_timeout_seconds: parseInt(e.target.value) })}
                />
              </div>

              <div className="form-group">
                <label className="form-label">Max Retries</label>
                <input
                  type="number"
                  className="form-control"
                  value={otelConfig.max_export_retries}
                  onChange={(e) => setOtelConfig({ ...otelConfig, max_export_retries: parseInt(e.target.value) })}
                />
              </div>
            </div>
          </div>

          <div className="form-section">
            <h3>Environment Variables</h3>
            <div className="key-value-list">
              {Object.entries(envVars).map(([key, value]) => (
                <div key={key} className="key-value-row">
                  <input type="text" className="form-control-sm" value={key} readOnly />
                  <input
                    type="text"
                    className="form-control-sm"
                    value={value}
                    onChange={(e) => setEnvVars({ ...envVars, [key]: e.target.value })}
                    placeholder="value"
                  />
                  <button className="btn-remove" onClick={() => removeEnvVar(key)}>
                    ✕
                  </button>
                </div>
              ))}
              <button className="btn-add" onClick={addEnvVar}>
                + Add Variable
              </button>
            </div>
          </div>

          <div className="form-section">
            <h3>Feature Flags</h3>
            <div className="key-value-list">
              {Object.entries(featureFlags).map(([key, value]) => (
                <div key={key} className="key-value-row">
                  <input type="text" className="form-control-sm" value={key} readOnly />
                  <label className="checkbox-label">
                    <input
                      type="checkbox"
                      checked={value}
                      onChange={(e) => setFeatureFlags({ ...featureFlags, [key]: e.target.checked })}
                    />
                    <span>{value ? 'Enabled' : 'Disabled'}</span>
                  </label>
                  <button className="btn-remove" onClick={() => removeFeatureFlag(key)}>
                    ✕
                  </button>
                </div>
              ))}
              <button className="btn-add" onClick={addFeatureFlag}>
                + Add Flag
              </button>
            </div>
          </div>

          <div className="form-actions">
            <button className="btn-primary" onClick={handleDeploy} disabled={loading}>
              {loading ? '⏳ Deploying...' : '🚀 Deploy Configuration'}
            </button>
            <button className="btn-secondary" onClick={fetchActiveConfig}>
              🔄 Reload
            </button>
          </div>
        </div>

        <div className="config-versions-panel">
          <h3>Configuration History</h3>
          <p className="panel-subtitle">For group: {deviceGroup}</p>

          {savedConfigs.length === 0 ? (
            <p className="empty-state">No configurations yet</p>
          ) : (
            <div className="versions-list">
              {savedConfigs.map((config) => (
                <div key={config.id} className={`version-card ${config.is_active ? 'active' : ''}`}>
                  <div className="version-header">
                    <strong>v{config.version}</strong>
                    {config.is_active && <span className="badge-active">Active</span>}
                  </div>
                  <div className="version-details">
                    <div className="detail-row">
                      <span className="label">Protocol:</span>
                      <span className="value">{config.protocol.toUpperCase()}</span>
                    </div>
                    <div className="detail-row">
                      <span className="label">Endpoint:</span>
                      <span className="value endpoint-text">{config.collector_endpoint}</span>
                    </div>
                    <div className="detail-row">
                      <span className="label">Dataset:</span>
                      <span className="value">{config.dataset || '-'}</span>
                    </div>
                    <div className="detail-row">
                      <span className="label">Created:</span>
                      <span className="value">{new Date(config.created_at).toLocaleString()}</span>
                    </div>
                    <div className="detail-row">
                      <span className="label">By:</span>
                      <span className="value">{config.created_by}</span>
                    </div>
                  </div>
                  {!config.is_active && (
                    <button className="btn-activate" onClick={() => handleActivateConfig(config.id)}>
                      Activate
                    </button>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
