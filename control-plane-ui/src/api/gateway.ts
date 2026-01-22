import axios from 'axios';
import type { ConfigVersion, DSLConfig, WorkflowGraph } from '../types/workflow';

const api = axios.create({
  baseURL: '/api',
  headers: {
    'Content-Type': 'application/json',
  },
});

export interface PublishRequest {
  graph_json: string;
  dsl_json: string;
  published_by: string;
}

export interface PublishResponse {
  status: string;
  version: number;
}

export interface RollbackRequest {
  version: number;
}

export interface VersionsResponse {
  versions: ConfigVersion[];
}

export const gatewayAPI = {
  // Get current config (for preview)
  async getConfig(appId: string, deviceId: string): Promise<DSLConfig> {
    const response = await api.get<DSLConfig>(
      `/config?app_id=${appId}&device_id=${deviceId}`
    );
    return response.data;
  },

  // Publish new workflow version
  async publish(
    graphs: WorkflowGraph[],
    dslConfig: DSLConfig,
    publishedBy: string
  ): Promise<PublishResponse> {
    const request: PublishRequest = {
      graph_json: JSON.stringify(graphs),
      dsl_json: JSON.stringify(dslConfig),
      published_by: publishedBy,
    };

    const response = await api.post<PublishResponse>('/admin/publish', request);
    return response.data;
  },

  // Rollback to previous version
  async rollback(version: number): Promise<void> {
    const request: RollbackRequest = { version };
    await api.post('/admin/rollback', request);
  },

  // List config versions
  async listVersions(limit: number = 50): Promise<ConfigVersion[]> {
    const response = await api.get<VersionsResponse>(
      `/admin/versions?limit=${limit}`
    );
    return response.versions;
  },

  // Health check
  async health(): Promise<{ status: string }> {
    const response = await api.get<{ status: string }>('/health');
    return response.data;
  },

  // Device management
  async registerDevice(data: {
    device_id: string;
    os_version: string;
    app_version: string;
    device_group?: string;
  }): Promise<any> {
    const response = await api.post('/v1/devices/register', data);
    return response.data;
  },

  async listDevices(params?: {
    group?: string;
    limit?: number;
    offset?: number;
  }): Promise<any> {
    const response = await api.get('/v1/devices', { params });
    return response.data;
  },

  async getDevice(deviceId: string): Promise<any> {
    const response = await api.get('/v1/devices/detail', {
      params: { device_id: deviceId }
    });
    return response.data;
  },

  async updateDeviceGroup(deviceId: string, deviceGroup: string): Promise<any> {
    const response = await api.patch(`/v1/devices/group?device_id=${deviceId}`, {
      device_group: deviceGroup
    });
    return response.data;
  },

  async listDeviceGroups(): Promise<any> {
    const response = await api.get('/v1/device-groups');
    return response.data;
  },

  async getHeartbeats(limit: number = 100): Promise<any> {
    const response = await api.get('/v1/heartbeats', {
      params: { limit }
    });
    return response.data;
  },

  // OTEL Configuration management
  async createOTELConfig(data: {
    device_group: string;
    protocol: string;
    collector_endpoint: string;
    auth_token?: string;
    dataset?: string;
    ram_buffer_size?: number;
    disk_buffer_mb?: number;
    disk_buffer_ttl_hours?: number;
    export_timeout_seconds?: number;
    max_export_retries?: number;
    environment_vars?: Record<string, string>;
    feature_flags?: Record<string, boolean>;
  }): Promise<any> {
    const response = await api.post('/v1/otel-configs', data);
    return response.data;
  },

  async listOTELConfigs(deviceGroup?: string, limit?: number): Promise<any> {
    const response = await api.get('/v1/otel-configs', {
      params: { device_group: deviceGroup, limit }
    });
    return response.data;
  },

  async getActiveOTELConfig(deviceGroup: string): Promise<any> {
    const response = await api.get('/v1/otel-configs/active', {
      params: { device_group: deviceGroup }
    });
    return response.data;
  },

  async activateOTELConfig(id: number): Promise<any> {
    const response = await api.post('/v1/otel-configs/activate', null, {
      params: { id }
    });
    return response.data;
  },

  async getConfigRolloutStatus(): Promise<any> {
    const response = await api.get('/v1/otel-configs/rollout-status');
    return response.data;
  },
};
