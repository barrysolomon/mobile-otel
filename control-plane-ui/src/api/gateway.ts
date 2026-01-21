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
};
