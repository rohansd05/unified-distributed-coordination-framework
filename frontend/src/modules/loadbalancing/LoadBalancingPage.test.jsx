import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import * as useStompModule from '@/hooks/useStomp'
import * as useToastModule from '@/hooks/use-toast'
import * as useModuleEventsModule from '@/hooks/useModuleEvents'
import { ApiError } from '@/services/api'
import { findBySlug } from '@/lib/experiments'
import overviewBefore from '@/test/fixtures/loadbalancing/overview-before.json'
import overviewAfterRun from '@/test/fixtures/loadbalancing/overview-after-run.json'
import overviewAfterComparison from '@/test/fixtures/loadbalancing/overview-after-comparison.json'
import overviewAfterCrashRun from '@/test/fixtures/loadbalancing/overview-after-crash-run.json'
import runAccepted from '@/test/fixtures/loadbalancing/run-accepted.json'
import comparisonAccepted from '@/test/fixtures/loadbalancing/comparison-accepted.json'
import busyFixture from '@/test/fixtures/loadbalancing/error-409-module-busy.json'
import nodeDownFixture from '@/test/fixtures/loadbalancing/error-409-node-down.json'
import validationFixture from '@/test/fixtures/loadbalancing/error-400-validation.json'
import { LoadBalancingPage } from './LoadBalancingPage'
import { formatMillis } from './labels'

const experiment = findBySlug('6-loadbalancing')
const copy = (value) => JSON.parse(JSON.stringify(value))

/** An ApiError exactly as services/api.js builds it from a real ProblemDetail body. */
function problem(body) {
  return new ApiError({ status: body.status, title: body.title, detail: body.detail, nodeId: body.nodeId ?? null,
    moduleId: body.moduleId ?? null, errors: body.errors ?? null })
}

function makeApi({ overview = overviewBefore } = {}) {
  return {
    getOverview: vi.fn().mockResolvedValue(overview),
    startRun: vi.fn().mockResolvedValue(runAccepted),
    startComparison: vi.fn().mockResolvedValue(comparisonAccepted),
    recoverNode: vi.fn().mockResolvedValue({}),
  }
}

let toast
let stompStatus

beforeEach(() => {
  toast = vi.fn()
  stompStatus = 'connected'
  vi.spyOn(useToastModule, 'useToast').mockReturnValue({ toast, toasts: [], dismiss: vi.fn() })
  vi.spyOn(useStompModule, 'useStomp').mockImplementation(() => ({
    status: stompStatus,
    subscribe: () => () => {},
    onConnect: () => () => {},
  }))
  vi.spyOn(useModuleEventsModule, 'useModuleEvents').mockReturnValue({ events: [], loading: false, error: null })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

async function renderPage(api) {
  render(<LoadBalancingPage experiment={experiment} api={api} />)
  await waitFor(() => expect(api.getOverview).toHaveBeenCalled())
  await screen.findByTestId('balancer-flow')
  await screen.findByLabelText('Requests')
}

const pageText = () => document.body.textContent
const ALL_CAPS_WORD = /\b[A-Z]{2,}\b/g
const ALLOWED_ABBREVIATIONS = new Set(['TCP', 'UDP', 'RTT', 'ID', 'IDS'])

describe('LoadBalancingPage', () => {
  it('uses the shared layout with the concept, the explanation and three callouts', async () => {
    await renderPage(makeApi())

    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Load Balancing')
    expect(screen.getByText(experiment.concept)).toBeTruthy()
    expect(screen.getByTestId('section-how-it-works').textContent).toContain('circuit breaker')
    expect(screen.getByTestId('section-what-to-notice').querySelectorAll('li')).toHaveLength(3)
    expect(screen.getByTestId('section-what-to-notice').textContent)
      .toContain('The comparison table says whether that made round robin finish last in your run.')
    expect(screen.queryByTestId('simulated-badge')).toBeNull()
    expect(pageText()).toContain('Nothing on this page is simulated: every figure is measured from real requests.')
  })

  it('before the first run: every measurement is "—", guidance instead of results, never NaN or undefined', async () => {
    await renderPage(makeApi())

    const cards = within(screen.getByTestId('section-measurements')).getAllByTestId('metric-card')
    expect(cards).toHaveLength(7)
    for (const card of cards) {
      expect(within(card).getByLabelText('Not available').textContent).toBe('—')
    }
    expect(screen.getByTestId('section-measurements').textContent).toContain('Nothing has run yet, so nothing is shown.')
    expect(screen.getByTestId('comparison-table').textContent).toMatch(/^No comparison yet/)
    expect(pageText()).not.toMatch(/NaN|undefined|Infinity/)
  })

  it('shows no all-caps words anywhere, whatever it renders (known abbreviations aside)', async () => {
    for (const overview of [overviewBefore, overviewAfterRun, overviewAfterComparison, overviewAfterCrashRun]) {
      await renderPage(makeApi({ overview }))
      const words = (pageText().match(ALL_CAPS_WORD) ?? []).filter((word) => !ALLOWED_ABBREVIATIONS.has(word))
      expect(words).toEqual([])
      cleanup()
    }
  })

  it('runs the chosen strategy with the backend defaults', async () => {
    const api = makeApi()
    await renderPage(api)

    fireEvent.click(screen.getByLabelText(/^Least connections/))
    fireEvent.click(screen.getByRole('button', { name: 'Run' }))

    await waitFor(() => expect(api.startRun).toHaveBeenCalledWith({
      strategy: 'LEAST_CONNECTIONS', requestCount: 60, workUnits: 400, concurrency: 12,
    }))
    expect(toast).toHaveBeenCalledWith({ title: 'Round robin run started' })
  })

  it('sends a crash plan: node 1 after a third of the requests by default', async () => {
    const api = makeApi()
    await renderPage(api)

    fireEvent.click(screen.getByLabelText('Crash a worker during this run'))
    expect(screen.getByLabelText('Node to crash').value).toBe('1')
    expect(screen.getByLabelText('After this many requests are served').value).toBe('20')
    fireEvent.change(screen.getByLabelText('Node to crash'), { target: { value: '3' } })
    fireEvent.click(screen.getByRole('button', { name: 'Run' }))

    await waitFor(() => expect(api.startRun).toHaveBeenCalledWith({
      strategy: 'ROUND_ROBIN', requestCount: 60, workUnits: 400, concurrency: 12, crash: { nodeId: 3, afterServed: 20 },
    }))
  })

  it('keeps the default crash point inside 1..requests-1, and disables the crash for a 1-request run, saying why', async () => {
    await renderPage(makeApi())
    fireEvent.click(screen.getByLabelText('Crash a worker during this run'))

    fireEvent.change(screen.getByLabelText('Requests'), { target: { value: '2' } })
    expect(screen.getByLabelText('After this many requests are served').value).toBe('1')

    fireEvent.change(screen.getByLabelText('Requests'), { target: { value: '1' } })
    expect(screen.getByLabelText('Crash a worker during this run').disabled).toBe(true)
    expect(pageText()).toContain('A crash needs a run of at least 2 requests, so that some requests are served after it.')
  })

  it('compares all four with the same settings', async () => {
    const api = makeApi()
    await renderPage(api)

    fireEvent.click(screen.getByRole('button', { name: 'Compare all four' }))

    await waitFor(() => expect(api.startComparison).toHaveBeenCalledWith({ requestCount: 60, workUnits: 400, concurrency: 12 }))
    expect(toast).toHaveBeenCalledWith({ title: 'Comparison of all four started' })
  })

  it('a 400 puts the backend message under its field', async () => {
    const api = makeApi()
    api.startRun.mockRejectedValue(problem(validationFixture))
    await renderPage(api)

    fireEvent.change(screen.getByLabelText('Requests'), { target: { value: '0' } })
    fireEvent.click(screen.getByRole('button', { name: 'Run' }))

    await screen.findByText(validationFixture.errors.requestCount)
    expect(screen.getByLabelText('Requests').getAttribute('aria-invalid')).toBe('true')
  })

  it.each([
    ['module busy', busyFixture],
    ['node down', nodeDownFixture],
  ])('a 409 (%s) shows its detail and starts nothing', async (_, fixture) => {
    const api = makeApi()
    api.startRun.mockRejectedValue(problem(fixture))
    await renderPage(api)

    fireEvent.click(screen.getByRole('button', { name: 'Run' }))

    expect((await screen.findAllByRole('alert')).map((a) => a.textContent)).toContain(fixture.detail)
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ variant: 'destructive', title: fixture.title }))
  })

  it('after a crash run: says the module crashed the node, and offers to recover it for every experiment', async () => {
    const api = makeApi({ overview: overviewAfterCrashRun })
    await renderPage(api)

    expect(screen.getByTestId('crash-outcome').textContent)
      .toBe('Node 3 was crashed by this run after 20 requests were served. It stays down, for every experiment, until you recover it.')
    expect(pageText()).toContain('Node 3 is down for every experiment.')
    expect(pageText()).toContain('Recovering a node brings it back for every experiment, not only this one.')
    fireEvent.click(screen.getByRole('button', { name: 'Recover node 3' }))

    await waitFor(() => expect(api.recoverNode).toHaveBeenCalledWith(3))
    expect(toast).toHaveBeenCalledWith({ title: 'Node 3 recovered for every experiment' })
    // While node 3 is down it is not offered as a crash target.
    fireEvent.click(screen.getByLabelText('Crash a worker during this run'))
    expect(within(screen.getByLabelText('Node to crash')).queryByText(/^Node 3/)).toBeNull()
  })

  it('while busy: both buttons are disabled and the status says what runs', async () => {
    const running = copy(overviewAfterRun)
    running.status = 'BUSY'
    running.actionInProgress = 'Load balancing run with round robin, 60 requests'
    running.latestRun.state = 'RUNNING'
    running.latestRun.report = null
    await renderPage(makeApi({ overview: running }))

    expect(screen.getByRole('button', { name: 'Run' }).disabled).toBe(true)
    expect(screen.getByRole('button', { name: 'Compare all four' }).disabled).toBe(true)
    expect(screen.getByTestId('module-status').textContent)
      .toBe('Module status: Busy (Load balancing run with round robin, 60 requests)')
  })

  it('after a comparison: the measurements show the chosen phase and the finding with its numbers', async () => {
    await renderPage(makeApi({ overview: overviewAfterComparison }))

    const rr = overviewAfterComparison.latestComparison.phases[0]
    expect(screen.getByTestId('section-measurements').textContent).toContain(`Finish time${formatMillis(rr.makespanMillis)}ms`)
    expect(screen.getByTestId('finding').textContent).toMatch(/Round robin finished last: \d+ ms/)

    fireEvent.click(within(screen.getByRole('group', { name: 'Show the split for' })).getByLabelText('Least connections'))
    const lc = overviewAfterComparison.latestComparison.phases.find((p) => p.strategy === 'LEAST_CONNECTIONS')
    expect(screen.getByTestId('section-measurements').textContent).toContain(`Finish time${formatMillis(lc.makespanMillis)}ms`)
  })

  it('a failed run: its error text, and no measurements', async () => {
    const failed = copy(overviewAfterRun)
    failed.latestRun.state = 'FAILED'
    failed.latestRun.report = null
    failed.latestRun.error = 'broke after ten'
    await renderPage(makeApi({ overview: failed }))

    expect(within(screen.getByTestId('balancer-flow')).getByRole('alert').textContent).toBe('Failed. broke after ten')
    expect(within(screen.getByTestId('section-measurements')).getAllByLabelText('Not available')).toHaveLength(7)
    expect(pageText()).not.toMatch(/NaN|undefined/)
  })

  it('a failed fetch: the unreachable banner and a retry, no throw', async () => {
    const api = makeApi()
    api.getOverview.mockRejectedValue(new ApiError({ status: 0, title: 'Backend unreachable', detail: 'Network Error' }))
    render(<LoadBalancingPage experiment={experiment} api={api} />)

    expect(await screen.findByText('Could not load the load balancing data.')).toBeTruthy()
    expect(pageText()).toContain('The backend is not reachable.')
    expect(screen.getByRole('button', { name: 'Run' }).disabled).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(api.getOverview).toHaveBeenCalledTimes(2))
  })

  it('an empty worker list: no lanes and no crash target, no throw', async () => {
    await renderPage(makeApi({ overview: { ...overviewBefore, workers: [] } }))

    expect(screen.getByTestId('balancer-flow').textContent).toContain('No workers are listed yet.')
    expect(screen.getByLabelText('Crash a worker during this run').disabled).toBe(true)
    expect(pageText()).toContain('No node is up to crash.')
  })
})
