import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import * as useStompModule from '@/hooks/useStomp'
import * as useModuleEventsModule from '@/hooks/useModuleEvents'
import { ClusterContext } from '@/services/cluster/ClusterContext'
import { ApiError } from '@/services/api'
import { findBySlug } from '@/lib/experiments'
import { modulePages } from '@/modules/registry'
import overviewInitial from '@/test/fixtures/election/overview-initial.json'
import overviewAfterBully from '@/test/fixtures/election/overview-after-bully.json'
import overviewAfterRecovery from '@/test/fixtures/election/overview-after-recovery.json'
import startBully from '@/test/fixtures/election/start-bully.json'
import events from '@/test/fixtures/election/events.json'
import eventsCluster from '@/test/fixtures/election/events-cluster.json'
import clusterWithLeader from '@/test/fixtures/election/cluster-with-leader.json'
import clusterLeaderCrashed from '@/test/fixtures/election/cluster-leader-crashed.json'
import { ElectionPage } from './ElectionPage'

const experiment = findBySlug('4-election')

/** The module's whole api, mocked at its boundary: no test reaches the network. */
function makeApi({ overviews = [overviewAfterRecovery] } = {}) {
  const getOverview = vi.fn()
  overviews.forEach((overview) => getOverview.mockResolvedValueOnce(overview))
  getOverview.mockResolvedValue(overviews[overviews.length - 1])
  return {
    getOverview,
    startElection: vi.fn().mockResolvedValue(startBully),
    crashNode: vi.fn().mockResolvedValue({}),
    recoverNode: vi.fn().mockResolvedValue({}),
    getEvents: vi.fn().mockResolvedValue(eventsCluster),
  }
}

const clusterContext = (cluster) => ({ info: null, cluster, modules: null, loading: false, error: null, refresh: () => {} })

function page(api, cluster) {
  return (
    <MemoryRouter>
      <ClusterContext.Provider value={clusterContext(cluster)}>
        <ElectionPage experiment={experiment} api={api} />
      </ClusterContext.Provider>
    </MemoryRouter>
  )
}

async function renderPage(api, cluster = clusterWithLeader) {
  const view = render(page(api, cluster))
  await screen.findByTestId('module-status')
  return view
}

const announcement = () => screen.getByTestId('section-controls').querySelector('[aria-live="polite"]').textContent

beforeEach(() => {
  vi.spyOn(useStompModule, 'useStomp').mockImplementation(() => ({
    status: 'connected',
    subscribe: () => () => {},
    onConnect: () => () => {},
  }))
  vi.spyOn(useModuleEventsModule, 'useModuleEvents').mockImplementation(() => ({ events, loading: false, error: null }))
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('ElectionPage', () => {
  it('is registered for the election module and renders the shared layout, sections in order', async () => {
    expect(modulePages.election).toBe(ElectionPage)
    await renderPage(makeApi())
    const headings = screen.getAllByRole('heading').filter((h) => ['H1', 'H2'].includes(h.tagName)).map((h) => h.textContent)
    expect(headings).toEqual(['Bully and Ring Election', 'How it works', 'Controls', 'Live visualisation',
      'Measurements', 'Event log', 'What to notice'])
    expect(screen.getByText(experiment.concept)).toBeTruthy()
  })

  it('shows the detector idle on a fresh backend and no leader from the cluster roles', async () => {
    await renderPage(makeApi({ overviews: [overviewInitial] }), clusterLeaderCrashed)
    expect(screen.getByTestId('detector-state').textContent).toBe('Detector idle, starts on the first election.')
    expect(screen.getByTestId('round-panel').textContent).toContain('Leader: none')
  })

  it('takes the leader from the cluster roles, not from the overview', async () => {
    await renderPage(makeApi({ overviews: [overviewAfterRecovery] }), clusterLeaderCrashed)
    expect(screen.getByTestId('round-panel').textContent).toContain('Leader: none')
    expect(within(screen.getByTestId('election-ring')).queryByText('Leader')).toBeNull()
  })

  it('moves focus to the result heading after a start and announces it', async () => {
    const api = makeApi({ overviews: [overviewInitial] })
    await renderPage(api, clusterLeaderCrashed)
    fireEvent.click(screen.getByRole('button', { name: 'Start election' }))
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Election result' })))
    expect(api.startElection).toHaveBeenCalledWith({ algorithm: 'BULLY', nodeId: 1 })
    await waitFor(() => expect(announcement()).toContain('Bully election started from node 1.'))
  })

  it('announces the end of a round and a leader change', async () => {
    const api = makeApi({ overviews: [overviewInitial, overviewAfterBully] })
    const view = await renderPage(api, clusterLeaderCrashed)
    view.rerender(page(api, clusterWithLeader))   // the cluster roles now name node 5
    await waitFor(() => expect(announcement()).toBe('Node 5 is now the leader.'), { timeout: 4000 })
    fireEvent.click(screen.getByRole('button', { name: 'Crash node 2' }))   // any action refreshes the overview
    await waitFor(() => expect(announcement()).toMatch(/Election finished: node 5 was elected in [\d.]+ ms\./), { timeout: 4000 })
  })

  it('names the node on every per-node metric card and shows "—" for a mean that was not measured', async () => {
    await renderPage(makeApi())
    const rows = within(screen.getByTestId('per-node-metrics')).getAllByRole('listitem')
    expect(rows).toHaveLength(5)
    rows.forEach((row, index) => {
      within(row).getAllByTestId('metric-card').forEach((card) => expect(card.textContent).toMatch(new RegExp(`^Node ${index + 1}: `)))
    })
    const nodeFour = overviewAfterRecovery.nodes[3]
    expect(rows[3].textContent).toContain(`Node 4: elections won${nodeFour.electionsWon}`)
    expect(within(rows[3]).getByLabelText('Not available')).toBeTruthy()
    expect(screen.getByText('Per node, since the backend started')).toBeTruthy()
  })

  it('lists the leader changes of the cluster log with their real Lamport values', async () => {
    await renderPage(makeApi())
    const list = await screen.findByTestId('leader-changes')
    expect(within(list).getByRole('heading').textContent).toBe('Leader changes (cluster log)')
    const changes = eventsCluster.filter((e) => e.type === 'LEADER_CHANGED')
    await waitFor(() => expect(within(list).getAllByRole('listitem')).toHaveLength(changes.length))
    for (const change of changes) {
      expect(within(list).getByText(`L:${change.lamportTime}`)).toBeTruthy()
    }
    expect(within(list).getAllByText('Cluster').length).toBe(changes.length)
  })

  it('has no all-capitals word and no uppercase class anywhere on the page', async () => {
    await renderPage(makeApi())
    await screen.findAllByText(/became the leader/)
    expect(document.body.textContent).not.toMatch(/\b[A-Z]{2,}\b/)
    expect(document.body.querySelector('.uppercase')).toBeNull()
  })

  it('shows an error with a retry when the overview cannot be loaded', async () => {
    const api = makeApi()
    api.getOverview.mockReset()
    api.getOverview.mockRejectedValueOnce(new ApiError({ status: 503, title: 'Down', detail: 'The backend did not answer' }))
    api.getOverview.mockResolvedValue(overviewAfterRecovery)
    render(page(api, clusterWithLeader))
    const retry = await screen.findByRole('button', { name: 'Try again' })
    expect(screen.getByText('Could not load the election module.')).toBeTruthy()
    fireEvent.click(retry)
    await screen.findByTestId('module-status')
    expect(api.getOverview).toHaveBeenCalledTimes(2)
  })
})
