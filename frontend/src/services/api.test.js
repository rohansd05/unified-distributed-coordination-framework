import { describe, expect, it } from 'vitest'
import { ApiError, createApi } from './api'

describe('createApi and ApiError', () => {
  it('calls getHealth with GET /api/system/health', async () => {
    let capturedConfig
    const api = createApi({
      baseURL: 'http://localhost:8080',
      adapter: async (config) => {
        capturedConfig = config
        return {
          data: { status: 'UP' },
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
        }
      },
    })

    const health = await api.getHealth()
    expect(health).toEqual({ status: 'UP' })
    expect(capturedConfig.method.toLowerCase()).toBe('get')
    expect(capturedConfig.url).toBe('/api/system/health')
  })

  it('calls getSystemInfo with GET /api/system/info', async () => {
    let capturedConfig
    const mockInfo = { name: 'UDCF', version: '0.1.0', mode: 'local', uptimeSeconds: 42, clusterSize: 5 }
    const api = createApi({
      adapter: async (config) => {
        capturedConfig = config
        return { data: mockInfo, status: 200, statusText: 'OK', headers: {}, config }
      },
    })

    const info = await api.getSystemInfo()
    expect(info).toEqual(mockInfo)
    expect(capturedConfig.method.toLowerCase()).toBe('get')
    expect(capturedConfig.url).toBe('/api/system/info')
  })

  it('calls getCluster with GET /api/cluster', async () => {
    let capturedConfig
    const mockCluster = { size: 5, upCount: 5, nodes: [] }
    const api = createApi({
      adapter: async (config) => {
        capturedConfig = config
        return { data: mockCluster, status: 200, statusText: 'OK', headers: {}, config }
      },
    })

    const cluster = await api.getCluster()
    expect(cluster).toEqual(mockCluster)
    expect(capturedConfig.method.toLowerCase()).toBe('get')
    expect(capturedConfig.url).toBe('/api/cluster')
  })

  it('calls getNode with GET /api/cluster/nodes/{id}', async () => {
    let capturedConfig
    const mockNode = { id: 3, status: 'UP' }
    const api = createApi({
      adapter: async (config) => {
        capturedConfig = config
        return { data: mockNode, status: 200, statusText: 'OK', headers: {}, config }
      },
    })

    const node = await api.getNode(3)
    expect(node).toEqual(mockNode)
    expect(capturedConfig.method.toLowerCase()).toBe('get')
    expect(capturedConfig.url).toBe('/api/cluster/nodes/3')
  })

  it('calls crashNode with POST /api/cluster/nodes/{id}/crash', async () => {
    let capturedConfig
    const mockNode = { id: 2, status: 'DOWN' }
    const api = createApi({
      adapter: async (config) => {
        capturedConfig = config
        return { data: mockNode, status: 200, statusText: 'OK', headers: {}, config }
      },
    })

    const node = await api.crashNode(2)
    expect(node).toEqual(mockNode)
    expect(capturedConfig.method.toLowerCase()).toBe('post')
    expect(capturedConfig.url).toBe('/api/cluster/nodes/2/crash')
  })

  it('calls recoverNode with POST /api/cluster/nodes/{id}/recover', async () => {
    let capturedConfig
    const mockNode = { id: 2, status: 'UP' }
    const api = createApi({
      adapter: async (config) => {
        capturedConfig = config
        return { data: mockNode, status: 200, statusText: 'OK', headers: {}, config }
      },
    })

    const node = await api.recoverNode(2)
    expect(node).toEqual(mockNode)
    expect(capturedConfig.method.toLowerCase()).toBe('post')
    expect(capturedConfig.url).toBe('/api/cluster/nodes/2/recover')
  })

  it('calls resetCluster with POST /api/cluster/reset', async () => {
    let capturedConfig
    const api = createApi({
      adapter: async (config) => {
        capturedConfig = config
        return { data: null, status: 204, statusText: 'No Content', headers: {}, config }
      },
    })

    await api.resetCluster()
    expect(capturedConfig.method.toLowerCase()).toBe('post')
    expect(capturedConfig.url).toBe('/api/cluster/reset')
  })

  it('calls getModules with GET /api/modules', async () => {
    let capturedConfig
    const mockModules = [{ id: 'multithreading', labNumber: 2, title: 'Multithreading', status: 'IDLE' }]
    const api = createApi({
      adapter: async (config) => {
        capturedConfig = config
        return { data: mockModules, status: 200, statusText: 'OK', headers: {}, config }
      },
    })

    const modules = await api.getModules()
    expect(modules).toEqual(mockModules)
    expect(capturedConfig.method.toLowerCase()).toBe('get')
    expect(capturedConfig.url).toBe('/api/modules')
  })

  it('builds query parameters for getEvents', async () => {
    let capturedConfig
    const mockEvents = [{ sequence: 1, module: 'election', nodeId: 1 }]
    const api = createApi({
      adapter: async (config) => {
        capturedConfig = config
        return { data: mockEvents, status: 200, statusText: 'OK', headers: {}, config }
      },
    })

    const events = await api.getEvents({ module: 'election', node: 1, limit: 50 })
    expect(events).toEqual(mockEvents)
    expect(capturedConfig.method.toLowerCase()).toBe('get')
    expect(capturedConfig.url).toBe('/api/events')
    expect(capturedConfig.params).toEqual({ module: 'election', node: 1, limit: 50 })
  })

  it('normalises RFC 7807 ProblemDetail 409 responses into ApiError', async () => {
    const api = createApi({
      adapter: async (config) => {
        const error = new Error('Request failed with status code 409')
        error.config = config
        error.response = {
          status: 409,
          statusText: 'Conflict',
          data: {
            type: 'about:blank',
            title: 'Node state conflict',
            status: 409,
            detail: 'Node 2 is already crashed',
            nodeId: 2,
          },
        }
        throw error
      },
    })

    const error = await api.crashNode(2).catch((e) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      name: 'ApiError',
      status: 409,
      title: 'Node state conflict',
      detail: 'Node 2 is already crashed',
      nodeId: 2,
    })
  })

  it('normalises network errors into status 0 Backend unreachable', async () => {
    const api = createApi({
      adapter: async () => {
        const error = new Error('Network Error')
        throw error
      },
    })

    const error = await api.getCluster().catch((e) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      name: 'ApiError',
      status: 0,
      title: 'Backend unreachable',
      detail: 'Network Error',
    })
  })
})
