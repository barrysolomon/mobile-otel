import React, { useEffect, useState } from 'react';
import { gatewayAPI } from '../api/gateway';

interface Device {
  device_id: string;
  device_group: string;
  os_version: string;
  app_version: string;
  last_seen: string;
  current_config_version: number;
  config_applied_successfully: boolean;
  registered_at: string;
}

export const DeviceFleet: React.FC = () => {
  const [devices, setDevices] = useState<Device[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedGroup, setSelectedGroup] = useState<string>('all');
  const [searchTerm, setSearchTerm] = useState('');
  const [autoRefresh, setAutoRefresh] = useState(true);

  useEffect(() => {
    fetchDevices();

    if (!autoRefresh) return;

    const interval = setInterval(fetchDevices, 30000); // Poll every 30s
    return () => clearInterval(interval);
  }, [selectedGroup, autoRefresh]);

  const fetchDevices = async () => {
    try {
      setLoading(true);
      setError(null);
      const params: any = { limit: 100 };
      if (selectedGroup !== 'all') {
        params.group = selectedGroup;
      }
      const result = await gatewayAPI.listDevices(params);
      setDevices(result.devices || []);
    } catch (err: any) {
      console.error('Failed to fetch devices:', err);
      setError(err.message || 'Failed to fetch devices');
    } finally {
      setLoading(false);
    }
  };

  const filteredDevices = devices.filter(d =>
    d.device_id.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const formatLastSeen = (timestamp: string): string => {
    if (!timestamp) return 'Never';
    const date = new Date(timestamp);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMins = Math.floor(diffMs / 60000);

    if (diffMins < 1) return 'Just now';
    if (diffMins < 60) return `${diffMins}m ago`;
    const diffHours = Math.floor(diffMins / 60);
    if (diffHours < 24) return `${diffHours}h ago`;
    return date.toLocaleDateString();
  };

  const getStatusIndicator = (lastSeen: string): { color: string; text: string; emoji: string } => {
    if (!lastSeen) return { color: '#gray', text: 'Unknown', emoji: '⚪' };

    const diffMins = Math.floor((Date.now() - new Date(lastSeen).getTime()) / 60000);

    if (diffMins < 1) return { color: '#4caf50', text: 'Online', emoji: '🟢' };
    if (diffMins < 5) return { color: '#ff9800', text: 'Active', emoji: '🟡' };
    return { color: '#f44336', text: 'Offline', emoji: '🔴' };
  };

  const groupColors: Record<string, string> = {
    'production-mobile': '#e91e63',
    'staging-mobile': '#ff9800',
    'dev-mobile': '#2196f3',
    'default': '#9e9e9e'
  };

  return (
    <div className="device-fleet">
      <div className="device-fleet-header">
        <h2>Device Fleet Management</h2>
        <div className="device-fleet-stats">
          <div className="stat-box">
            <span className="stat-value">{devices.length}</span>
            <span className="stat-label">Total Devices</span>
          </div>
          <div className="stat-box">
            <span className="stat-value" style={{ color: '#4caf50' }}>
              {devices.filter(d => getStatusIndicator(d.last_seen).text === 'Online').length}
            </span>
            <span className="stat-label">Online</span>
          </div>
          <div className="stat-box">
            <span className="stat-value" style={{ color: '#ff9800' }}>
              {devices.filter(d => getStatusIndicator(d.last_seen).text === 'Active').length}
            </span>
            <span className="stat-label">Active</span>
          </div>
          <div className="stat-box">
            <span className="stat-value" style={{ color: '#f44336' }}>
              {devices.filter(d => getStatusIndicator(d.last_seen).text === 'Offline').length}
            </span>
            <span className="stat-label">Offline</span>
          </div>
        </div>
      </div>

      <div className="device-fleet-controls">
        <input
          type="text"
          className="search-input"
          placeholder="🔍 Search devices..."
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
        />

        <select
          className="group-filter"
          value={selectedGroup}
          onChange={(e) => setSelectedGroup(e.target.value)}
        >
          <option value="all">All Groups</option>
          <option value="production-mobile">Production</option>
          <option value="staging-mobile">Staging</option>
          <option value="dev-mobile">Development</option>
          <option value="default">Default</option>
        </select>

        <label className="auto-refresh-toggle">
          <input
            type="checkbox"
            checked={autoRefresh}
            onChange={(e) => setAutoRefresh(e.target.checked)}
          />
          <span>Auto-refresh (30s)</span>
        </label>

        <button className="refresh-btn" onClick={fetchDevices} disabled={loading}>
          {loading ? '🔄 Loading...' : '🔄 Refresh Now'}
        </button>
      </div>

      {error && (
        <div className="error-message">
          <strong>Error:</strong> {error}
        </div>
      )}

      {loading && devices.length === 0 ? (
        <div className="loading-message">Loading devices...</div>
      ) : filteredDevices.length === 0 ? (
        <div className="no-devices-message">
          <p>📱 No devices found</p>
          {searchTerm && <p>Try adjusting your search term</p>}
          {!searchTerm && selectedGroup !== 'all' && <p>No devices in this group</p>}
          {!searchTerm && selectedGroup === 'all' && (
            <p>Register devices from your mobile app to see them here</p>
          )}
        </div>
      ) : (
        <div className="device-table-container">
          <table className="device-table">
            <thead>
              <tr>
                <th>Status</th>
                <th>Device ID</th>
                <th>Group</th>
                <th>OS Version</th>
                <th>App Version</th>
                <th>Config Version</th>
                <th>Config Status</th>
                <th>Last Seen</th>
                <th>Registered</th>
              </tr>
            </thead>
            <tbody>
              {filteredDevices.map(device => {
                const status = getStatusIndicator(device.last_seen);
                return (
                  <tr key={device.device_id}>
                    <td>
                      <span
                        className="status-indicator"
                        style={{ color: status.color }}
                        title={status.text}
                      >
                        {status.emoji}
                      </span>
                    </td>
                    <td className="device-id">{device.device_id}</td>
                    <td>
                      <span
                        className="group-badge"
                        style={{
                          backgroundColor: groupColors[device.device_group] || '#9e9e9e',
                          color: 'white',
                          padding: '4px 8px',
                          borderRadius: '4px',
                          fontSize: '12px',
                          fontWeight: 'bold'
                        }}
                      >
                        {device.device_group}
                      </span>
                    </td>
                    <td>{device.os_version || 'Unknown'}</td>
                    <td>{device.app_version || 'Unknown'}</td>
                    <td className="config-version">
                      {device.current_config_version > 0 ? `v${device.current_config_version}` : 'None'}
                    </td>
                    <td>
                      {device.config_applied_successfully ? (
                        <span style={{ color: '#4caf50' }}>✓ Applied</span>
                      ) : (
                        <span style={{ color: '#f44336' }}>✗ Failed</span>
                      )}
                    </td>
                    <td>{formatLastSeen(device.last_seen)}</td>
                    <td>{new Date(device.registered_at).toLocaleDateString()}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      <div className="device-fleet-footer">
        <p>Showing {filteredDevices.length} of {devices.length} devices</p>
      </div>
    </div>
  );
};
