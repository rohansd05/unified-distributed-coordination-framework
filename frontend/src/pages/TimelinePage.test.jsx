import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import * as useStompModule from '@/hooks/useStomp'
import { PROVIDER_FUTURE, ROUTER_FUTURE } from '@/routes'
import { TimelinePage } from './TimelinePage'

const ev = (sequence, lamportTime, nodeId, type, module, data = {}, message = null) =>
  ({ sequence, lamportTime, nodeId, type, module, data, message, peerId: null, wallTime: '2026-10-18T10:00:00Z' })

// The leader-crash chain as the bus delivers it: the role changes carry the new holder's clock,
// LEADER_CHANGED the cluster clock, so Lamport order puts LEADER_CHANGED (L=4) first only by luck.
const EVENTS = [
  ev(1, 2, 0, 'LEADER_CHANGED', 'cluster', { leaderId: 5, previousLeaderId: null }),
  ev(2, 12, 5, 'NODE_CRASHED', 'cluster', { services: [], failures: [] }),
  ev(3, 3, 0, 'LEADER_CHANGED', 'cluster', { leaderId: null, previousLeaderId: 5 }),
  ev(4, 4, 0, 'LEADER_CHANGED', 'cluster', { leaderId: 4, previousLeaderId: null }),
  ev(5, 30, 4, 'TIME_DAEMON_CHANGED', 'clocksync', { nodeId: 4, previousNodeId: 5, cause: 'LEADER_CRASHED' }),
  ev(6, 18, 4, 'COORDINATOR_CHANGED', 'mapreduce', { nodeId: 4, previousNodeId: 5, cause: 'LEADER_CRASHED' }),
  ev(7, 9, 2, 'WIDGET_WOBBLED', 'mystery', { wobble: 3 }),
]

let stomp
let onMessage

function renderPage(path = '/timeline', events = EVENTS) {
  const api = { getEvents: vi.fn().mockResolvedValue(events) }
  const router = createMemoryRouter([{ path: '/timeline', element: <TimelinePage api={api} /> }],
    { initialEntries: [path], future: ROUTER_FUTURE })
  render(<RouterProvider router={router} future={PROVIDER_FUTURE} />)
  return { api, router }
}

const rows = () => screen.queryAllByTestId('timeline-row').map((r) => r.textContent)

beforeEach(() => {
  stomp = {
    status: 'connected',
    subscribe: vi.fn((destination, handler) => { onMessage = handler; return () => {} }),
    onConnect: vi.fn(() => () => {}),
  }
  vi.spyOn(useStompModule, 'useStomp').mockImplementation(() => stomp)
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('TimelinePage', () => {
  it('shows lanes and the list in (lamportTime, nodeId) order, newest first, with readable sentences', async () => {
    renderPage()
    await waitFor(() => expect(rows()).toHaveLength(7))
    expect(rows()[0]).toContain('Node 4 became the Berkeley time daemon (was node 5), after node 5 crashed')
    expect(rows().at(-1)).toContain('Node 5 became leader')
    expect(screen.getAllByTestId('lane-marker')).toHaveLength(7)
    expect(screen.getByText(/the bus sequence is what guarantees/i)).toBeTruthy()
  })

  it('orders by arrival when toggled, and keeps the order in the URL', async () => {
    const { router } = renderPage()
    await waitFor(() => expect(rows()).toHaveLength(7))
    fireEvent.click(screen.getByRole('button', { name: 'Arrival order (bus sequence)' }))
    expect(router.state.location.search).toBe('?order=arrival')
    expect(rows().map((r) => r.match(/#(\d+)$/)[1])).toEqual(['7', '6', '5', '4', '3', '2', '1'])
  })

  it('applies deep-linked filters and writes chip changes back to the URL', async () => {
    const { router } = renderPage('/timeline?modules=clocksync,mapreduce')
    await waitFor(() => expect(rows()).toHaveLength(2))
    fireEvent.click(within(screen.getByRole('group', { name: 'Nodes' })).getByRole('button', { name: 'Node 2' }))
    expect(router.state.location.search).toBe('?modules=clocksync%2Cmapreduce&nodes=2')
    expect(screen.getByText('No events match these filters.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(router.state.location.search).toBe('')
    expect(rows()).toHaveLength(7)
  })

  it('filters by type text', async () => {
    renderPage('/timeline?q=coordinator')
    await waitFor(() => expect(rows()).toHaveLength(1))
    expect(rows()[0]).toContain('MapReduce coordinator')
  })

  it('highlights the leader chain: the latest LEADER_CHANGED and the roles that followed it', async () => {
    renderPage()
    await waitFor(() => expect(rows()).toHaveLength(7))
    expect(screen.getByTestId('leader-chain').textContent)
      .toBe('Node 4 became leader. Then 2 roles followed it to node 4: time daemon, MapReduce coordinator.')
    const chained = rows().filter((r) => r.includes('Leader chain'))
    expect(chained).toHaveLength(3)
    expect(screen.getAllByTestId('lane-marker').filter((m) => m.getAttribute('aria-label').includes('leader chain'))).toHaveLength(3)
  })

  it('shows an unknown type by its raw type and its full data on click', async () => {
    renderPage()
    await waitFor(() => expect(rows()).toHaveLength(7))
    const row = screen.getAllByTestId('timeline-row').find((r) => r.textContent.includes('#7'))
    expect(row.textContent).toContain('WIDGET_WOBBLED')
    fireEvent.click(row)
    const detail = screen.getByTestId('event-detail')
    expect(detail.textContent).toContain('"wobble": 3')
    expect(detail.textContent).toContain('mystery')
  })

  it('adds live events, deduplicated, and holds them while paused', async () => {
    renderPage()
    await waitFor(() => expect(rows()).toHaveLength(7))
    fireEvent.click(screen.getByRole('button', { name: 'Pause live' }))
    act(() => { onMessage(ev(8, 50, 3, 'NODE_RECOVERED', 'cluster')); onMessage(ev(8, 50, 3, 'NODE_RECOVERED', 'cluster')) })
    expect(rows()).toHaveLength(7)
    fireEvent.click(screen.getByRole('button', { name: 'Resume live (1 waiting)' }))
    expect(rows()).toHaveLength(8)
    expect(rows()[0]).toContain('Node 3 recovered')
  })

  it('clears the view on the client only, then shows new events', async () => {
    const { api } = renderPage()
    await waitFor(() => expect(rows()).toHaveLength(7))
    fireEvent.click(screen.getByRole('button', { name: 'Clear view' }))
    expect(screen.getByText('View cleared. New events appear here as they arrive.')).toBeTruthy()
    act(() => onMessage(ev(9, 60, 1, 'NODE_CRASHED', 'cluster')))
    expect(rows()).toHaveLength(1)
    expect(api.getEvents).toHaveBeenCalledTimes(1)
  })

  it('shows an empty state with no events', async () => {
    renderPage('/timeline', [])
    expect(await screen.findByTestId('timeline-empty')).toBeTruthy()
    expect(screen.getByText(/No events yet/)).toBeTruthy()
    expect(screen.queryByTestId('disconnected-banner')).toBeNull()
  })

  it('shows a calm banner while live updates reconnect', async () => {
    stomp = { ...stomp, status: 'reconnecting' }
    renderPage()
    expect((await screen.findByTestId('disconnected-banner')).textContent)
      .toMatch(/^Live updates disconnected, retrying\./)
  })

  it('never says planned or coming soon', async () => {
    renderPage()
    await waitFor(() => expect(rows()).toHaveLength(7))
    expect(document.body.textContent).not.toMatch(/planned|coming soon|Phase 13|lowest live/i)
  })
})
