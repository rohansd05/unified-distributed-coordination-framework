import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ClusterPage } from './ClusterPage'
import { ApiError } from '@/services/api'
import * as useClusterModule from '@/hooks/useCluster'
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

describe('ClusterPage', () => {
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
    }

    vi.spyOn(useClusterModule, 'useCluster').mockReturnValue({
      cluster: { size: 5, upCount: 4, nodes: MOCK_NODES },
      loading: false,
      error: null,
      refresh: vi.fn(),
    })
  })

  it('renders a row per node with status, capacity and monospace ports', () => {
    render(<ClusterPage api={mockApi} />)

    expect(screen.getByTestId('node-row-1')).toBeTruthy()
    expect(screen.getByTestId('node-row-2')).toBeTruthy()

    // Status
    expect(screen.getAllByText('Up').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Crashed').length).toBeGreaterThan(0)

    // Capacity
    expect(screen.getAllByText('FAST').length).toBeGreaterThan(0)
    expect(screen.getAllByText(/4 threads, 1x work/).length).toBeGreaterThan(0)

    // Monospace ports
    expect(screen.getAllByText('1101').length).toBeGreaterThan(0)
    expect(screen.getAllByText('6001').length).toBeGreaterThan(0)
    expect(screen.getAllByText('7001').length).toBeGreaterThan(0)
    expect(screen.getAllByText('7101').length).toBeGreaterThan(0)
    expect(screen.getAllByText('7201').length).toBeGreaterThan(0)
    expect(screen.getAllByText('7301').length).toBeGreaterThan(0)

    // Services
    expect(screen.getAllByText('clock, election').length).toBeGreaterThan(0)
    expect(screen.getAllByText('None running yet').length).toBeGreaterThan(0)
  })

  it('renders from cluster-node2-crashed contract fixture: node 2 shows "Crashed" and action "Recover node 2"', () => {
    vi.spyOn(useClusterModule, 'useCluster').mockReturnValue({
      cluster: clusterFixture,
      loading: false,
      error: null,
      refresh: vi.fn(),
    })

    render(<ClusterPage api={mockApi} />)

    // Both desktop row and mobile block show Crashed for node 2
    const node2Row = screen.getByTestId('node-row-2')
    expect(node2Row.textContent).toContain('Crashed')

    const node2Mobile = screen.getByTestId('mobile-node-2')
    expect(node2Mobile.textContent).toContain('Crashed')

    // Action button on both desktop row and mobile block is Recover node 2
    const recoverButtons = screen.getAllByRole('button', { name: /Recover node 2/i })
    expect(recoverButtons.length).toBe(2)
  })

  it('renders both desktop table and mobile stacked block with matching node info (D3)', () => {
    render(<ClusterPage api={mockApi} />)

    const table = screen.getByTestId('cluster-nodes-table')
    const mobileContainer = screen.getByTestId('cluster-nodes-mobile')

    expect(table).toBeTruthy()
    expect(mobileContainer).toBeTruthy()

    // Both containers render details for all nodes
    MOCK_NODES.forEach((node) => {
      expect(screen.getByTestId(`node-row-${node.id}`)).toBeTruthy()
      expect(screen.getByTestId(`mobile-node-${node.id}`)).toBeTruthy()
    })
  })

  it('uses singular "1 thread" for capacity with 1 thread (D5)', () => {
    render(<ClusterPage api={mockApi} />)

    // Node 3 has 1 thread
    expect(screen.getAllByText(/1 thread, 4x work/).length).toBeGreaterThan(0)
  })

  it('calls api.crashNode(1) and displays honest toast description on success (D5)', async () => {
    render(<ClusterPage api={mockApi} />)

    // Node 1 has running services: ['clock', 'election']
    const crashButton = screen.getAllByRole('button', { name: /Crash node 1/i })[0]
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

  it('shows honest toast "No services were running on it yet." when crashing node with no services (D5)', async () => {
    render(<ClusterPage api={mockApi} />)

    // Node 3 has no running services
    const crashButton = screen.getAllByRole('button', { name: /Crash node 3/i })[0]
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

  it('shows error toast with detail on 409 ApiError', async () => {
    const error409 = new ApiError({
      status: 409,
      title: 'Node state conflict',
      detail: 'Node 2 is already crashed',
      nodeId: 2,
    })
    mockApi.recoverNode.mockRejectedValueOnce(error409)

    render(<ClusterPage api={mockApi} />)

    const recoverButton = screen.getAllByRole('button', { name: /Recover node 2/i })[0]
    fireEvent.click(recoverButton)

    await waitFor(() => {
      expect(mockApi.recoverNode).toHaveBeenCalledWith(2)
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({
          variant: 'destructive',
          title: 'Recovery failed',
          description: 'Node 2 is already crashed',
        }),
      )
    })
  })

  it('disables button while mutation is pending', async () => {
    let resolveCrash
    mockApi.crashNode.mockReturnValue(new Promise((resolve) => {
      resolveCrash = resolve
    }))

    render(<ClusterPage api={mockApi} />)

    const crashButton = screen.getAllByRole('button', { name: /Crash node 1/i })[0]
    fireEvent.click(crashButton)

    expect(screen.getAllByRole('button', { name: /Crashing node 1…/i })[0].disabled).toBe(true)

    resolveCrash({})
    await waitFor(() => {
      expect(screen.getAllByRole('button', { name: /Crash node 1/i })[0].disabled).toBe(false)
    })
  })

  it('opens reset dialog; Cancel does not call reset; confirming calls api.resetCluster (D5 wording)', async () => {
    render(<ClusterPage api={mockApi} />)

    // Click Reset cluster trigger button
    const openButton = screen.getByRole('button', { name: 'Reset cluster' })
    fireEvent.click(openButton)

    // Dialog content is visible with correct clock reset wording
    expect(screen.getByText('Reset cluster to clean slate?')).toBeTruthy()
    expect(
      screen.getByText(/This recovers every node, restarts all Lamport clocks to 0 \(the reset event is stamped 1\), and clears the event history/),
    ).toBeTruthy()

    // Clicking Cancel does not call api
    const cancelButton = screen.getByRole('button', { name: 'Cancel' })
    fireEvent.click(cancelButton)
    expect(mockApi.resetCluster).not.toHaveBeenCalled()

    // Open again and confirm
    fireEvent.click(openButton)
    const dialogConfirmButtons = screen.getAllByRole('button', { name: 'Reset cluster' })
    const confirmButton = dialogConfirmButtons[dialogConfirmButtons.length - 1]
    fireEvent.click(confirmButton)

    await waitFor(() => {
      expect(mockApi.resetCluster).toHaveBeenCalledTimes(1)
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'Cluster reset',
        }),
      )
    })
  })
})
