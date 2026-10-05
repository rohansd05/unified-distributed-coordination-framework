import { afterEach, describe, expect, it, vi } from 'vitest'
import { getConfig } from './config'

afterEach(() => {
  vi.unstubAllEnvs()
})

describe('getConfig', () => {
  it('returns apiBaseUrl and wsUrl when both variables are valid', () => {
    vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:8080')
    vi.stubEnv('VITE_WS_URL', 'ws://localhost:8080/ws')

    const config = getConfig()
    expect(config.error).toBeNull()
    expect(config.errors).toHaveLength(0)
    expect(config.apiBaseUrl).toBe('http://localhost:8080')
    expect(config.wsUrl).toBe('ws://localhost:8080/ws')
  })

  it('accepts secure https and wss protocols', () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.com')
    vi.stubEnv('VITE_WS_URL', 'wss://api.example.com/ws')

    const config = getConfig()
    expect(config.error).toBeNull()
    expect(config.apiBaseUrl).toBe('https://api.example.com')
    expect(config.wsUrl).toBe('wss://api.example.com/ws')
  })

  it('reports an error naming VITE_API_BASE_URL when missing', () => {
    vi.stubEnv('VITE_API_BASE_URL', '')
    vi.stubEnv('VITE_WS_URL', 'ws://localhost:8080/ws')

    const config = getConfig()
    expect(config.error).toContain('VITE_API_BASE_URL is missing')
    expect(config.errors).toContain('VITE_API_BASE_URL is missing')
    expect(config.apiBaseUrl).toBeNull()
  })

  it('reports an error naming VITE_API_BASE_URL when protocol is invalid', () => {
    vi.stubEnv('VITE_API_BASE_URL', 'ftp://localhost:8080')
    vi.stubEnv('VITE_WS_URL', 'ws://localhost:8080/ws')

    const config = getConfig()
    expect(config.error).toContain('VITE_API_BASE_URL is invalid')
    expect(config.error).toContain('http:// or https://')
  })

  it('reports an error naming VITE_WS_URL when missing', () => {
    vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:8080')
    vi.stubEnv('VITE_WS_URL', '')

    const config = getConfig()
    expect(config.error).toContain('VITE_WS_URL is missing')
    expect(config.wsUrl).toBeNull()
  })

  it('reports an error naming VITE_WS_URL when protocol is invalid', () => {
    vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:8080')
    vi.stubEnv('VITE_WS_URL', 'http://localhost:8080/ws')

    const config = getConfig()
    expect(config.error).toContain('VITE_WS_URL is invalid')
    expect(config.error).toContain('ws:// or wss://')
  })

  it('reports both variables when both are missing or invalid', () => {
    vi.stubEnv('VITE_API_BASE_URL', '')
    vi.stubEnv('VITE_WS_URL', '')

    const config = getConfig()
    expect(config.errors).toHaveLength(2)
    expect(config.error).toContain('VITE_API_BASE_URL')
    expect(config.error).toContain('VITE_WS_URL')
  })
})
