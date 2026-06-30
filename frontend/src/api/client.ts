import axios from 'axios';

const BASE_URL = import.meta.env.VITE_API_URL || '';

const api = axios.create({
  baseURL: BASE_URL,
  headers: { 'Content-Type': 'application/json' },
});

export const connectionApi = {
  test: (data: { url: string; accessToken: string; tenant?: string }) =>
    api.post('/api/connection/test', data),
};

export const servicesApi = {
  list: (url: string, accessToken: string) =>
    api.get('/api/services', { params: { url, accessToken } }),
  get: (id: string, url: string, accessToken: string) =>
    api.get(`/api/services/${id}`, { params: { url, accessToken } }),
  checkCompatibility: (id: string, url: string, accessToken: string) =>
    api.get(`/api/services/${id}/compatibility`, { params: { url, accessToken } }),
};

export const conversionApi = {
  convert: (data: {
    threescaleUrl: string;
    accessToken: string;
    tenant?: string;
    namespace: string;
    serviceIds: string[];
    externalBackendUrl?: string;
  }) => api.post('/api/convert', data),
};

export const validationApi = {
  validate: (yamlFiles: Record<string, string>) =>
    api.post('/api/validate', yamlFiles),
};

export const downloadApi = {
  downloadZip: (packageName: string, yamlFiles: Record<string, string>) =>
    api.post('/api/download/zip', { packageName, yamlFiles }, { responseType: 'blob' }),
};

export const applyApi = {
  apply: (namespace: string, files: Record<string, string>, source = 'CONVERT') =>
    api.post('/api/apply', { namespace, files, source }),
};

export const importApi = {
  uploadZip: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return api.post('/api/import/zip', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
};

export const gatewayApi = {
  getInfo: (namespace: string, name: string) =>
    api.get('/api/gateway/info', { params: { namespace, name } }),
};

export const historyApi = {
  list: (page = 0, size = 50) =>
    api.get('/api/history', { params: { page, size } }),
  get: (id: number) => api.get(`/api/history/${id}`),
  downloadZip: (id: number) =>
    api.get(`/api/history/${id}/download`, { responseType: 'blob' }),
  deleteByIds: (ids: number[]) =>
    api.delete('/api/history', { data: ids }),
};

export default api;
