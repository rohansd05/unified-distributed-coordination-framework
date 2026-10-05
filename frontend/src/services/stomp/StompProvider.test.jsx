import { useEffect } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen } from '@testing-library/react'
import { StompProvider } from './StompProvider'
import { useStomp } from '@/hooks/useStomp'

let lastClient = null
let lastConfig = null

vi.mock('@stomp/stompjs', () => {
  class MockClient {
    constructor(config) {
      this.config = config
      lastConfig = config
      lastClient = this
      this.connected = false
      this.active = false
      this.stompSubscriptions = new Map()
    }

    activate() {
      this.active = true
    }

    deactivate() {
      this.active = false
      this.connected = false
      return Promise.resolve()
    }

    subscribe(destination, callback, headers = {}) {
      const id = headers.id || `sub-${Math.random()}`
      const entry = { id, destination, callback }
      this.stompSubscriptions.set(id, entry)
      return {
        id,
        unsubscribe: () => {
          this.stompSubscriptions.delete(id)
        },
      }
    }

    simulateConnect(frame = {}) {
      this.connected = true
      this.config.onConnect?.(frame)
    }

    simulateClose() {
      this.connected = false
      this.config.onWebSocketClose?.()
    }

    simulateMessage(destination, body) {
      this.stompSubscriptions.forEach((sub) => {
        if (sub.destination === destination) {
          sub.callback({ body: typeof body === 'object' ? JSON.stringify(body) : body })
        }
      })
    }
  }

  return {
    Client: MockClient,
    ReconnectionTimeMode: { LINEAR: 0, EXPONENTIAL: 1 },
  }
})

afterEach(() => {
  cleanup()
  lastClient = null
  lastConfig = null
})

function StatusDisplay({ onRender }) {
  const { status } = useStomp()
  useEffect(() => {
    onRender?.(status)
  }, [status, onRender])
  return <div data-testid="stomp-status">{status}</div>
}

describe('StompProvider', () => {
  it('configures client with 10000/10000 heartbeats and reconnect options', () => {
    render(
      <StompProvider wsUrl="ws://localhost:8080/ws">
        <StatusDisplay />
      </StompProvider>,
    )

    expect(lastConfig.brokerURL).toBe('ws://localhost:8080/ws')
    expect(lastConfig.heartbeatIncoming).toBe(10000)
    expect(lastConfig.heartbeatOutgoing).toBe(10000)
    expect(lastConfig.reconnectDelay).toBe(1000)
    expect(lastConfig.maxReconnectDelay).toBe(30000)
    expect(lastClient.active).toBe(true)
  })

  it('handles status transitions: connecting -> connected -> reconnecting -> connected', () => {
    const statuses = []
    render(
      <StompProvider wsUrl="ws://localhost:8080/ws">
        <StatusDisplay onRender={(s) => statuses.push(s)} />
      </StompProvider>,
    )

    expect(screen.getByTestId('stomp-status').textContent).toBe('connecting')

    act(() => {
      lastClient.simulateConnect()
    })
    expect(screen.getByTestId('stomp-status').textContent).toBe('connected')

    act(() => {
      lastClient.simulateClose()
    })
    expect(screen.getByTestId('stomp-status').textContent).toBe('reconnecting')

    act(() => {
      lastClient.simulateConnect()
    })
    expect(screen.getByTestId('stomp-status').textContent).toBe('connected')
  })

  it('re-subscribes all active subscriptions after reconnect', () => {
    const received = []

    function Consumer() {
      const { subscribe } = useStomp()
      useEffect(() => {
        const unsub = subscribe('/topic/events', (msg) => {
          received.push(msg)
        })
        return unsub
      }, [subscribe])
      return <div>Consumer</div>
    }

    render(
      <StompProvider wsUrl="ws://localhost:8080/ws">
        <Consumer />
      </StompProvider>,
    )

    // Connect initially
    lastClient.simulateConnect()
    expect(lastClient.stompSubscriptions.size).toBe(1)

    lastClient.simulateMessage('/topic/events', { sequence: 1, type: 'CRASH' })
    expect(received).toEqual([{ sequence: 1, type: 'CRASH' }])

    // Drop connection
    lastClient.simulateClose()

    // Reconnect - mock client cleared previous active broker subs
    lastClient.stompSubscriptions.clear()
    lastClient.simulateConnect()

    // Subscriptions should be re-established
    expect(lastClient.stompSubscriptions.size).toBe(1)

    lastClient.simulateMessage('/topic/events', { sequence: 2, type: 'RECOVER' })
    expect(received).toEqual([
      { sequence: 1, type: 'CRASH' },
      { sequence: 2, type: 'RECOVER' },
    ])
  })

  it('stops delivery when unsubscribed and does not re-subscribe on reconnect', () => {
    const received = []

    function Consumer({ active }) {
      const { subscribe } = useStomp()
      useEffect(() => {
        if (!active) return undefined
        const unsub = subscribe('/topic/test', (data) => received.push(data))
        return unsub
      }, [subscribe, active])
      return null
    }

    const { rerender } = render(
      <StompProvider wsUrl="ws://localhost:8080/ws">
        <Consumer active={true} />
      </StompProvider>,
    )

    lastClient.simulateConnect()
    expect(lastClient.stompSubscriptions.size).toBe(1)

    // Unsubscribe via rerender with active=false
    rerender(
      <StompProvider wsUrl="ws://localhost:8080/ws">
        <Consumer active={false} />
      </StompProvider>,
    )
    expect(lastClient.stompSubscriptions.size).toBe(0)

    // Disconnect and reconnect
    lastClient.simulateClose()
    lastClient.stompSubscriptions.clear()
    lastClient.simulateConnect()

    // Should NOT have re-subscribed
    expect(lastClient.stompSubscriptions.size).toBe(0)
  })

  it('parses JSON bodies before passing to handler', () => {
    let messageData = null

    function Consumer() {
      const { subscribe } = useStomp()
      useEffect(() => {
        return subscribe('/topic/cluster', (data) => {
          messageData = data
        })
      }, [subscribe])
      return null
    }

    render(
      <StompProvider wsUrl="ws://localhost:8080/ws">
        <Consumer />
      </StompProvider>,
    )

    lastClient.simulateConnect()
    lastClient.simulateMessage('/topic/cluster', '{"nodeId":2,"status":"DOWN"}')
    expect(messageData).toEqual({ nodeId: 2, status: 'DOWN' })
  })

  it('deactivates client on unmount', () => {
    const { unmount } = render(
      <StompProvider wsUrl="ws://localhost:8080/ws">
        <StatusDisplay />
      </StompProvider>,
    )

    expect(lastClient.active).toBe(true)
    unmount()
    expect(lastClient.active).toBe(false)
  })
})
