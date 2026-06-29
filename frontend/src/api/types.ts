export interface ConnectionRequest {
  url: string;
  accessToken: string;
  tenant?: string;
}

export interface ApiService {
  id: string;
  name: string;
  description?: string;
  state?: string;
  systemName?: string;
  backendVersion?: string;
  deploymentOption?: string;
  backends?: Backend[];
  mappingRules?: MappingRule[];
  metrics?: Metric[];
  policies?: Policy[];
  authentication?: Authentication;
}

export interface Backend {
  id: string;
  name: string;
  systemName?: string;
  privateEndpoint?: string;
}

export interface MappingRule {
  id: string;
  httpMethod: string;
  pattern: string;
  metricSystemName?: string;
}

export interface Metric {
  id: string;
  name: string;
  systemName: string;
  unit?: string;
}

export interface Policy {
  name: string;
  version?: string;
  enabled: boolean;
  configuration?: Record<string, unknown>;
}

export interface Authentication {
  type: string;
  location?: string;
  paramName?: string;
  oidcIssuerEndpoint?: string;
}

export interface CompatibilityItem {
  name: string;
  status: 'SUPPORTED' | 'WARNING' | 'UNSUPPORTED';
  message: string;
}

export interface CompatibilityResult {
  serviceId: string;
  serviceName: string;
  score: number;
  level: 'HIGH' | 'MEDIUM' | 'LOW';
  items: CompatibilityItem[];
}

export interface ConversionRequest {
  threescaleUrl: string;
  accessToken: string;
  tenant?: string;
  namespace: string;
  serviceIds: string[];
}

export interface ConversionResultItem {
  serviceId: string;
  serviceName: string;
  packageName: string;
  historyId: number;
  compatibilityScore: number;
  files: string[];
  yamlFiles: Record<string, string>;
  status?: string;
  error?: string;
}

export interface ConversionResponse {
  projectId: number;
  results: ConversionResultItem[];
}

export interface ValidationItem {
  check: string;
  status: 'OK' | 'WARNING' | 'ERROR';
  message: string;
}

export interface ValidationResult {
  valid: boolean;
  items: ValidationItem[];
}

export interface ConversionHistory {
  id: number;
  serviceId: string;
  serviceName: string;
  status: string;
  compatibilityScore?: number;
  createdAt: string;
}
