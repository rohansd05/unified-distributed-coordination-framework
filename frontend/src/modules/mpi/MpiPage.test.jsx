import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import * as useStompModule from '@/hooks/useStomp'
import * as useModuleEventsModule from '@/hooks/useModuleEvents'
import * as useToastModule from '@/hooks/use-toast'
import { ApiError } from '@/services/api'
import { findBySlug } from '@/lib/experiments'
import { modulePages } from '@/modules/registry'
import overviewAfter from '@/test/fixtures/mpi/overview-after-start.json'
import overviewCrashed from '@/test/fixtures/mpi/overview-node-crashed.json'
import overviewLeader from '@/test/fixtures/mpi/overview-leader.json'
import startFixture from '@/test/fixtures/mpi/start.json'
import startWithFailure from '@/test/fixtures/mpi/start-with-failure.json'
import history from '@/test/fixtures/mpi/collectives-history.json'
import broadcast from '@/test/fixtures/mpi/collective-broadcast.json'
import partial from '@/test/fixtures/mpi/collective-partial.json'
import refused from '@/test/fixtures/mpi/collective-refused.json'
import missingFields from '@/test/fixtures/mpi/error-400-missing-fields.json'
import countError from '@/test/fixtures/mpi/error-400-count.json'
import seedError from '@/test/fixtures/mpi/error-400-seed.json'
import unreadable from '@/test/fixtures/mpi/error-400-unreadable.json'
import unknownNode from '@/test/fixtures/mpi/error-404-unknown-node.json'
import busy from '@/test/fixtures/mpi/error-409-busy.json'
import rootDown from '@/test/fixtures/mpi/error-409-root-down.json'
import noLiveNode from '@/test/fixtures/mpi/error-409-no-live-node.json'
import noLeader from '@/test/fixtures/mpi/synthetic/error-409-no-leader.json'
import events from '@/test/fixtures/mpi/events.json'
import { MpiPage } from './MpiPage'

const experiment = findBySlug('9-mpi')
const copy = (value) => JSON.parse(JSON.stringify(value))

/** An ApiError exactly as services/api.js builds it from a real ProblemDetail body (original response kept). */
function problem(body) {
  return new ApiError({ status: body.status, title: body.title, detail: body.detail, nodeId: body.nodeId ?? null,
    moduleId: body.moduleId ?? null, errors: body.errors ?? null,
    originalError: { response: { status: body.status, data: body } } })
}

/** The module's whole api, mocked: no test reaches the network. */
function makeApi({ overview = overviewAfter, start = startFixture, list = history } = {}) {
  return {
    getOverview: vi.fn().mockResolvedValue(overview),
    start: vi.fn().mockResolvedValue(start),
    getHistory: vi.fn().mockResolvedValue(list),
    runCollective: vi.fn().mockResolvedValue(broadcast),
    crashNode: vi.fn().mockResolvedValue({}),
    recoverNode: vi.fn().mockResolvedValue({}),
    getEvents: vi.fn().mockResolvedValue([]),
  }
}

let toast
let moduleEvents

beforeEach(() => {
  toast = vi.fn()
  moduleEvents = []
  vi.spyOn(useStompModule, 'useStomp').mockImplementation(() => ({
    status: 'connected',
    subscribe: () => () => {},
    onConnect: () => () => {},
  }))
  vi.spyOn(useModuleEventsModule, 'useModuleEvents').mockImplementation(() => ({ events: moduleEvents, loading: false, error: null }))
  vi.spyOn(useToastModule, 'useToast').mockReturnValue({ toast, toasts: [], dismiss: vi.fn() })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

async function renderPage(api) {
  render(<MemoryRouter><MpiPage experiment={experiment} api={api} /></MemoryRouter>)
  await screen.findByTestId('module-status')
  await waitFor(() => expect(api.getHistory).toHaveBeenCalled())
}

const button = (name) => screen.getByRole('button', { name })
const change = (label, value) => fireEvent.change(screen.getByLabelText(label), { target: { value } })
const run = () => fireEvent.click(button('Run collective'))
const actionError = () => screen.findByTestId('action-error')
const ALL_CAPS_WORD = /\b[A-Z]{2,}\b/g
const ALLOWED_ABBREVIATIONS = new Set(['RMI', 'SHA', 'MPI', 'UTC'])

describe('MpiPage: layout and states', () => {
  it('is registered for the mpi module and uses the shared layout with the concept and the algorithm note', async () => {
    expect(modulePages.mpi).toBe(MpiPage)
    await renderPage(makeApi())
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('MPI Collectives')
    expect(screen.getByText(experiment.concept)).toBeTruthy()
    expect(screen.getByTestId('section-how-it-works').textContent).toContain(overviewAfter.algorithmNote)
  })

  it('starts on mount, once, and reports how many nodes export the endpoint', async () => {
    const api = makeApi()
    await renderPage(api)
    expect(api.start).toHaveBeenCalledTimes(1)
    expect(screen.getByTestId('module-status').textContent).toContain('CollectiveEndpoint exported on 5 of 5 nodes')
  })

  it('shows a loading skeleton until the first answer', () => {
    const api = makeApi()
    const never = new Promise(() => {})
    api.getOverview.mockReturnValue(never)
    api.start.mockReturnValue(never)
    render(<MemoryRouter><MpiPage experiment={experiment} api={api} /></MemoryRouter>)
    expect(screen.getByTestId('loading')).toBeTruthy()
  })

  it('shows an error with retry when the module cannot be loaded', async () => {
    const api = makeApi()
    const offline = new ApiError({ status: 0, title: 'Backend unreachable', detail: 'Network Error', originalError: { code: 'ERR_NETWORK' } })
    api.getOverview.mockRejectedValue(offline)
    api.start.mockRejectedValue(offline)
    render(<MemoryRouter><MpiPage experiment={experiment} api={api} /></MemoryRouter>)
    const alert = await screen.findByTestId('load-error')
    expect(alert.textContent).toContain('The backend is not reachable')
    const reads = api.getOverview.mock.calls.length
    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(api.getOverview.mock.calls.length).toBe(reads + 1))
  })

  it('shows a failed start with "Try again", which starts once more', async () => {
    const api = makeApi()
    api.start.mockRejectedValueOnce(problem(busy))
    await renderPage(api)
    const alert = await screen.findByTestId('start-error')
    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(api.start).toHaveBeenCalledTimes(2))
  })

  it('guides the first step when nothing has run, and shows n/a, never 0, for every figure', async () => {
    await renderPage(makeApi({ overview: startWithFailure, start: startWithFailure, list: [] }))
    expect(screen.getByTestId('history-empty').textContent).toContain('start with a broadcast')
    const cards = within(screen.getByTestId('section-measurements')).getAllByTestId('metric-card')
    expect(cards).toHaveLength(3)
    for (const card of cards) {
      expect(within(card).getByTestId('not-available')).toBeTruthy()
      expect(card.querySelector('p').textContent).not.toMatch(/\b0\b/)
    }
    expect(screen.getByTestId('section-what-to-notice').textContent).not.toContain('Measured here')
    expect(screen.getByTestId('start-failure-5')).toBeTruthy()
  })

  it('shows the latest collective from the overview in the map, the metrics and the chart', async () => {
    await renderPage(makeApi())
    expect(screen.getByTestId('map-sentence').textContent).toBe('Broadcast 1 from root node 1: partial.')
    const cards = within(screen.getByTestId('section-measurements')).getAllByTestId('metric-card')
    expect(cards[0].textContent).toContain('44.541 ms')
    expect(cards[1].textContent).toContain('3 of 4')
    expect(cards[1].textContent).toContain('1 failed, 0 not attempted')
    expect(within(cards[2]).getByTestId('not-available')).toBeTruthy()   // passed is null: unknown, never "passed"
    expect(screen.getAllByTestId('latency-failed')).toHaveLength(1)
  })

  it('shows exactly one Simulated badge, for the co-hosting of the nodes', async () => {
    await renderPage(makeApi())
    expect(screen.getAllByTestId('simulated-badge')).toHaveLength(1)
    expect(within(screen.getByTestId('collective-map')).getByTestId('simulated-badge')).toBeTruthy()
  })

  it('shows the module\'s events through the shared event log, a refusal ending in REFUSED', async () => {
    moduleEvents = events
    await renderPage(makeApi())
    const log = screen.getByTestId('module-event-log')
    expect(log.textContent).toContain('Node 1 refused scatter 7: A scatter needs at least one element per live participant')
    expect(log.textContent).toContain('Node 1 started scatter 7 of 3 values')
  })

  it('writes no all-caps word except known abbreviations', async () => {
    await renderPage(makeApi())
    const words = (document.body.textContent.match(ALL_CAPS_WORD) ?? []).filter((word) => !ALLOWED_ABBREVIATIONS.has(word))
    expect(words).toEqual([])
  })
})

describe('MpiPage: controls', () => {
  it('shows the root default in plain words (no leader yet), the leader rule and its side effect, and each operation\'s description', async () => {
    await renderPage(makeApi())
    expect(within(screen.getByLabelText('Root node')).getByRole('option',
      { name: 'Default: the leader elected by Experiment 4 (none yet; Experiment 4 elects one first)' })).toBeTruthy()
    const hint = screen.getByTestId('mpi-root-hint').textContent
    expect(hint).toContain('the leader elected by Experiment 4')
    expect(hint).toContain('starts its election service and heartbeats on every live node')
    expect(hint).not.toMatch(/Phase 13|lowest live/)
    expect(screen.getByTestId('mpi-operation-hint').textContent).toBe(overviewAfter.operations[0].description)
    change('Operation', 'SCATTER')
    expect(screen.getByTestId('mpi-operation-hint').textContent).toBe(overviewAfter.operations[1].description)
    expect(screen.getByTestId('mpi-count-hint').textContent).toContain('Scatter needs at least 5 (one per live node)')
  })

  it('names the elected leader as the default root (real overview with a leader)', async () => {
    await renderPage(makeApi({ overview: overviewLeader, start: overviewLeader }))
    expect(within(screen.getByLabelText('Root node')).getByRole('option',
      { name: 'Default: node 5 (the leader elected by Experiment 4)' })).toBeTruthy()
  })

  it('synthetic: shows a minimum of n/a when every node is down (no capture of that overview)', async () => {
    const overview = copy(overviewAfter)
    overview.operations[1].minimumCount = null   // changed: was 5 (null when every node is crashed)
    overview.defaultRootNodeId = null   // as captured: no leader
    overview.participants.forEach((node) => { node.nodeStatus = 'CRASHED' })   // changed: was UP (every node down)
    await renderPage(makeApi({ overview, start: overview }))
    change('Operation', 'SCATTER')
    expect(within(screen.getByTestId('mpi-count-hint')).getByTestId('not-available')).toBeTruthy()
    expect(within(screen.getByLabelText('Root node')).getByRole('option', { name: 'Default: none, every node is down' })).toBeTruthy()
  })

  it('explains the timeout with the overview\'s figures and the named margin only (real, then synthetic limits)', async () => {
    await renderPage(makeApi())
    expect(screen.getByTestId('timeout-help').textContent.replace(/\s+/g, ' ')).toBe(
      'A collective can take up to its deadline (10000 ms) plus one worst-case transfer (12000 ms = 3 x (connect 1000 ms + call 3000 ms): '
      + 'a lookup, RMI\'s distributed garbage collection call and the method call): 22000 ms. These are bounds from the configured '
      + 'timeouts, not measurements. This page waits 25000 ms (that plus a 3000 ms margin).')
    cleanup()
    const overview = copy(overviewAfter)
    overview.limits = { ...overview.limits, collectiveDeadlineMillis: 5000, worstCaseTransferMillis: 6000,   // changed: all five figures
      connectTimeoutMillis: 500, callTimeoutMillis: 1500, recommendedMinClientTimeoutMillis: 11000 }
    await renderPage(makeApi({ overview, start: overview }))
    const text = screen.getByTestId('timeout-help').textContent
    for (const figure of ['(5000 ms)', '(6000 ms', 'connect 500 ms', 'call 1500 ms', '11000 ms.', 'waits 14000 ms', '3000 ms margin']) {
      expect(text).toContain(figure)
    }
  })

  it('runs a broadcast with the current overview (for its timeout), shows and focuses the result, and toasts', async () => {
    const api = makeApi()
    await renderPage(api)
    run()
    const result = await screen.findByTestId('action-result')
    expect(api.runCollective).toHaveBeenCalledWith({ operation: 'BROADCAST', count: 1000 }, startFixture)
    await waitFor(() => expect(document.activeElement).toBe(result))
    expect(within(result).getByTestId('outcome-badge').textContent).toBe('Complete')
    expect(toast).toHaveBeenCalledWith({ title: 'Broadcast 2: complete, 4 of 4 remote transfers delivered, total 10.036 ms.' })
  })

  it('sends seed, root and a count below the minimum as typed, and shows the backend\'s refusal', async () => {
    const api = makeApi()
    api.runCollective.mockResolvedValue(refused)
    await renderPage(api)
    change('Operation', 'SCATTER')
    change('Element count', '3')
    change('Seed (optional)', '42')
    change('Root node', '1')
    run()
    await screen.findByTestId('refusal')
    expect(api.runCollective).toHaveBeenCalledWith({ operation: 'SCATTER', count: 3, seed: 42, rootNode: 1 }, startFixture)
    expect(toast).toHaveBeenCalledWith({ title: 'Scatter 7 refused', description: refused.refusal.message })
  })

  it('toasts a partial collective as destructive', async () => {
    const api = makeApi()
    api.runCollective.mockResolvedValue(partial)
    await renderPage(api)
    run()
    await screen.findByTestId('action-result')
    expect(toast).toHaveBeenCalledWith({ variant: 'destructive',
      title: 'Broadcast 1: partial, 3 of 4 remote transfers delivered, total 44.541 ms.' })
  })

  it('checks count and seed before sending, in the backend\'s words', async () => {
    const api = makeApi()
    await renderPage(api)
    change('Element count', '100001')
    change('Seed (optional)', '-1')
    run()
    expect(screen.getByTestId('mpi-count-error').textContent).toBe('count must be 1-100000, was 100001')
    expect(screen.getByTestId('mpi-seed-error').textContent).toBe('seed must be 0-9007199254740991, was -1')
    expect(api.runCollective).not.toHaveBeenCalled()
  })

  it('disables Run and the node buttons and shows progress while its own collective runs', async () => {
    const api = makeApi()
    let finish
    api.runCollective.mockReturnValue(new Promise((resolve) => { finish = resolve }))
    await renderPage(api)
    run()
    expect(screen.getByTestId('pending').textContent).toContain('Running broadcast of 1000 values...')
    for (const name of ['Run collective', 'Crash node 1']) expect(button(name).disabled).toBe(true)
    expect(screen.queryByTestId('busy-banner')).toBeNull()
    finish(broadcast)
    await screen.findByTestId('action-result')
    expect(button('Run collective').disabled).toBe(false)
  })

  it('synthetic: shows a BUSY overview from another tab as a banner but keeps Run enabled (no BUSY overview captured)', async () => {
    const overview = { ...copy(overviewAfter), status: 'BUSY', actionInProgress: busy.actionInProgress }   // changed: status, actionInProgress
    await renderPage(makeApi({ overview, start: overview }))
    expect(screen.getByTestId('busy-banner').textContent).toContain('BROADCAST of 100000 values')
    expect(button('Run collective').disabled).toBe(false)
  })

  it('crashes and recovers a node through the shared cluster calls', async () => {
    const api = makeApi({ overview: overviewCrashed, start: overviewCrashed })
    await renderPage(api)
    fireEvent.click(button('Crash node 2'))
    await waitFor(() => expect(api.crashNode).toHaveBeenCalledWith(2))
    await waitFor(() => expect(toast).toHaveBeenCalledWith({ title: 'Node 2 crashed for every experiment.' }))
    fireEvent.click(button('Recover node 3'))
    await waitFor(() => expect(api.recoverNode).toHaveBeenCalledWith(3))
  })
})

describe('MpiPage: errors', () => {
  async function failWith(error) {
    const api = makeApi()
    api.runCollective.mockRejectedValue(error)
    await renderPage(api)
    run()
    const alert = await actionError()
    return { api, alert }
  }

  it('shows the real 409 busy with what is running, and retries nothing', async () => {
    const { api, alert } = await failWith(problem(busy))
    expect(alert.getAttribute('data-kind')).toBe('busy')
    expect(alert.textContent).toContain(busy.detail)
    expect(alert.textContent).toContain('Running now: BROADCAST of 100000 values')
    expect(api.runCollective).toHaveBeenCalledTimes(1)
  })

  it('shows 409 "Node down" for a crashed root and "Node state conflict" when every node is down', async () => {
    const first = await failWith(problem(rootDown))
    expect(first.alert.textContent).toBe('Node down: Node 3 is crashed.')
    cleanup()
    const second = await failWith(problem(noLiveNode))
    expect(second.alert.textContent).toBe('Node state conflict: Every node is crashed, so no node can be the root of a collective.')
  })

  it('shows 409 No leader as a calm status, not an alert (synthetic body, from MpiControllerTest)', async () => {
    const { alert } = await failWith(problem(noLeader))
    expect(alert.getAttribute('data-kind')).toBe('no-leader')
    expect(alert.getAttribute('role')).toBe('status')
    expect(alert.textContent).toBe(`No leader${noLeader.detail}`)
  })

  it('shows the backend\'s 400 errors beside their fields', async () => {
    const api = makeApi()
    api.runCollective.mockRejectedValueOnce(problem(countError)).mockRejectedValueOnce(problem(seedError))
      .mockRejectedValueOnce(problem(missingFields))
    await renderPage(api)
    run()
    await actionError()
    expect(screen.getByTestId('mpi-count-error').textContent).toBe('count must be 1-100000, was 100001')
    run()
    await waitFor(() => expect(screen.getByTestId('mpi-seed-error').textContent).toBe('seed must be 0-9007199254740991, was -1'))
    run()
    await waitFor(() => expect(screen.getByTestId('mpi-operation-error').textContent).toBe('operation is required'))
    expect(screen.getByTestId('mpi-count-error').textContent).toBe('count is required')
  })

  it('shows a 400 without errors, a 404, a client timeout and a network error each with its own message', async () => {
    const bad = await failWith(problem(unreadable))
    expect(bad.alert.textContent).toBe('The backend could not read the request: Failed to read request.')
    cleanup()
    const notFound = await failWith(problem(unknownNode))
    expect(notFound.alert.textContent).toBe('Unknown node: No node with id 9.')
    cleanup()
    const timeout = await failWith(new ApiError({ status: 0, title: 'Backend unreachable', detail: 'timeout of 25000ms exceeded',
      originalError: { code: 'ECONNABORTED', config: { timeout: 25000 } } }))
    expect(timeout.alert.textContent).toContain('within 25 s')
    cleanup()
    const network = await failWith(new ApiError({ status: 0, title: 'Backend unreachable', detail: 'Network Error',
      originalError: { code: 'ERR_NETWORK' } }))
    expect(network.alert.getAttribute('data-kind')).toBe('network')
  })
})

describe('MpiPage: history', () => {
  it('opens a history entry from the list itself, with no further request', async () => {
    const api = makeApi()
    await renderPage(api)
    fireEvent.click(screen.getByRole('button', { name: 'Show collective 4' }))
    const detail = screen.getByTestId('history-detail')
    expect(within(detail).getByText('Gather of 1003 values from node 1')).toBeTruthy()
    expect(within(detail).getAllByTestId('gathered-block')).toHaveLength(5)
  })

  it('says so when the selected entry is gone after the list is replaced (ids restart after a reset)', async () => {
    const api = makeApi()
    await renderPage(api)
    fireEvent.click(screen.getByRole('button', { name: 'Show collective 4' }))
    api.getHistory.mockResolvedValue([])
    fireEvent.click(button('Crash node 1'))   // any action refreshes the list
    await waitFor(() => expect(within(screen.getByTestId('history-detail')).getByRole('alert').textContent)
      .toContain('no longer in the history'))
  })

  it('appends measured figures to What to notice from the history', async () => {
    await renderPage(makeApi())
    const notice = screen.getByTestId('section-what-to-notice').textContent
    expect(notice).toContain('broadcast 6 took 4.429 ms in total for 3 remote transfers')
    expect(notice).toContain('Latest refusal:')
  })
})
