import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { EventStream } from './EventStream'
import { formatEventType, formatRelativeTime } from './eventFormatters'

afterEach(cleanup)

const MOCK_EVENTS = [
  {
    sequence: 1,
    lamportTime: 10,
    nodeId: 0,
    type: 'CLUSTER_RESET',
    message: 'Cluster reset clean slate',
    wallTime: new Date(Date.now() - 60000).toISOString(),
  },
  {
    sequence: 2,
    lamportTime: 12,
    nodeId: 2,
    type: 'NODE_CRASHED',
    message: 'Node 2 crashed across all services',
    wallTime: new Date(Date.now() - 10000).toISOString(),
  },
]

describe('EventStream', () => {
  it('formats event types to sentence case words', () => {
    expect(formatEventType('NODE_CRASHED')).toBe('Node crashed')
    expect(formatEventType('CLUSTER_RESET')).toBe('Cluster reset')
    expect(formatEventType('HEARTBEAT')).toBe('Heartbeat')
  })

  it('formats relative time accurately', () => {
    expect(formatRelativeTime(null)).toBe('—')
    expect(formatRelativeTime(new Date(Date.now() - 2000).toISOString())).toBe('just now')
    expect(formatRelativeTime(new Date(Date.now() - 30000).toISOString())).toBe('30s ago')
    expect(formatRelativeTime(new Date(Date.now() - 120000).toISOString())).toBe('2m ago')
  })

  it('renders events with newest first', () => {
    render(<EventStream events={MOCK_EVENTS} />)

    const items = screen.getAllByText(/L:\d+/)
    expect(items).toHaveLength(2)
    // Newest event (seq 2, L:12) should appear before event 1 (L:10)
    expect(items[0].textContent).toBe('L:12')
    expect(items[1].textContent).toBe('L:10')

    expect(screen.getByText('Cluster')).toBeTruthy()
    expect(screen.getByText('Node 2')).toBeTruthy()
    expect(screen.getByText('Node 2 crashed across all services')).toBeTruthy()
    expect(screen.getByText('Cluster reset clean slate')).toBeTruthy()
  })

  it('renders loading state', () => {
    render(<EventStream loading={true} />)
    expect(screen.getByText(/loading event history/i)).toBeTruthy()
  })

  it('renders error state with detail', () => {
    const error = { message: 'Failed to fetch', detail: 'Connection refused' }
    render(<EventStream error={error} />)
    expect(screen.getByText(/unable to load event stream/i)).toBeTruthy()
    expect(screen.getByText('Connection refused')).toBeTruthy()
  })

  it('renders empty state when there are no events', () => {
    render(<EventStream events={[]} />)
    expect(screen.getByText(/no events recorded yet/i)).toBeTruthy()
  })
})
