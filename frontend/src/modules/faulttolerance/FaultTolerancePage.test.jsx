import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import * as useStompModule from '@/hooks/useStomp'
import * as useModuleEventsModule from '@/hooks/useModuleEvents'
import * as useToastModule from '@/hooks/use-toast'
import { ApiError } from '@/services/api'
import { findBySlug } from '@/lib/experiments'
import { pageLoaders } from '@/modules/registry'
import overviewBefore from '@/test/fixtures/faulttolerance/overview-before-start.json'
import overviewAfterRuns from '@/test/fixtures/faulttolerance/overview-after-runs.json'
import statusRunning from '@/test/fixtures/faulttolerance/status-stream-running.json'
import statusDuringFailover from '@/test/fixtures/faulttolerance/status-during-failover.json'
import statusAfterDemotion from '@/test/fixtures/faulttolerance/status-after-demotion.json'
import streamStartSync from '@/test/fixtures/faulttolerance/stream-start-sync.json'
import crashSync from '@/test/fixtures/faulttolerance/crash-primary-sync.json'
import recoverFixture from '@/test/fixtures/faulttolerance/recover.json'
import modeAsync from '@/test/fixtures/faulttolerance/mode-async.json'
import runSyncRestored from '@/test/fixtures/faulttolerance/run-sync-restored.json'
import runSyncRecovered from '@/test/fixtures/faulttolerance/run-sync-recovered.json'
import runAsyncRestored from '@/test/fixtures/faulttolerance/run-async-restored.json'
import busy from '@/test/fixtures/faulttolerance/error-409-busy.json'
import runInProgress from '@/test/fixtures/faulttolerance/synthetic/run-in-progress.json'
import runPrimaryReturned from '@/test/fixtures/faulttolerance/synthetic/run-primary-returned.json'
import statusRestoredDown from '@/test/fixtures/faulttolerance/synthetic/status-restored-old-primary-down.json'
import { FaultTolerancePage } from './FaultTolerancePage'
import { MODULE_TOPIC, STATUS_POLL_INTERVAL_MS } from './useFaultTolerance'

const experiment = findBySlug('8-faulttolerance')
/** Acronyms and units that may be written in capitals (C6). Enumerations never may. */
const CAPITALS_ALLOWED = ['UDP', 'TCP', 'RMI', 'ID', 'STOMP', 'HTTP', 'OK']

let toast
let handlers

beforeEach(() => {
  vi.useFakeTimers()
  toast = vi.fn()
  handlers = {}
  vi.spyOn(useStompModule, 'useStomp').mockImplementation(() => ({
    status: 'connected',
    subscribe: (topic, callback) => {
      (handlers[topic] ??= []).push(callback)
      return () => { handlers[topic] = handlers[topic].filter((cb) => cb !== callback) }
    },
    onConnect: () => () => {},
  }))
  vi.spyOn(useModuleEventsModule, 'useModuleEvents').mockImplementation(() => ({ events: [], loading: false, error: null }))
  vi.spyOn(useToastModule, 'useToast').mockReturnValue({ toast, toasts: [], dismiss: vi.fn() })
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
})

const overviewWith = (current, latestRun = null) => ({ ...overviewBefore, current, latestRun })

function makeApi(overview = overviewBefore) {
  return {
    getOverview: vi.fn().mockResolvedValue(overview),
    getStatus: vi.fn().mockResolvedValue(overview.current),
    getLatestRun: vi.fn().mockResolvedValue(overview.latestRun),
    start: vi.fn(),
    startStream: vi.fn(),
    stopStream: vi.fn(),
    setMode: vi.fn(),
    crashPrimary: vi.fn(),
    recover: vi.fn(),
  }
}

async function flush(ms = 0) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

async function renderPage(api) {
  render(<MemoryRouter><FaultTolerancePage experiment={experiment} api={api} /></MemoryRouter>)
  await flush()
}

const button = (name) => screen.getByRole('button', { name })
const situation = () => screen.getByTestId('situation').textContent

/** Every visible text node under `root`, one by one (textContent would glue words together). */
function textNodes(root) {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  const out = []
  while (walker.nextNode()) {
    if (walker.currentNode.textContent.trim()) out.push(walker.currentNode.textContent)
  }
  return out
}

describe('FaultTolerancePage', () => {
  it('is registered for the faulttolerance module with the sections in the template order', async () => {
    expect((await pageLoaders.faulttolerance()).Page).toBe(FaultTolerancePage)
    await renderPage(makeApi())
    const order = [...document.querySelectorAll('[data-testid^="section-"]')].map((s) => s.getAttribute('data-testid'))
    expect(order).toEqual(['section-how-it-works', 'section-controls', 'section-visualisation', 'section-measurements',
      'section-event-log', 'section-what-to-notice'])
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Fault Tolerance')
    expect(screen.getByText(experiment.concept)).toBeTruthy()
  })

  it('explains the experiment with the eight served notes, verbatim', async () => {
    await renderPage(makeApi())
    const how = screen.getByTestId('section-how-it-works')
    for (const note of overviewBefore.notes) {
      expect(within(how).getByText(note)).toBeTruthy()
    }
  })

  it('shows a loading state, then a load error with a retry', async () => {
    const api = makeApi()
    api.getOverview.mockRejectedValueOnce(new ApiError({ status: 0, title: 'Backend unreachable', detail: 'Network Error',
      originalError: { code: 'ERR_NETWORK' } }))
    render(<MemoryRouter><FaultTolerancePage experiment={experiment} api={api} /></MemoryRouter>)
    expect(screen.getByTestId('loading')).toBeTruthy()
    await flush()
    expect(screen.getByTestId('load-error').textContent).toContain('The backend is not reachable.')
    fireEvent.click(button('Try again'))
    await flush()
    expect(screen.queryByTestId('load-error')).toBeNull()
    expect(situation()).toMatch(/^Experiment 8 has not started/)
  })

  it('walks the synchronous scenario, then the asynchronous one (crash, restore, recover, demotion; then a simulated loss)', async () => {
    const api = makeApi()
    await renderPage(api)
    expect(button('Crash primary').disabled).toBe(true)
    expect(screen.getByTestId('failover-timeline').textContent).toMatch(/^No failover yet/)

    // Start a synchronous stream.
    api.startStream.mockResolvedValue(streamStartSync)
    api.getStatus.mockResolvedValue(statusRunning)
    fireEvent.click(button('Start stream'))
    await flush()
    expect(api.startStream).toHaveBeenCalledWith({ mode: 'SYNCHRONOUS', ratePerSecond: 4 })
    expect(toast).toHaveBeenCalledWith({ title: 'Update stream started: synchronous updates, 4 per second.' })
    expect(situation()).toBe('Updates flow to node 1, the primary at epoch 2.')
    expect(button('Crash primary').disabled).toBe(false)

    // Crash the primary: the failover opens (synthetic in-progress run) and crash is withheld (Q3).
    api.crashPrimary.mockResolvedValue(crashSync)
    api.getStatus.mockResolvedValue(statusDuringFailover)
    api.getLatestRun.mockResolvedValue(runInProgress)
    fireEvent.click(button('Crash primary'))
    await flush()
    expect(toast).toHaveBeenCalledWith({ title: 'Node 1 crashed at epoch 2. Watch the timeline.' })
    expect(situation()).toMatch(/^Failover in progress/)
    expect(button('Crash primary').disabled).toBe(true)
    expect(screen.getByTestId('milestone-PROMOTED').getAttribute('data-state')).toBe('pending')

    // The polling reads the restored run: zero lost, recover offered (synthetic status: old primary still down).
    api.getStatus.mockResolvedValue(statusRestoredDown)
    api.getLatestRun.mockResolvedValue(runSyncRestored)
    await flush(STATUS_POLL_INTERVAL_MS)
    expect(screen.getByTestId('milestone-SERVICE_RESTORED').getAttribute('data-state')).toBe('recorded')
    expect(screen.getByTestId('loss-headline').textContent).toMatch(/^0 lost/)
    expect(within(screen.getByTestId('data-loss-panel')).queryByTestId('simulated-badge')).toBeNull()
    expect(button('Recover old primary').disabled).toBe(false)

    // Recover the old primary: it demotes itself to backup at a higher epoch.
    api.recover.mockResolvedValue(recoverFixture)
    api.getStatus.mockResolvedValue(statusAfterDemotion)
    api.getLatestRun.mockResolvedValue(runSyncRecovered)
    fireEvent.click(button('Recover old primary'))
    await flush()
    expect(within(screen.getByTestId('node-1')).getByTestId('demoted').textContent)
      .toBe('Demoted itself: primary at epoch 2, now backup at epoch 3')
    expect(screen.getByText('No two primaries: check passed')).toBeTruthy()
    expect(within(screen.getByTestId('four-measurements')).getByText('30.1 ms')).toBeTruthy()

    // Asynchronous: switch the mode, then a restored run with a simulated in-flight loss.
    api.setMode.mockResolvedValue(modeAsync)
    fireEvent.click(screen.getByRole('radio', { name: 'Asynchronous' }))
    await flush()
    expect(api.setMode).toHaveBeenCalledWith('ASYNCHRONOUS')
    expect(toast).toHaveBeenCalledWith({ title: 'Asynchronous mode applies from the next update.' })
    api.getLatestRun.mockResolvedValue(runAsyncRestored)
    await act(async () => {
      handlers[MODULE_TOPIC].forEach((cb) => cb({ type: 'DATA_LOSS_MEASURED' }))
      await vi.advanceTimersByTimeAsync(0)
    })
    const loss = screen.getByTestId('data-loss-panel')
    expect(screen.getByTestId('loss-headline').textContent).toMatch(/^4 lost/)
    expect(within(loss).getByTestId('simulated-badge').textContent).toContain(runAsyncRestored.dataLoss.simulatedReason)
  })

  it('shows a 409 busy calmly and moves focus to it', async () => {
    const api = makeApi(overviewWith(statusRunning))
    api.crashPrimary.mockRejectedValue(new ApiError({ status: 409, title: busy.title, detail: busy.detail,
      originalError: { response: { status: 409, data: busy } } }))
    await renderPage(api)
    fireEvent.click(button('Crash primary'))
    await flush()
    const alert = screen.getByTestId('action-error')
    expect(alert.getAttribute('data-kind')).toBe('busy')
    expect(document.activeElement).toBe(alert)
  })

  it.each([
    ['before the start', overviewBefore],
    ['after the runs', overviewAfterRuns],
    ['with the asynchronous loss', overviewWith(statusAfterDemotion, runAsyncRestored)],
    ['mid-failover (synthetic run-in-progress)', overviewWith(statusDuringFailover, runInProgress)],
  ])('writes no word in capitals %s: enumerations are always shown as words (C6)', async (_, overview) => {
    await renderPage(makeApi(overview))
    const capitals = textNodes(screen.getByTestId('experiment-layout'))
      .flatMap((text) => text.match(/[A-Za-z_]+/g) ?? [])
      .filter((word) => /[A-Z]{2,}/.test(word) && !CAPITALS_ALLOWED.includes(word))
    expect(capitals).toEqual([])
  })

  it.each([
    ['before the start', overviewBefore],
    ['when the old primary returned first (synthetic run-primary-returned)', overviewWith(statusDuringFailover, runPrimaryReturned)],
    ['mid-failover (synthetic run-in-progress)', overviewWith(statusDuringFailover, runInProgress)],
  ])('renders no "0" for a null in the node, measurement or data loss panels %s', async (_, overview) => {
    await renderPage(makeApi(overview))
    const areas = [screen.getByTestId('section-measurements'), ...overview.current.nodes
      .filter((node) => node.epoch === null).map((node) => screen.getByTestId(`node-${node.nodeId}`))]
    const zeros = areas.flatMap(textNodes).filter((text) => /(^|[^\d.])0([^\d.]|$)/.test(text))
    expect(zeros).toEqual([])
  })
})
