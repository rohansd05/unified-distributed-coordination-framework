import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ClusterProvider, WAKE_RETRY_MS, WAKE_SLOW_MS } from './ClusterProvider'
import { useCluster } from '@/hooks/useCluster'
import { StompContext } from '@/services/stomp/StompContext'

let stompListeners = {}
let connectCallbacks = []

function FakeStompProvider({ children }) {
  const subscribe = vi.fn((destination, handler) => {
    stompListeners[destination] = handler
    return () => {
      delete stompListeners[destination]
    }
  })

  const onConnect = vi.fn((cb) => {
    connectCallbacks.push(cb)
    return () => {
      connectCallbacks = connectCallbacks.filter((c) => c !== cb)
    }
  })

  return (
    <StompContext.Provider
      value={{
        status: 'connected',
        subscribe,
        onConnect,
      }}
    >
      {children}
    </StompContext.Provider>
  )
}

function Consumer() {
  const { info, cluster, modules, loading, error } = useCluster()

  if (loading) return <div>Loading...</div>
  if (error) return <div>Error: {error.message || 'failed'}</div>

  return (
    <div>
      <div data-testid="info-mode">{info?.mode}</div>
      <div data-testid="cluster-up">{cluster?.upCount}/{cluster?.size}</div>
      <div data-testid="modules-count">{modules?.length}</div>
    </div>
  )
}

describe('ClusterProvider', () => {
  beforeEach(() => {
    stompListeners = {}
    connectCallbacks = []
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
  })

  it('fills info, cluster, and modules on initial fetch', async () => {
    const fakeApi = {
      getHealth: vi.fn().mockResolvedValue({ status: 'UP' }),
      getSystemInfo: vi.fn().mockResolvedValue({ mode: 'local', uptimeSeconds: 10 }),
      getCluster: vi.fn().mockResolvedValue({ size: 5, upCount: 5, nodes: [] }),
      getModules: vi.fn().mockResolvedValue([{ id: 'election', status: 'IDLE' }]),
    }

    render(
      <FakeStompProvider>
        <ClusterProvider api={fakeApi}>
          <Consumer />
        </ClusterProvider>
      </FakeStompProvider>,
    )

    expect(screen.getByText('Loading...')).toBeTruthy()

    const infoMode = await screen.findByTestId('info-mode')
    expect(infoMode.textContent).toBe('local')
    expect(screen.getByTestId('cluster-up').textContent).toBe('5/5')
    expect(screen.getByTestId('modules-count').textContent).toBe('1')
  })

  it('debounces a burst of /topic/cluster messages into ONE re-fetch', async () => {
    vi.useFakeTimers()

    const fakeApi = {
      getHealth: vi.fn().mockResolvedValue({ status: 'UP' }),
      getSystemInfo: vi.fn().mockResolvedValue({ mode: 'local' }),
      getCluster: vi.fn().mockResolvedValue({ size: 5, upCount: 5, nodes: [] }),
      getModules: vi.fn().mockResolvedValue([]),
    }

    render(
      <FakeStompProvider>
        <ClusterProvider api={fakeApi}>
          <Consumer />
        </ClusterProvider>
      </FakeStompProvider>,
    )

    // Flush initial fetch promise resolution
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(fakeApi.getCluster).toHaveBeenCalledTimes(1)

    // Verify /topic/cluster subscriber registered
    const clusterHandler = stompListeners['/topic/cluster']
    expect(clusterHandler).toBeDefined()

    // Send a burst of 3 messages in rapid succession
    act(() => {
      clusterHandler({ nodeId: 1, type: 'CRASH' })
      clusterHandler({ nodeId: 2, type: 'CRASH' })
      clusterHandler({ nodeId: 3, type: 'CRASH' })
    })

    // Advance 50ms - debounce has not expired yet
    await act(async () => {
      vi.advanceTimersByTime(50)
      await Promise.resolve()
    })
    expect(fakeApi.getCluster).toHaveBeenCalledTimes(1)

    // Advance another 120ms (total 170ms > 150ms debounce)
    await act(async () => {
      vi.advanceTimersByTime(120)
      await Promise.resolve()
      await Promise.resolve()
    })

    // Exactly one re-fetch should have occurred for the whole burst
    expect(fakeApi.getCluster).toHaveBeenCalledTimes(2)
  })

  it('re-fetches cluster and modules on STOMP reconnect', async () => {
    const fakeApi = {
      getHealth: vi.fn().mockResolvedValue({ status: 'UP' }),
      getSystemInfo: vi.fn().mockResolvedValue({ mode: 'local' }),
      getCluster: vi.fn().mockResolvedValue({ size: 5, upCount: 5, nodes: [] }),
      getModules: vi.fn().mockResolvedValue([]),
    }

    render(
      <FakeStompProvider>
        <ClusterProvider api={fakeApi}>
          <Consumer />
        </ClusterProvider>
      </FakeStompProvider>,
    )

    await screen.findByTestId('cluster-up')
    expect(fakeApi.getCluster).toHaveBeenCalledTimes(1)
    expect(fakeApi.getModules).toHaveBeenCalledTimes(1)

    // First onConnect callback fires on initial connect
    expect(connectCallbacks.length).toBeGreaterThan(0)
    await act(async () => {
      connectCallbacks[0]({})
    })
    // Initial connect does not cause duplicate fetch
    expect(fakeApi.getCluster).toHaveBeenCalledTimes(1)

    // Simulate reconnect
    await act(async () => {
      connectCallbacks[0]({})
    })

    expect(fakeApi.getCluster).toHaveBeenCalledTimes(2)
    expect(fakeApi.getModules).toHaveBeenCalledTimes(2)
  })

  it('sets error state without crashing when fetch fails', async () => {
    const fakeApi = {
      getHealth: vi.fn().mockResolvedValue({ status: 'UP' }),
      getSystemInfo: vi.fn().mockRejectedValue(new Error('Backend unreachable')),
      getCluster: vi.fn().mockRejectedValue(new Error('Backend unreachable')),
      getModules: vi.fn().mockRejectedValue(new Error('Backend unreachable')),
    }

    render(
      <FakeStompProvider>
        <ClusterProvider api={fakeApi}>
          <Consumer />
        </ClusterProvider>
      </FakeStompProvider>,
    )

    expect(await screen.findByText(/Error: Backend unreachable/)).toBeTruthy()
  })
})

describe('ClusterProvider cold start (14.1)', () => {
  function WakeConsumer() {
    const { wakingSince } = useCluster()
    return <div data-testid="waking">{wakingSince === null ? 'awake' : 'waking'}</div>
  }

  function renderWith(getHealth) {
    const fakeApi = {
      getHealth,
      getSystemInfo: vi.fn().mockResolvedValue({ mode: 'public' }),
      getCluster: vi.fn().mockResolvedValue({ size: 5, upCount: 5, nodes: [] }),
      getModules: vi.fn().mockResolvedValue([]),
    }
    render(
      <FakeStompProvider>
        <ClusterProvider api={fakeApi}>
          <WakeConsumer />
        </ClusterProvider>
      </FakeStompProvider>,
    )
    return fakeApi
  }

  const waking = () => screen.getByTestId('waking').textContent

  async function advance(ms) {
    await act(async () => {
      vi.advanceTimersByTime(ms)
      for (let i = 0; i < 5; i += 1) await Promise.resolve()
    })
  }

  beforeEach(() => {
    stompListeners = {}
    connectCallbacks = []
    vi.useFakeTimers()
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
  })

  it('a backend that answers at once never shows the waking state', async () => {
    const api = renderWith(vi.fn().mockResolvedValue({ status: 'UP' }))
    await advance(0)
    await advance(WAKE_SLOW_MS * 3)
    expect(waking()).toBe('awake')
    expect(api.getHealth).toHaveBeenCalledTimes(1)
    expect(api.getSystemInfo).toHaveBeenCalledTimes(1)
  })

  it('a health check slower than 3 s marks waking, then clears it and reloads the data when it answers', async () => {
    let answer
    const api = renderWith(vi.fn(() => new Promise((resolve) => { answer = resolve })))
    await advance(WAKE_SLOW_MS - 1)
    expect(waking()).toBe('awake')
    await advance(1)
    expect(waking()).toBe('waking')

    await act(async () => answer({ status: 'UP' }))
    await advance(0)
    expect(waking()).toBe('awake')
    expect(api.getSystemInfo).toHaveBeenCalledTimes(2)
    expect(api.getCluster).toHaveBeenCalledTimes(2)
  })

  it('a failing health check marks waking at once and retries every 3 s until the backend answers', async () => {
    const down = new Error('Backend unreachable')
    const api = renderWith(vi.fn().mockRejectedValueOnce(down).mockRejectedValueOnce(down).mockResolvedValue({ status: 'UP' }))
    await advance(0)
    expect(waking()).toBe('waking')
    expect(api.getHealth).toHaveBeenCalledTimes(1)

    await advance(WAKE_RETRY_MS - 1)
    expect(api.getHealth).toHaveBeenCalledTimes(1)
    await advance(1)
    expect(api.getHealth).toHaveBeenCalledTimes(2)
    expect(waking()).toBe('waking')

    await advance(WAKE_RETRY_MS)
    expect(api.getHealth).toHaveBeenCalledTimes(3)
    expect(waking()).toBe('awake')
    expect(api.getModules).toHaveBeenCalledTimes(2)

    await advance(WAKE_RETRY_MS * 3)
    expect(api.getHealth).toHaveBeenCalledTimes(3)
  })
})
