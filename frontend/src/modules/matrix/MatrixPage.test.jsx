import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import * as useStompModule from '@/hooks/useStomp'
import * as useModuleEventsModule from '@/hooks/useModuleEvents'
import * as useToastModule from '@/hooks/use-toast'
import { ApiError } from '@/services/api'
import { EXPERIMENTS, findBySlug } from '@/lib/experiments'
import { modulePages } from '@/modules/registry'
import overviewBefore from '@/test/fixtures/matrix/overview-before-start.json'
import overviewAfter from '@/test/fixtures/matrix/overview-after-start.json'
import overviewCrashed from '@/test/fixtures/matrix/overview-node-crashed.json'
import startFixture from '@/test/fixtures/matrix/start.json'
import startWithFailure from '@/test/fixtures/matrix/start-with-failure.json'
import runsHistory from '@/test/fixtures/matrix/runs-history.json'
import series from '@/test/fixtures/matrix/series.json'
import seriesBefore from '@/test/fixtures/matrix/series-before-runs.json'
import complete50 from '@/test/fixtures/matrix/run-complete-50.json'
import complete316 from '@/test/fixtures/matrix/run-complete-316.json'
import partial from '@/test/fixtures/matrix/run-partial.json'
import refused from '@/test/fixtures/matrix/run-refused.json'
import aboveMax from '@/test/fixtures/matrix/error-400-size-above-max.json'
import unknownNode from '@/test/fixtures/matrix/error-404-unknown-node.json'
import busy from '@/test/fixtures/matrix/error-409-busy.json'
import rootDown from '@/test/fixtures/matrix/error-409-root-down.json'
import noLiveNode from '@/test/fixtures/matrix/error-409-no-live-node.json'
import noLeader from '@/test/fixtures/matrix/synthetic/error-409-no-leader.json'
import overviewLeader from '@/test/fixtures/matrix/overview-leader.json'
import { RUN_TIMEOUT_MARGIN_MS } from './matrixApi'
import { MatrixPage } from './MatrixPage'

const experiment = findBySlug('10-matrix')
const copy = (value) => JSON.parse(JSON.stringify(value))
const withLatest = (overview, latest) => ({ ...copy(overview), latest })

function problem(body) {
  return new ApiError({ status: body.status, title: body.title, detail: body.detail, nodeId: body.nodeId ?? null,
    moduleId: body.moduleId ?? null, errors: body.errors ?? null,
    originalError: { response: { status: body.status, data: body } } })
}

/** The module's whole api, mocked: no test reaches the network. */
function makeApi({ overview = withLatest(overviewAfter, complete316), start = startFixture, runs = runsHistory, chart = series } = {}) {
  return {
    getOverview: vi.fn().mockResolvedValue(overview),
    start: vi.fn().mockResolvedValue(start),
    getRuns: vi.fn().mockResolvedValue(runs),
    getSeries: vi.fn().mockResolvedValue(chart),
    runMatrix: vi.fn().mockResolvedValue(complete50),
    getEvents: vi.fn().mockResolvedValue([]),
  }
}

let toast

beforeEach(() => {
  toast = vi.fn()
  vi.spyOn(useStompModule, 'useStomp').mockImplementation(() => ({
    status: 'connected',
    subscribe: () => () => {},
    onConnect: () => () => {},
  }))
  vi.spyOn(useModuleEventsModule, 'useModuleEvents').mockImplementation(() => ({ events: [], loading: false, error: null }))
  vi.spyOn(useToastModule, 'useToast').mockReturnValue({ toast, toasts: [], dismiss: vi.fn() })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

async function renderPage(api) {
  render(<MemoryRouter><MatrixPage experiment={experiment} api={api} /></MemoryRouter>)
  await screen.findByTestId('module-status')
  await waitFor(() => expect(api.start).toHaveBeenCalled())
  await waitFor(() => expect(api.getSeries.mock.calls.length).toBeGreaterThanOrEqual(2))
}

const button = (name) => screen.getByRole('button', { name })
const sizeInput = () => screen.getByLabelText('Matrix size (n)')
const setSize = (value) => fireEvent.change(sizeInput(), { target: { value } })
const run = () => fireEvent.click(button('Run'))
const actionError = () => screen.findByTestId('action-error')
const ALL_CAPS_WORD = /\b[A-Z]{2,}\b/g
const ALLOWED_ABBREVIATIONS = new Set(['RMI', 'JVM', 'JIT', 'MPI'])

describe('MatrixPage: layout and start', () => {
  it('is registered for matrix and uses the shared layout with the concept and the served algorithm note', async () => {
    expect(modulePages.matrix).toBe(MatrixPage)
    await renderPage(makeApi())
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Parallel Matrix Multiplication')
    expect(screen.getByText(experiment.concept)).toBeTruthy()
    expect(screen.getByTestId('section-how-it-works').textContent).toContain(overviewAfter.algorithmNote)
  })

  it('reads, then starts once with the served overview, and reports the endpoint and the listeners', async () => {
    const api = makeApi({ overview: overviewBefore })
    api.start.mockImplementation(async () => {
      api.getOverview.mockResolvedValue(overviewAfter)   // after the start, reads show every node exported
      return startFixture
    })
    await renderPage(api)
    expect(api.start).toHaveBeenCalledTimes(1)
    expect(api.start).toHaveBeenCalledWith(overviewBefore)
    expect(screen.getByTestId('module-status').textContent).toContain('MatrixEndpoint exported on 5 of 5 nodes')
    expect(screen.getByTestId('module-status').textContent).toContain('request listeners running on 5 of 5')
  })

  it('shows the served Experiment 2 side effect beside the start, with a link to the Experiment 2 page', async () => {
    await renderPage(makeApi())
    const text = screen.getByTestId('experiment-two')
    expect(text.textContent).toContain(overviewAfter.experimentTwoSideEffect)
    const two = EXPERIMENTS.find((e) => e.id === 'multithreading')
    expect(within(text).getByRole('link').getAttribute('href')).toBe(`/experiments/${two.slug}`)
  })

  it('has exactly one Simulated badge, in the live visualisation, before and after a run', async () => {
    await renderPage(makeApi({ overview: overviewAfter }))
    expect(screen.getAllByTestId('simulated-badge')).toHaveLength(1)
    expect(within(screen.getByTestId('section-visualisation')).getAllByTestId('simulated-badge')).toHaveLength(1)
    cleanup()
    await renderPage(makeApi())
    expect(screen.getAllByTestId('simulated-badge')).toHaveLength(1)
    expect(within(screen.getByTestId('section-visualisation')).getByTestId('collective-map')).toBeTruthy()
  })

  it('shows a load error with "Try again" when the overview cannot be read', async () => {
    const api = makeApi()
    api.getOverview.mockRejectedValue(new ApiError({ status: 0 }))
    api.start.mockRejectedValue(new ApiError({ status: 0 }))
    render(<MemoryRouter><MatrixPage experiment={experiment} api={api} /></MemoryRouter>)
    expect(await screen.findByTestId('load-error')).toBeTruthy()
    expect(api.start).toHaveBeenCalledWith(null)
  })

  it('shows a failed start with "Try again", and a node\'s own start failure', async () => {
    const api = makeApi({ overview: overviewBefore })
    api.start.mockRejectedValueOnce(problem(busy))
    await renderPage(api)
    expect((await screen.findByTestId('start-error')).textContent).toContain('could not be started')
    api.getOverview.mockResolvedValue(overviewAfter)   // the retry works, so later reads show every node exported
    fireEvent.click(within(screen.getByTestId('start-error')).getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(api.start).toHaveBeenCalledTimes(2))
    cleanup()
    await renderPage(makeApi({ overview: startWithFailure, start: startWithFailure }))
    expect(screen.getByTestId('start-failure').textContent).toContain(startWithFailure.participants[4].startFailure)
  })

  it('uses only words in sentence case (no ALL-CAPS labels beyond known abbreviations)', async () => {
    await renderPage(makeApi({ overview: withLatest(overviewAfter, partial) }))
    const words = (document.body.textContent.match(ALL_CAPS_WORD) ?? []).filter((word) => !ALLOWED_ABBREVIATIONS.has(word))
    expect(words).toEqual([])
  })
})

describe('MatrixPage: running', () => {
  it('with no run yet, guides from the first served series size and shows every size as not run', async () => {
    const fresh = withLatest(overviewAfter, null)   // the capture's overview already has a latest run
    await renderPage(makeApi({ overview: fresh, start: fresh, runs: [], chart: seriesBefore }))
    const sizes = overviewAfter.limits.seriesSizes
    expect(screen.getByTestId('empty-state').textContent).toBe(
      `No run yet. Start with size ${sizes[0]}, or run all sizes (${sizes.join(', ')}) to fill the speedup chart.`)
    expect(sizeInput().value).toBe(String(sizes[0]))
    expect(screen.getAllByTestId('speedup-gap')).toHaveLength(sizes.length)
  })

  it('runs the default size, announces the outcome and shows the run', async () => {
    const api = makeApi()
    await renderPage(api)
    run()
    await waitFor(() => expect(api.runMatrix).toHaveBeenCalledWith({ size: overviewAfter.limits.seriesSizes[0] }, expect.any(Object)))
    await waitFor(() => expect(toast).toHaveBeenCalledWith({ title: `Run ${complete50.runId}, size ${complete50.size}: complete.` }))
  })

  it('checks the size in the browser against the served bounds and sends nothing when it is out of range', async () => {
    const api = makeApi()
    await renderPage(api)
    setSize('317')
    run()
    expect(screen.getByTestId('matrix-size-error').textContent).toBe('size must be 2-316, was 317')
    expect(api.runMatrix).not.toHaveBeenCalled()
  })

  it('sends a size below minimumSizeNow (the backend refuses and keeps it), with the hint saying so', async () => {
    const api = makeApi()
    api.runMatrix.mockResolvedValue(refused)
    await renderPage(api)
    expect(screen.getByTestId('matrix-size-hint').textContent).toContain(`A size below ${overviewAfter.minimumSizeNow}`)
    setSize('3')
    fireEvent.change(screen.getByLabelText('Root node (optional)'), { target: { value: '2' } })
    run()
    await waitFor(() => expect(api.runMatrix).toHaveBeenCalledWith({ size: 3, rootNode: 2 }, expect.any(Object)))
    await waitFor(() => expect(toast).toHaveBeenCalledWith({ title: `Run ${refused.runId}, size 3 was refused: ${refused.refusal.message}` }))
  })

  it('shows the backend\'s 400 field error under the size', async () => {
    const api = makeApi()
    api.runMatrix.mockRejectedValue(problem(aboveMax))
    await renderPage(api)
    run()
    expect((await screen.findByTestId('matrix-size-error')).textContent).toBe(aboveMax.errors.size)
  })

  it('answers 404 and the three 409s calmly, with the page intact', async () => {
    const cases = [
      [unknownNode, 'not-found', 'Unknown node'],
      [busy, 'busy', busy.actionInProgress],
      [rootDown, 'node-down', 'Node down'],
      [noLiveNode, 'state-conflict', 'No node is live, so nothing can be the root'],
      [noLeader, 'no-leader', noLeader.detail],   // synthetic body, from MatrixControllerTest
    ]
    for (const [body, kind, text] of cases) {
      const api = makeApi()
      api.runMatrix.mockRejectedValue(problem(body))
      await renderPage(api)
      run()
      const alert = await actionError()
      expect(alert.dataset.kind).toBe(kind)
      expect(alert.textContent).toContain(text)
      expect(screen.getByTestId('row-blocks')).toBeTruthy()
      expect(button('Run').disabled).toBe(false)
      cleanup()
    }
  })

  it('runs all served sizes one after another, shows progress and offers "Stop after this run"', async () => {
    const api = makeApi()
    const gates = []
    api.runMatrix.mockImplementation(() => new Promise((resolve) => gates.push(resolve)))
    await renderPage(api)
    const sizes = overviewAfter.limits.seriesSizes
    fireEvent.click(button(`Run all sizes (${sizes.join(', ')})`))
    await waitFor(() => expect(screen.getByTestId('pending').textContent).toContain(`Running size ${sizes[0]} (1 of ${sizes.length})`))
    expect(button('Run').disabled).toBe(true)
    fireEvent.click(button('Stop after this run'))
    expect(screen.getByTestId('pending').textContent).toContain('The series stops after this run.')
    gates[0](complete50)
    expect(await screen.findByTestId('run-all-note')).toBeTruthy()
    expect(screen.getByTestId('run-all-note').textContent).toContain(`Stopped after size ${sizes[0]}, as asked.`)
    expect(api.runMatrix).toHaveBeenCalledTimes(1)
  })
})

describe('MatrixPage: states', () => {
  it('shows a busy module (another tab, or before a reload) with Run still available', async () => {
    await renderPage(makeApi({ overview: { ...withLatest(overviewAfter, complete316), status: 'BUSY', actionInProgress: busy.actionInProgress } }))
    expect(screen.getByTestId('busy-banner').textContent).toContain(busy.actionInProgress)
    expect(button('Run').disabled).toBe(false)
  })

  it('after a client timeout locks the controls and says the run may still be going', async () => {
    const api = makeApi()
    api.runMatrix.mockImplementation(async () => {
      api.getOverview.mockResolvedValue({ ...withLatest(overviewAfter, complete316), status: 'BUSY', actionInProgress: busy.actionInProgress })
      throw new ApiError({ status: 0, originalError: { code: 'ECONNABORTED', config: { timeout: 29001 } } })
    })
    await renderPage(api)
    run()
    expect((await actionError()).dataset.kind).toBe('timeout')
    expect(await screen.findByTestId('awaiting-idle')).toBeTruthy()
    expect(screen.queryByTestId('busy-banner')).toBeNull()
    expect(button('Run').disabled).toBe(true)
  })

  it('says when every node is down and points to the Cluster page', async () => {
    const down = copy(overviewCrashed)
    down.participants.forEach((node) => { node.nodeStatus = 'CRASHED' })
    Object.assign(down, { defaultRootNodeId: null, minimumSizeNow: null, speedupCeiling: null, latest: null })
    await renderPage(makeApi({ overview: down, start: down }))
    expect(screen.getByTestId('all-down').textContent).toContain('Every node is down')
    expect(screen.getByRole('option', { name: 'Default: none, every node is down' })).toBeTruthy()
    expect(screen.getByTestId('matrix-size-hint').textContent).toContain('answered with a conflict')
  })

  it('shows the latest complete run\'s figures in the strip and per rank, and a partial run\'s withheld speedup', async () => {
    await renderPage(makeApi())
    const strip = screen.getByTestId('section-measurements')
    expect(strip.textContent).toContain('Communication share69.1 %')
    expect(screen.getAllByTestId('row-band')).toHaveLength(complete316.computes.length)
    cleanup()
    await renderPage(makeApi({ overview: withLatest(overviewAfter, partial) }))
    expect(screen.getByTestId('speedup-withheld').textContent).toBe(partial.timing.derived.speedupWithheldReason)
  })

  it('shows a history run in full when its row is chosen', async () => {
    await renderPage(makeApi())
    fireEvent.click(screen.getByRole('button', { name: /show run 3$/ }))
    const detail = within(screen.getByTestId('history-detail')).getByTestId('run-result')
    const chosen = runsHistory.find((r) => r.runId === 3)
    expect(detail.textContent).toContain(`Run ${chosen.runId}, size ${chosen.size}`)
  })

  it('has two general callouts and a third "In this run" one from the newest complete run', async () => {
    await renderPage(makeApi())
    const items = within(screen.getByTestId('section-what-to-notice')).getAllByRole('listitem')
    expect(items).toHaveLength(3)
    expect(items[2].textContent).toMatch(/^In this run \(run 8, size 100\)/)
  })

  it('the default root names the elected leader, as served (real overview with a leader)', async () => {
    await renderPage(makeApi({ overview: overviewLeader, start: overviewLeader }))
    expect(screen.getByRole('option', { name: 'Default: node 5 (the leader elected by Experiment 4)' })).toBeTruthy()
    const hint = screen.getByTestId('matrix-root-hint').textContent
    expect(hint).toContain('starts its election service and heartbeats on every live node')
    expect(hint).not.toMatch(/Phase 13|lowest live/)
  })

  it('before any election the default root says Experiment 4 elects one first', async () => {
    await renderPage(makeApi())
    expect(screen.getByRole('option',
      { name: 'Default: the leader elected by Experiment 4 (none yet; Experiment 4 elects one first)' })).toBeTruthy()
  })
})

describe('MatrixPage: nothing from configuration is hard-coded', () => {
  it('takes the bounds, the series sizes, the default size and the wait from altered served limits', async () => {
    const altered = withLatest(overviewAfter, null)   // limits changed here, in the test, on purpose
    altered.limits = { ...altered.limits, minSize: 3, maxSize: 40, seriesSizes: [8, 16], recommendedMinClientTimeoutMillis: 7000 }
    altered.minimumSizeNow = 5
    const alteredSeries = { ...copy(seriesBefore), seriesSizes: [8, 16],
      gaps: [8, 16].map((size) => ({ size, newestRunId: null, newestOutcome: null })) }
    const api = makeApi({ overview: altered, start: altered, runs: [], chart: alteredSeries })
    await renderPage(api)
    expect(sizeInput().value).toBe('8')
    expect(sizeInput().getAttribute('max')).toBe('40')
    expect(screen.getByTestId('matrix-size-hint').textContent).toContain('from 3 to 40')
    expect(screen.getByTestId('empty-state').textContent).toContain('Start with size 8, or run all sizes (8, 16)')
    expect(screen.getByTestId('timeout-help').textContent).toContain(`waits ${7000 + RUN_TIMEOUT_MARGIN_MS} ms`)
    expect(screen.getAllByTestId('speedup-gap')).toHaveLength(2)
    setSize('41')
    run()
    expect(screen.getByTestId('matrix-size-error').textContent).toBe('size must be 3-40, was 41')
    fireEvent.click(button('Run all sizes (8, 16)'))
    await waitFor(() => expect(api.runMatrix).toHaveBeenCalledTimes(2))
    expect(api.runMatrix.mock.calls.map(([body]) => body.size)).toEqual([8, 16])
    const text = document.body.textContent
    for (const served of ['316', '26001', '29001']) expect(text).not.toContain(served)
  })
})
