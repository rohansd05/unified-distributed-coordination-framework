import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ClusterGraph } from './ClusterGraph'
import * as useClusterModule from '@/hooks/useCluster'
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
    runningServices: [],
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

describe('ClusterGraph', () => {
  it('renders one node per cluster node with descriptive accessible names', () => {
    render(<ClusterGraph nodes={MOCK_NODES} />)

    const node1 = screen.getByRole('button', { name: /Node 1, up, fast capacity, 4 threads/i })
    const node2 = screen.getByRole('button', { name: /Node 2, crashed, medium capacity, 2 threads/i })
    const node3 = screen.getByRole('button', { name: /Node 3, up, slow capacity, 1 thread/i })

    expect(node1).toBeTruthy()
    expect(node2).toBeTruthy()
    expect(node3).toBeTruthy()
  })

  it('renders explicit "Crashed" text for a crashed node', () => {
    render(<ClusterGraph nodes={MOCK_NODES} />)

    const crashedTexts = screen.getAllByText('Crashed')
    expect(crashedTexts.length).toBeGreaterThanOrEqual(1)
  })

  it('renders from cluster-node2-crashed contract fixture: node 2 has crashed state and accessible name containing "crashed"', () => {
    render(<ClusterGraph nodes={clusterFixture.nodes} />)

    const node2 = screen.getByRole('button', { name: /Node 2, crashed/i })
    expect(node2).toBeTruthy()

    const crashedText = screen.getByText('Crashed')
    expect(crashedText).toBeTruthy()
  })

  it('marks the node whose roles include LEADER, in the backend\'s upper case or in lower case', () => {
    const withLeader = MOCK_NODES.map((node) => (node.id === 3 ? { ...node, roles: ['LEADER'] } : node))
    render(<ClusterGraph nodes={withLeader} />)
    expect(screen.getAllByLabelText('Elected cluster leader')).toHaveLength(1)

    cleanup()

    const lowerCase = MOCK_NODES.map((node) => (node.id === 1 ? { ...node, roles: ['leader'] } : node))
    render(<ClusterGraph nodes={lowerCase} />)
    expect(screen.getAllByLabelText('Elected cluster leader')).toHaveLength(1)

    cleanup()

    render(<ClusterGraph nodes={MOCK_NODES} />)
    expect(screen.queryByLabelText('Elected cluster leader')).toBeNull()
  })

  it('displays explicit SVG focus ring on keyboard focus; selection remains unchanged until Enter (D2)', () => {
    const handleSelect = vi.fn()
    render(<ClusterGraph nodes={MOCK_NODES} selectedNodeId={1} onSelectNode={handleSelect} />)

    // Focus node 2
    const node2 = screen.getByRole('button', { name: /Node 2, crashed/i })

    // Before focus, no focus ring
    expect(screen.queryByTestId('focus-ring')).toBeNull()

    // Focus node 2
    fireEvent.focus(node2)

    // Focus ring element is displayed
    const focusRing = screen.getByTestId('focus-ring')
    expect(focusRing).toBeTruthy()
    expect(focusRing.getAttribute('stroke-dasharray')).toBe('4 3')

    // Selection has NOT changed on focus
    expect(handleSelect).not.toHaveBeenCalled()
    expect(node2.getAttribute('aria-pressed')).toBe('false')

    // Pressing Enter activates selection
    fireEvent.keyDown(node2, { key: 'Enter' })
    expect(handleSelect).toHaveBeenCalledWith(2)

    // Blurring node removes focus ring
    fireEvent.blur(node2)
    expect(screen.queryByTestId('focus-ring')).toBeNull()
  })

  it('falls back to useCluster when nodes prop is omitted', () => {
    vi.spyOn(useClusterModule, 'useCluster').mockReturnValue({
      cluster: { nodes: MOCK_NODES },
    })

    render(<ClusterGraph />)
    expect(screen.getByRole('button', { name: /Node 1, up/i })).toBeTruthy()
  })

  it('calls onSelectNode on click', () => {
    const handleSelect = vi.fn()
    render(<ClusterGraph nodes={MOCK_NODES} onSelectNode={handleSelect} />)

    const node1 = screen.getByRole('button', { name: /Node 1, up/i })
    fireEvent.click(node1)

    expect(handleSelect).toHaveBeenCalledWith(1)
  })

  it('supports keyboard navigation via Enter and Space keys', () => {
    const handleSelect = vi.fn()
    render(<ClusterGraph nodes={MOCK_NODES} onSelectNode={handleSelect} />)

    const node2 = screen.getByRole('button', { name: /Node 2, crashed/i })

    fireEvent.keyDown(node2, { key: 'Enter' })
    expect(handleSelect).toHaveBeenCalledWith(2)

    fireEvent.keyDown(node2, { key: ' ' })
    expect(handleSelect).toHaveBeenCalledWith(2)
  })

  it('renders empty fallback when no nodes are available', () => {
    render(<ClusterGraph nodes={[]} />)
    expect(screen.getByText(/no cluster nodes available/i)).toBeTruthy()
  })
})
