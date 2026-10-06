import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { OverviewPage } from './OverviewPage'
import * as useClusterModule from '@/hooks/useCluster'
import * as useEventStreamModule from '@/hooks/useEventStream'
import * as useToastModule from '@/hooks/use-toast'
import clusterFixture from '@/test/fixtures/cluster-node2-crashed.json'

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

const MOCK_NODES = [
  {
    id: 1,
    status: 'UP',
    roles: [],
    capacity: { name: 'FAST', threads: 4, workMultiplier: 1 },
    ports: { rmi: 1101, clock: 6001, election: 7001, replication: 7101, requests: 7201, mapreduce: 7301 },
    runningServices: ['clock', 'election'],
  },
  {
    id: 2,
    status: 'CRASHED',
    roles: [],
    capacity: { name: 'MEDIUM', threads: 2, workMultiplier: 2 },
    ports: { rmi: 1102, clock: 6002, election: 7002, replication: 7102, requests: 7202, mapreduce: 7302 },
    runningServices: [],
  },
  {
    id: 3,
    status: 'UP',
    roles: [],
    capacity: { name: 'SLOW', threads: 1, workMultiplier: 4 },
    ports: { rmi: 1103, clock: 6003, election: 7003, replication: 7103, requests: 7203, mapreduce: 7303 },
    runningServices: [],
  },
]

describe('OverviewPage', () => {
  let mockApi
  let mockToast

  beforeEach(() => {
    mockToast = vi.fn()
    vi.spyOn(useToastModule, 'useToast').mockReturnValue({
      toast: mockToast,
      toasts: [],
      dismiss: vi.fn(),
    })

    mockApi = {
      crashNode: vi.fn().mockResolvedValue({}),
      recoverNode: vi.fn().mockResolvedValue({}),
      resetCluster: vi.fn().mockResolvedValue({}),
      getEvents: vi.fn().mockResolvedValue([]),
    }

    vi.spyOn(useClusterModule, 'useCluster').mockReturnValue({
      cluster: { size: 5, upCount: 4, nodes: MOCK_NODES },
      loading: false,
      error: null,
      refresh: vi.fn(),
    })

    vi.spyOn(useEventStreamModule, 'useEventStream').mockReturnValue({
      events: [
        {
          sequence: 1,
          lamportTime: 10,
          nodeId: 2,
          type: 'NODE_CRASHED',
          message: 'Node 2 crashed',
          wallTime: new Date().toISOString(),
        },
      ],
      loading: false,
      error: null,
    })
  })

  it('renders health statement "4 of 5 nodes up"', () => {
    render(
      <MemoryRouter>
        <OverviewPage api={mockApi} />
      </MemoryRouter>,
    )

    const health = screen.getByTestId('health-statement')
    expect(health.textContent).toContain('4 of 5 nodes up')
  })

  it('renders from cluster-node2-crashed fixture: selecting node 2 shows "Crashed" and action "Recover node 2"', async () => {
    vi.spyOn(useClusterModule, 'useCluster').mockReturnValue({
      cluster: clusterFixture,
      loading: false,
      error: null,
      refresh: vi.fn(),
    })

    render(
      <MemoryRouter>
        <OverviewPage api={mockApi} />
      </MemoryRouter>,
    )

    // Click node 2 on the graph to inspect it
    const node2Btn = screen.getByRole('button', { name: /Node 2, crashed/i })
    fireEvent.click(node2Btn)

    const inspector = screen.getByTestId('node-inspector')
    expect(inspector.textContent).toContain('Node 2')
    expect(inspector.textContent).toContain('Crashed')

    const recoverBtn = screen.getByRole('button', { name: /Recover node 2/i })
    expect(recoverBtn).toBeTruthy()
  })

  it('formats singular "1 worker thread" for node with 1 thread (D5)', () => {
    render(
      <MemoryRouter>
        <OverviewPage api={mockApi} />
      </MemoryRouter>,
    )

    // Select node 3 (1 thread)
    const node3Btn = screen.getByRole('button', { name: /Node 3, up/i })
    fireEvent.click(node3Btn)

    const inspector = screen.getByTestId('node-inspector')
    expect(inspector.textContent).toContain('1 worker thread')
  })

  it('renders topology graph and live event stream', () => {
    render(
      <MemoryRouter>
        <OverviewPage api={mockApi} />
      </MemoryRouter>,
    )

    expect(screen.getByRole('region', { name: /cluster topology graph/i })).toBeTruthy()
    expect(screen.getByTestId('event-stream')).toBeTruthy()
    expect(screen.getByText('Node 2 crashed')).toBeTruthy()
  })

  it('shows loading state when cluster is loading', () => {
    vi.spyOn(useClusterModule, 'useCluster').mockReturnValue({
      cluster: null,
      loading: true,
      error: null,
      refresh: vi.fn(),
    })

    render(
      <MemoryRouter>
        <OverviewPage api={mockApi} />
      </MemoryRouter>,
    )

    expect(screen.getByTestId('overview-loading')).toBeTruthy()
  })

  it('shows error state with directions when cluster fetch fails', () => {
    vi.spyOn(useClusterModule, 'useCluster').mockReturnValue({
      cluster: null,
      loading: false,
      error: new Error('Failed to connect'),
      refresh: vi.fn(),
    })

    render(
      <MemoryRouter>
        <OverviewPage api={mockApi} />
      </MemoryRouter>,
    )

    expect(screen.getByTestId('overview-error')).toBeTruthy()
    expect(screen.getByText(/can't reach the backend at/i)).toBeTruthy()
    expect(screen.getByText(/cd backend; \.\\mvnw\.cmd spring-boot:run/i)).toBeTruthy()
  })

  it('triggers crash action and honest toast description for node with services (D5)', async () => {
    render(
      <MemoryRouter>
        <OverviewPage api={mockApi} />
      </MemoryRouter>,
    )

    // Node 1 is selected by default and has ['clock', 'election'] running
    const crashButton = screen.getByRole('button', { name: /Crash node 1/i })
    fireEvent.click(crashButton)

    await waitFor(() => {
      expect(mockApi.crashNode).toHaveBeenCalledWith(1)
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'Node 1 crashed',
          description: 'Stopped services: clock, election.',
        }),
      )
    })
  })

  it('shows honest toast "No services were running on it yet." when crashing node without services (D5)', async () => {
    render(
      <MemoryRouter>
        <OverviewPage api={mockApi} />
      </MemoryRouter>,
    )

    // Select node 3 (no running services)
    const node3Btn = screen.getByRole('button', { name: /Node 3, up/i })
    fireEvent.click(node3Btn)

    const crashButton = screen.getByRole('button', { name: /Crash node 3/i })
    fireEvent.click(crashButton)

    await waitFor(() => {
      expect(mockApi.crashNode).toHaveBeenCalledWith(3)
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'Node 3 crashed',
          description: 'No services were running on it yet.',
        }),
      )
    })
  })

  it('opens reset dialog with truthful clock restart wording (D5)', () => {
    render(
      <MemoryRouter>
        <OverviewPage api={mockApi} />
      </MemoryRouter>,
    )

    const resetTrigger = screen.getByRole('button', { name: 'Reset cluster' })
    fireEvent.click(resetTrigger)

    expect(
      screen.getByText(/restarts all Lamport clocks to 0 \(the reset event is stamped 1\)/i),
    ).toBeTruthy()
  })
})
