import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import clockSyncAfterSync from '@/test/fixtures/clocksync/overview-after-sync.json'
import ftBeforeStart from '@/test/fixtures/faulttolerance/overview-before-start.json'
import mapReduceAfterRuns from '@/test/fixtures/mapreduce/overview-after-runs.json'
import matrixLeader from '@/test/fixtures/matrix/overview-leader.json'
import mpiLeader from '@/test/fixtures/mpi/overview-leader.json'
import replicationAfterTakeover from '@/test/fixtures/replication/overview-after-takeover.json'
import * as evidenceHook from './useIntegrationEvidence'
import { IntegrationSection } from './IntegrationSection'
import { LINKS } from './integrationLinks'

const ok = (data) => ({ status: 'ok', data })

/** Node 5 crashed, node 4 elected; Experiment 5 still names node 5 (no write since). */
const CLUSTER = {
  size: 5,
  upCount: 4,
  nodes: [1, 2, 3, 4, 5].map((id) => ({
    id,
    status: id === 5 ? 'CRASHED' : 'UP',
    roles: id === 4 ? ['LEADER'] : [],
    capacity: { name: 'MEDIUM', threads: 2, workMultiplier: 2 },
  })),
}

const OVERVIEWS = {
  clocksync: ok({ ...clockSyncAfterSync, timeDaemonNodeId: 4 }),
  replication: ok(replicationAfterTakeover),
  mapreduce: ok({ ...mapReduceAfterRuns, coordinatorId: 4 }),
  faulttolerance: ok(ftBeforeStart),
  mpi: ok({ ...mpiLeader, defaultRootNodeId: 4 }),
  matrix: ok({ ...matrixLeader, defaultRootNodeId: 4 }),
}

const EVENTS = [{ sequence: 3, lamportTime: 57, nodeId: 4, module: 'election', type: 'ELECTED' }]

function renderSection({ overviews = OVERVIEWS, cluster = CLUSTER, events = EVENTS } = {}) {
  vi.spyOn(evidenceHook, 'useIntegrationEvidence').mockReturnValue({ overviews })
  return render(
    <MemoryRouter>
      <IntegrationSection cluster={cluster} events={events} />
    </MemoryRouter>,
  )
}

function mapLink(id) {
  return document.querySelector(`[data-link="${id}"]`)
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('IntegrationSection', () => {
  it('draws the ten experiments and the cluster as nodes, and L1-L6 as selectable edges', () => {
    renderSection()
    const labs = [...document.querySelectorAll('[data-lab]')].map((g) => Number(g.getAttribute('data-lab')))
    expect(labs.sort((a, b) => a - b)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    expect(screen.getByRole('img', { name: /^The shared cluster: 5 nodes/ })).toBeTruthy()
    for (const link of LINKS) {
      const edge = mapLink(link.id)
      expect(edge.getAttribute('role')).toBe('button')
      expect(edge.getAttribute('aria-label')).toBe(`${link.id}: ${link.meaning}`)
    }
  })

  it('the role marks on the map use words as well as colour', () => {
    renderSection()
    const replication = screen.getByRole('img', { name: /^Experiment 5, Replication/ })
    expect(replication.textContent).toContain('node 5 differs')
    expect(screen.getByRole('img', { name: /^Experiment 3, Clock sync/ }).textContent).toContain('node 4 matches')
    expect(screen.getByRole('img', { name: /^Experiment 4, Election/ }).textContent).toContain('leader: node 4')
    expect(screen.getByRole('img', { name: /^Experiment 8, Fault tolerance/ }).textContent).toContain('not taken yet')
  })

  it('lists the six links with their headline evidence until one is selected', () => {
    renderSection()
    const list = screen.getByRole('list')
    expect(within(list).getAllByRole('button')).toHaveLength(6)
    expect(list.textContent).toContain('Leader: node 4. 4 of 5 active roles match it. 1 not taken yet.')
    expect(list.textContent).toContain('Wired, verified by L3LoadBalancingIntoExecutorsIntegrationTest')
    expect(list.textContent).toContain('Latest event: Lamport 57 on node 4 (elected, election).')
  })

  it('clicking an edge opens its panel: meaning, evidence and See in Timeline with the module filters', () => {
    renderSection()
    fireEvent.click(mapLink('L3'))
    const panel = screen.getByTestId('link-panel')
    expect(within(panel).getByRole('heading', { level: 3 }).textContent).toBe('Experiment 6 dispatches into Experiment 2 executors')
    expect(panel.textContent).toContain('Wired, verified by L3LoadBalancingIntoExecutorsIntegrationTest.')
    expect(panel.textContent).toContain('Verified by test')
    expect(within(panel).getByRole('link', { name: 'See in Timeline' }).getAttribute('href'))
      .toBe('/timeline?modules=loadbalancing,multithreading')
    expect(mapLink('L3').getAttribute('aria-pressed')).toBe('true')
  })

  it('Enter on a focused edge opens it; the panel closes back to the list', () => {
    renderSection()
    fireEvent.keyDown(mapLink('L6'), { key: 'Enter' })
    expect(screen.getByTestId('link-panel').textContent).toContain('Run 1: size 50 from root node 5, complete.')
    fireEvent.click(screen.getByRole('button', { name: 'Close link details' }))
    expect(screen.queryByTestId('link-panel')).toBeNull()
    expect(screen.getAllByRole('list')[0].textContent).toContain('L6')
  })

  it('clicking the selected edge again deselects it', () => {
    renderSection()
    fireEvent.click(mapLink('L2'))
    fireEvent.click(mapLink('L2'))
    expect(screen.queryByTestId('link-panel')).toBeNull()
  })

  it('the L1 panel shows every role with "matches leader", and the mismatch with its reason', () => {
    renderSection()
    fireEvent.click(within(screen.getByRole('list')).getByRole('button', { name: /^L1/ }))
    const panel = screen.getByTestId('link-panel')
    const rows = panel.querySelectorAll('tbody tr')
    expect(rows).toHaveLength(6)
    const states = [...rows].map((r) => r.getAttribute('data-role-state'))
    expect(states).toEqual(['match', 'mismatch', 'match', 'idle', 'match', 'match'])
    expect(rows[1].textContent).toContain('Differs')
    expect(panel.textContent).toContain(
      'Node 5 (crashed) was made primary before the leader changed; the primary moves to node 4 at the next accepted write.',
    )
    expect(within(panel).getByRole('link', { name: 'See in Timeline' }).getAttribute('href'))
      .toBe('/timeline?modules=cluster,election,clocksync,replication,mapreduce,faulttolerance,mpi,matrix&order=arrival')
  })

  it('with nothing read yet every link falls back to its test, and nothing says "planned"', () => {
    renderSection({ overviews: {}, cluster: { ...CLUSTER, nodes: CLUSTER.nodes.map((n) => ({ ...n, roles: [] })) }, events: [] })
    const section = screen.getByTestId('integration-section')
    expect(section.textContent.match(/Wired, verified by/g)).toHaveLength(6)
    expect(section.textContent).not.toMatch(/planned|coming soon|phase 13|TODO|lowest live/i)
  })
})
