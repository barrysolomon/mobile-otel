import { useState, useEffect } from 'react';

interface CollectorEndpoint {
  name: string;
  endpoint: string;
  protocol: 'grpc' | 'http';
  headers?: Record<string, string>;
  enabled: boolean;
}

interface CollectorConfigProps {
  onSave?: (config: CollectorEndpoint[]) => void;
}

const DEFAULT_ENDPOINTS: CollectorEndpoint[] = [
  {
    name: 'Dash0 US',
    endpoint: 'ingress.us.dash0.com:4317',
    protocol: 'grpc',
    enabled: false,
  },
  {
    name: 'Dash0 EU',
    endpoint: 'ingress.eu.dash0.com:4317',
    protocol: 'grpc',
    enabled: false,
  },
  {
    name: 'Local OTEL Collector',
    endpoint: 'localhost:4317',
    protocol: 'grpc',
    enabled: true,
  },
  {
    name: 'Custom Endpoint',
    endpoint: '',
    protocol: 'grpc',
    enabled: false,
  },
];

export function CollectorConfig({ onSave }: CollectorConfigProps) {
  const [endpoints, setEndpoints] = useState<CollectorEndpoint[]>(DEFAULT_ENDPOINTS);
  const [authToken, setAuthToken] = useState('');
  const [dataset, setDataset] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);

  // Load from localStorage on mount
  useEffect(() => {
    const saved = localStorage.getItem('collector_config');
    if (saved) {
      try {
        const parsed = JSON.parse(saved);
        setEndpoints(parsed.endpoints || DEFAULT_ENDPOINTS);
        setAuthToken(parsed.authToken || '');
        setDataset(parsed.dataset || '');
      } catch (e) {
        console.error('Failed to parse saved config:', e);
      }
    }
  }, []);

  const updateEndpoint = (index: number, updates: Partial<CollectorEndpoint>) => {
    setEndpoints((prev) => {
      const updated = [...prev];
      updated[index] = { ...updated[index], ...updates };
      return updated;
    });
  };

  const addCustomEndpoint = () => {
    setEndpoints((prev) => [
      ...prev,
      {
        name: 'Custom Endpoint',
        endpoint: '',
        protocol: 'grpc',
        enabled: false,
      },
    ]);
  };

  const removeEndpoint = (index: number) => {
    setEndpoints((prev) => prev.filter((_, i) => i !== index));
  };

  const handleSave = () => {
    // Build headers for Dash0
    const headers: Record<string, string> = {};
    if (authToken) {
      headers['Authorization'] = `Bearer ${authToken}`;
    }
    if (dataset) {
      headers['Dash0-Dataset'] = dataset;
    }

    // Apply headers to all Dash0 endpoints
    const updatedEndpoints = endpoints.map((ep) => {
      if (ep.name.startsWith('Dash0') && Object.keys(headers).length > 0) {
        return { ...ep, headers };
      }
      return ep;
    });

    const config = {
      endpoints: updatedEndpoints,
      authToken,
      dataset,
    };

    // Save to localStorage
    localStorage.setItem('collector_config', JSON.stringify(config));

    // Notify parent
    if (onSave) {
      onSave(updatedEndpoints);
    }

    alert('Configuration saved! Restart mobile app to apply changes.');
  };

  const exportConfig = () => {
    const activeEndpoint = endpoints.find((ep) => ep.enabled);
    if (!activeEndpoint) {
      alert('No active endpoint selected');
      return;
    }

    const mobileConfig = {
      serviceName: 'mobile-app',
      serviceVersion: '1.0.0',
      collectorEndpoint: `https://${activeEndpoint.endpoint}`,
      exportMode: 'CONDITIONAL',
      headers: activeEndpoint.headers || null,
    };

    const blob = new Blob([JSON.stringify(mobileConfig, null, 2)], {
      type: 'application/json',
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'mobile-config.json';
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="collector-config">
      <div className="config-header">
        <h2>OTEL Collector Configuration</h2>
        <p className="config-description">
          Configure endpoints for mobile telemetry export. Supports Dash0, local collectors, and custom endpoints.
        </p>
      </div>

      <div className="config-section">
        <h3>Dash0 Authentication</h3>
        <div className="form-group">
          <label htmlFor="authToken">
            Auth Token
            <span className="field-hint">
              Get your token from{' '}
              <a href="https://dash0.com" target="_blank" rel="noopener noreferrer">
                dash0.com
              </a>
            </span>
          </label>
          <input
            type="password"
            id="authToken"
            value={authToken}
            onChange={(e) => setAuthToken(e.target.value)}
            placeholder="Bearer token for Dash0 authentication"
            className="form-input"
          />
        </div>

        <div className="form-group">
          <label htmlFor="dataset">
            Dataset Name (Optional)
            <span className="field-hint">Logical grouping for your telemetry data</span>
          </label>
          <input
            type="text"
            id="dataset"
            value={dataset}
            onChange={(e) => setDataset(e.target.value)}
            placeholder="e.g., mobile-prod, mobile-staging"
            className="form-input"
          />
        </div>
      </div>

      <div className="config-section">
        <h3>Collector Endpoints</h3>
        <p className="section-hint">Select one active endpoint. Mobile apps will send telemetry here.</p>

        <div className="endpoints-list">
          {endpoints.map((endpoint, index) => (
            <div key={index} className={`endpoint-card ${endpoint.enabled ? 'active' : ''}`}>
              <div className="endpoint-header">
                <input
                  type="radio"
                  name="active-endpoint"
                  checked={endpoint.enabled}
                  onChange={() => {
                    setEndpoints((prev) =>
                      prev.map((ep, i) => ({
                        ...ep,
                        enabled: i === index,
                      }))
                    );
                  }}
                />
                <input
                  type="text"
                  value={endpoint.name}
                  onChange={(e) => updateEndpoint(index, { name: e.target.value })}
                  placeholder="Endpoint name"
                  className="endpoint-name-input"
                />
                {!endpoint.name.startsWith('Dash0') && index >= 3 && (
                  <button onClick={() => removeEndpoint(index)} className="btn-remove" title="Remove endpoint">
                    ✕
                  </button>
                )}
              </div>

              <div className="endpoint-body">
                <div className="form-row">
                  <div className="form-group flex-1">
                    <label>Endpoint URL</label>
                    <input
                      type="text"
                      value={endpoint.endpoint}
                      onChange={(e) => updateEndpoint(index, { endpoint: e.target.value })}
                      placeholder="hostname:port (e.g., collector.example.com:4317)"
                      className="form-input"
                      disabled={endpoint.name.startsWith('Dash0')}
                    />
                  </div>

                  <div className="form-group">
                    <label>Protocol</label>
                    <select
                      value={endpoint.protocol}
                      onChange={(e) =>
                        updateEndpoint(index, { protocol: e.target.value as 'grpc' | 'http' })
                      }
                      className="form-select"
                    >
                      <option value="grpc">gRPC</option>
                      <option value="http">HTTP</option>
                    </select>
                  </div>
                </div>

                {endpoint.name.startsWith('Dash0') && (
                  <div className="dash0-info">
                    ℹ️ Dash0 endpoints automatically use your auth token and dataset configured above
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>

        <button onClick={addCustomEndpoint} className="btn-secondary">
          + Add Custom Endpoint
        </button>
      </div>

      <div className="config-section">
        <button onClick={() => setShowAdvanced(!showAdvanced)} className="btn-link">
          {showAdvanced ? '▼' : '▶'} Advanced Options
        </button>

        {showAdvanced && (
          <div className="advanced-options">
            <div className="form-group">
              <label>Export Timeout (seconds)</label>
              <input type="number" defaultValue={30} className="form-input" />
            </div>

            <div className="form-group">
              <label>Max Retry Attempts</label>
              <input type="number" defaultValue={3} className="form-input" />
            </div>

            <div className="form-group">
              <label>Config Poll Interval (seconds)</label>
              <input type="number" defaultValue={300} className="form-input" />
              <span className="field-hint">How often devices check for workflow updates</span>
            </div>
          </div>
        )}
      </div>

      <div className="config-actions">
        <button onClick={handleSave} className="btn-primary">
          💾 Save Configuration
        </button>
        <button onClick={exportConfig} className="btn-secondary">
          📥 Export for Mobile App
        </button>
      </div>

      <div className="config-info">
        <h4>📱 Using in Mobile App</h4>
        <p>
          After saving, copy the configuration to your Android app:
        </p>
        <pre className="code-block">
          {`val config = MobileConfig(
    serviceName = "mobile-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://${endpoints.find((e) => e.enabled)?.endpoint || 'collector:4317'}",
    exportMode = ExportMode.CONDITIONAL,
    headers = mapOf(
        ${authToken ? `"Authorization" to "Bearer ${authToken}"` : ''}${authToken && dataset ? ',\n        ' : ''}${dataset ? `"Dash0-Dataset" to "${dataset}"` : ''}
    )
)`}
        </pre>
      </div>

      <style>{`
        .collector-config {
          max-width: 900px;
          margin: 0 auto;
          padding: 2rem;
        }

        .config-header {
          margin-bottom: 2rem;
        }

        .config-header h2 {
          margin: 0 0 0.5rem 0;
          font-size: 1.75rem;
          color: #1a1a1a;
        }

        .config-description {
          color: #666;
          margin: 0;
        }

        .config-section {
          background: white;
          border: 1px solid #e0e0e0;
          border-radius: 8px;
          padding: 1.5rem;
          margin-bottom: 1.5rem;
        }

        .config-section h3 {
          margin: 0 0 1rem 0;
          font-size: 1.25rem;
          color: #1a1a1a;
        }

        .section-hint {
          color: #666;
          font-size: 0.9rem;
          margin: -0.5rem 0 1rem 0;
        }

        .form-group {
          margin-bottom: 1rem;
        }

        .form-group label {
          display: block;
          font-weight: 500;
          margin-bottom: 0.5rem;
          color: #333;
        }

        .field-hint {
          display: block;
          font-weight: normal;
          font-size: 0.85rem;
          color: #666;
          margin-top: 0.25rem;
        }

        .field-hint a {
          color: #0066cc;
          text-decoration: none;
        }

        .field-hint a:hover {
          text-decoration: underline;
        }

        .form-input,
        .form-select {
          width: 100%;
          padding: 0.75rem;
          border: 1px solid #ccc;
          border-radius: 4px;
          font-size: 0.95rem;
          font-family: inherit;
        }

        .form-input:disabled {
          background: #f5f5f5;
          color: #666;
          cursor: not-allowed;
        }

        .form-input:focus,
        .form-select:focus {
          outline: none;
          border-color: #0066cc;
          box-shadow: 0 0 0 3px rgba(0, 102, 204, 0.1);
        }

        .form-row {
          display: flex;
          gap: 1rem;
          align-items: flex-end;
        }

        .flex-1 {
          flex: 1;
        }

        .endpoints-list {
          margin-bottom: 1rem;
        }

        .endpoint-card {
          border: 2px solid #e0e0e0;
          border-radius: 6px;
          padding: 1rem;
          margin-bottom: 1rem;
          transition: all 0.2s;
        }

        .endpoint-card.active {
          border-color: #0066cc;
          background: #f0f7ff;
        }

        .endpoint-header {
          display: flex;
          align-items: center;
          gap: 0.75rem;
          margin-bottom: 1rem;
        }

        .endpoint-name-input {
          flex: 1;
          border: none;
          background: transparent;
          font-weight: 500;
          font-size: 1rem;
          padding: 0.25rem;
        }

        .endpoint-name-input:focus {
          outline: none;
          background: white;
          border: 1px solid #0066cc;
          border-radius: 4px;
        }

        .endpoint-body {
          margin-left: 2rem;
        }

        .dash0-info {
          margin-top: 0.75rem;
          padding: 0.75rem;
          background: #e3f2fd;
          border-left: 3px solid #2196f3;
          border-radius: 4px;
          font-size: 0.9rem;
          color: #0d47a1;
        }

        .btn-remove {
          background: none;
          border: none;
          color: #d32f2f;
          font-size: 1.25rem;
          cursor: pointer;
          padding: 0.25rem 0.5rem;
          border-radius: 4px;
        }

        .btn-remove:hover {
          background: #ffebee;
        }

        .btn-primary,
        .btn-secondary,
        .btn-link {
          padding: 0.75rem 1.5rem;
          border: none;
          border-radius: 6px;
          font-size: 0.95rem;
          font-weight: 500;
          cursor: pointer;
          transition: all 0.2s;
        }

        .btn-primary {
          background: #0066cc;
          color: white;
        }

        .btn-primary:hover {
          background: #0052a3;
        }

        .btn-secondary {
          background: white;
          color: #333;
          border: 1px solid #ccc;
        }

        .btn-secondary:hover {
          background: #f5f5f5;
          border-color: #999;
        }

        .btn-link {
          background: none;
          color: #0066cc;
          padding: 0.5rem 0;
          text-align: left;
        }

        .btn-link:hover {
          color: #0052a3;
        }

        .advanced-options {
          margin-top: 1rem;
          padding-top: 1rem;
          border-top: 1px solid #e0e0e0;
        }

        .config-actions {
          display: flex;
          gap: 1rem;
          margin-top: 2rem;
        }

        .config-info {
          background: #f9f9f9;
          border: 1px solid #e0e0e0;
          border-radius: 8px;
          padding: 1.5rem;
          margin-top: 2rem;
        }

        .config-info h4 {
          margin: 0 0 1rem 0;
          color: #333;
        }

        .config-info p {
          color: #666;
          margin: 0 0 1rem 0;
        }

        .code-block {
          background: #1e1e1e;
          color: #d4d4d4;
          padding: 1rem;
          border-radius: 6px;
          overflow-x: auto;
          font-size: 0.9rem;
          line-height: 1.5;
        }
      `}</style>
    </div>
  );
}
