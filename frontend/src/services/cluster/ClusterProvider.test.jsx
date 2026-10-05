import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ClusterProvider } from './ClusterProvider'
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
