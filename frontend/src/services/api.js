import axios from 'axios'
import { getConfig } from '@/lib/config'

/**
 * Normalised API error with RFC 7807 ProblemDetail attributes.
 */
export class ApiError extends Error {
  constructor({
    status = 0,
    title = 'API Error',
    detail = '',
    nodeId = null,
    moduleId = null,
    errors = null,
    originalError = null,
  } = {}) {
    super(detail || title || 'API Error')
    this.name = 'ApiError'
    this.status = status
    this.title = title
    this.detail = detail
    this.nodeId = nodeId
    this.moduleId = moduleId
    this.errors = errors
    this.originalError = originalError
  }
}

/**
 * Normalises an Axios error into an ApiError.
 */
export function normalizeError(error) {
  if (error instanceof ApiError) {
    return error
  }

  if (error.response) {
    const data = error.response.data || {}
    const status = error.response.status
    const title = data.title || error.response.statusText || 'Error'
    const detail = data.detail || error.message || 'Request failed'

    return new ApiError({
      status,
      title,
      detail,
      nodeId: data.nodeId ?? null,
      moduleId: data.moduleId ?? null,
      errors: data.errors ?? null,
      originalError: error,
    })
  }

  // Network failures, connection timeouts, CORS or unreachable backend
  return new ApiError({
    status: 0,
    title: 'Backend unreachable',
    detail: error.message || 'Unable to connect to backend',
    originalError: error,
  })
}

/**
 * Creates an API client instance.
 *
 * @param {object} options
 * @param {string} [options.baseURL] Base URL for backend requests
 * @param {Function} [options.adapter] Custom Axios adapter for testing
 * @param {number} [options.timeout=10000] Request timeout in milliseconds
 */
export function createApi({ baseURL, adapter, timeout = 10000 } = {}) {
  const client = axios.create({
    baseURL,
    timeout,
    ...(adapter ? { adapter } : {}),
  })

  // Dynamic baseURL resolution if none specified
  if (!baseURL) {
    client.interceptors.request.use((config) => {
      if (!config.baseURL) {
        const appConfig = getConfig()
        if (appConfig.apiBaseUrl) {
          config.baseURL = appConfig.apiBaseUrl
        }
      }
      return config
    })
  }

  client.interceptors.response.use(
    (response) => response,
    (error) => Promise.reject(normalizeError(error)),
  )

  return {
    client,
    getHealth: () => client.get('/api/system/health').then((res) => res.data),
    getSystemInfo: () => client.get('/api/system/info').then((res) => res.data),
    getCluster: () => client.get('/api/cluster').then((res) => res.data),
    getNode: (id) => client.get(`/api/cluster/nodes/${id}`).then((res) => res.data),
    crashNode: (id) => client.post(`/api/cluster/nodes/${id}/crash`).then((res) => res.data),
    recoverNode: (id) => client.post(`/api/cluster/nodes/${id}/recover`).then((res) => res.data),
    resetCluster: () => client.post('/api/cluster/reset').then((res) => res.data),
    getModules: () => client.get('/api/modules').then((res) => res.data),
    getEvents: ({ module, node, limit } = {}) => {
      const params = {}
      if (module) params.module = module
      if (node !== undefined && node !== null) params.node = node
      if (limit !== undefined && limit !== null) params.limit = limit
      return client.get('/api/events', { params }).then((res) => res.data)
    },
  }
}

/** Default API instance configured from application environment */
export const api = createApi()
