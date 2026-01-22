import { useState, useCallback } from 'react';
import { WorkflowBuilder } from './components/WorkflowBuilder';
import { DeviceMonitor } from './components/DeviceMonitor';
import { DeviceFleet } from './components/DeviceFleet';
import { ConfigManager } from './components/ConfigManager';
import { CollectorConfig } from './components/CollectorConfig';
import { gatewayAPI } from './api/gateway';
import { compileGraphToDSL, validateGraph } from './utils/graphToDSL';
import type { WorkflowGraph, ConfigVersion } from './types/workflow';
import './App.css';

const defaultWorkflow: WorkflowGraph = {
  id: 'ui-freeze',
  name: 'UI Freeze Handler',
  enabled: true,
  entryNodeId: 'trigger-1',
  nodes: [
    {
      id: 'trigger-1',
      type: 'event_match',
      position: { x: 100, y: 100 },
      data: {
        eventName: 'ui.freeze',
        predicates: [],
      },
    },
    {
      id: 'action-1',
      type: 'flush_window',
      position: { x: 400, y: 100 },
      data: {
        minutes: 2,
        scope: 'session',
      },
    },
  ],
  edges: [
    {
      id: 'trigger-1-action-1',
      source: 'trigger-1',
      target: 'action-1',
    },
  ],
};

export function App() {
  const [workflows, setWorkflows] = useState<WorkflowGraph[]>([defaultWorkflow]);
  const [selectedWorkflowId, setSelectedWorkflowId] = useState(defaultWorkflow.id);
  const [activeTab, setActiveTab] = useState<'builder' | 'devices' | 'config'>('builder');
  const [devicesSubTab, setDevicesSubTab] = useState<'fleet' | 'monitor'>('fleet');
  const [configSubTab, setConfigSubTab] = useState<'workflows' | 'collector'>('workflows');
  const [versions, setVersions] = useState<ConfigVersion[]>([]);
  const [isPublishing, setIsPublishing] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const selectedWorkflow = workflows.find((w) => w.id === selectedWorkflowId) || defaultWorkflow;

  const handleWorkflowChange = useCallback(
    (updatedWorkflow: WorkflowGraph) => {
      setWorkflows((wfs) =>
        wfs.map((w) => (w.id === updatedWorkflow.id ? updatedWorkflow : w))
      );
    },
    []
  );

  const handleValidate = () => {
    const errors = validateGraph(selectedWorkflow);
    if (errors.length > 0) {
      setMessage({ type: 'error', text: `Validation errors: ${errors.join(', ')}` });
    } else {
      setMessage({ type: 'success', text: 'Workflow validated successfully!' });
    }
  };

  const handlePublish = async () => {
    setIsPublishing(true);
    setMessage(null);

    try {
      // Validate first
      const errors = validateGraph(selectedWorkflow);
      if (errors.length > 0) {
        setMessage({ type: 'error', text: `Cannot publish: ${errors.join(', ')}` });
        return;
      }

      // Compile to DSL
      const dslConfig = compileGraphToDSL(workflows, {
        diskMb: 50,
        ramEvents: 5000,
        retentionHours: 24,
      });

      // Publish to gateway
      const response = await gatewayAPI.publish(workflows, dslConfig, 'admin');

      setMessage({
        type: 'success',
        text: `Published version ${response.version} successfully!`,
      });

      // Refresh versions list
      const versionsList = await gatewayAPI.listVersions();
      setVersions(versionsList);
    } catch (error) {
      setMessage({
        type: 'error',
        text: `Failed to publish: ${error instanceof Error ? error.message : 'Unknown error'}`,
      });
    } finally {
      setIsPublishing(false);
    }
  };

  const handleRollback = async (version: number) => {
    try {
      await gatewayAPI.rollback(version);
      setMessage({ type: 'success', text: `Rolled back to version ${version}` });

      // Refresh versions
      const versionsList = await gatewayAPI.listVersions();
      setVersions(versionsList);
    } catch (error) {
      setMessage({
        type: 'error',
        text: `Failed to rollback: ${error instanceof Error ? error.message : 'Unknown error'}`,
      });
    }
  };

  return (
    <div className="app">
      <header className="app-header">
        <h1>Mobile Observability Control Plane</h1>
        <div className="header-actions">
          <button
            className={`tab-btn ${activeTab === 'builder' ? 'active' : ''}`}
            onClick={() => setActiveTab('builder')}
          >
            Workflow Builder
          </button>
          <button
            className={`tab-btn ${activeTab === 'devices' ? 'active' : ''}`}
            onClick={() => setActiveTab('devices')}
          >
            Devices
          </button>
          <button
            className={`tab-btn ${activeTab === 'config' ? 'active' : ''}`}
            onClick={() => setActiveTab('config')}
          >
            Configuration
          </button>
        </div>
      </header>

      {message && (
        <div className={`message message-${message.type}`}>
          {message.text}
          <button onClick={() => setMessage(null)}>✕</button>
        </div>
      )}

      {activeTab === 'builder' && (
        <div className="builder-container">
          <aside className="workflow-list">
            <h3>Workflows</h3>
            {workflows.map((workflow) => (
              <div
                key={workflow.id}
                className={`workflow-item ${workflow.id === selectedWorkflowId ? 'active' : ''}`}
                onClick={() => setSelectedWorkflowId(workflow.id)}
              >
                <div className="workflow-name">{workflow.name}</div>
                <div className="workflow-status">
                  {workflow.enabled ? '✓ Enabled' : '✗ Disabled'}
                </div>
              </div>
            ))}
          </aside>

          <main className="workflow-editor">
            <div className="editor-toolbar">
              <h2>{selectedWorkflow.name}</h2>
              <div className="toolbar-actions">
                <button onClick={handleValidate} className="btn btn-secondary">
                  Validate
                </button>
                <button
                  onClick={handlePublish}
                  className="btn btn-primary"
                  disabled={isPublishing}
                >
                  {isPublishing ? 'Publishing...' : 'Publish'}
                </button>
              </div>
            </div>

            <WorkflowBuilder workflow={selectedWorkflow} onChange={handleWorkflowChange} />
          </main>

          <aside className="version-panel">
            <h3>Config Versions</h3>
            {versions.length === 0 ? (
              <p className="empty-hint">No versions yet</p>
            ) : (
              <div className="version-list">
                {versions.map((version) => (
                  <div key={version.version} className="version-item">
                    <div className="version-header">
                      <strong>v{version.version}</strong>
                      {version.is_active && <span className="badge">Active</span>}
                    </div>
                    <div className="version-meta">
                      By {version.published_by}
                    </div>
                    {!version.is_active && (
                      <button
                        onClick={() => handleRollback(version.version)}
                        className="btn-rollback"
                      >
                        Rollback
                      </button>
                    )}
                  </div>
                ))}
              </div>
            )}
          </aside>
        </div>
      )}

      {activeTab === 'devices' && (
        <div className="devices-container">
          <div className="devices-subtabs">
            <button
              className={`subtab-btn ${devicesSubTab === 'fleet' ? 'active' : ''}`}
              onClick={() => setDevicesSubTab('fleet')}
            >
              📱 Device Fleet
            </button>
            <button
              className={`subtab-btn ${devicesSubTab === 'monitor' ? 'active' : ''}`}
              onClick={() => setDevicesSubTab('monitor')}
            >
              📊 Live Monitor
            </button>
          </div>
          {devicesSubTab === 'fleet' && <DeviceFleet />}
          {devicesSubTab === 'monitor' && <DeviceMonitor />}
        </div>
      )}

      {activeTab === 'config' && (
        <div className="config-container">
          <div className="config-subtabs">
            <button
              className={`subtab-btn ${configSubTab === 'workflows' ? 'active' : ''}`}
              onClick={() => setConfigSubTab('workflows')}
            >
              ⚙️ Workflow Config
            </button>
            <button
              className={`subtab-btn ${configSubTab === 'collector' ? 'active' : ''}`}
              onClick={() => setConfigSubTab('collector')}
            >
              📡 Collector Endpoints
            </button>
          </div>
          {configSubTab === 'workflows' && <ConfigManager />}
          {configSubTab === 'collector' && <CollectorConfig />}
        </div>
      )}
    </div>
  );
}
