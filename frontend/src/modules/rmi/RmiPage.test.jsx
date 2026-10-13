import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import * as useStompModule from '@/hooks/useStomp'
import * as useModuleEventsModule from '@/hooks/useModuleEvents'
import * as useToastModule from '@/hooks/use-toast'
import { ApiError } from '@/services/api'
import { findBySlug } from '@/lib/experiments'
import { modulePages } from '@/modules/registry'
import overviewAfter from '@/test/fixtures/rmi/overview-after-start.json'
import overviewCrashed from '@/test/fixtures/rmi/overview-node-crashed.json'
import startFixture from '@/test/fixtures/rmi/start.json'
import registriesAllUp from '@/test/fixtures/rmi/registries-all-up.json'
import callsHistory from '@/test/fixtures/rmi/calls-history.json'
import callPing from '@/test/fixtures/rmi/call-ping.json'
import callTimedOut from '@/test/fixtures/rmi/call-timed-out.json'
import callCrashedTarget from '@/test/fixtures/rmi/call-crashed-target.json'
import callById from '@/test/fixtures/rmi/call-by-id.json'
import comparisonHash from '@/test/fixtures/rmi/comparison-compute-hash.json'
import roundsError from '@/test/fixtures/rmi/error-400-rounds.json'
import unreadable from '@/test/fixtures/rmi/error-400-unreadable.json'
import unknownNode from '@/test/fixtures/rmi/error-404-unknown-node.json'
import unknownCall from '@/test/fixtures/rmi/error-404-unknown-call.json'
import busy from '@/test/fixtures/rmi/error-409-busy.json'
import callerCrashed from '@/test/fixtures/rmi/error-409-caller-crashed.json'
import compareTargetDown from '@/test/fixtures/rmi/error-409-compare-target-down.json'
import nodeStateConflict from '@/test/fixtures/replication/error-409-node-state.json'
import { RmiPage } from './RmiPage'

const experiment = findBySlug('1-rmi')
const copy = (value) => JSON.parse(JSON.stringify(value))

/** An ApiError exactly as services/api.js builds it from a real ProblemDetail body (original response kept). */
function problem(body) {
  return new ApiError({ status: body.status, title: body.title, detail: body.detail, nodeId: body.nodeId ?? null,
    moduleId: body.moduleId ?? null, errors: body.errors ?? null,
    originalError: { response: { status: body.status, data: body } } })
}

/** The module's whole api, mocked: no test reaches the network. */
function makeApi({ overview = overviewAfter, history = callsHistory } = {}) {
  return {
    getOverview: vi.fn().mockResolvedValue(overview),
    start: vi.fn().mockResolvedValue(overview === overviewAfter ? startFixture : overview),
    getRegistries: vi.fn().mockResolvedValue(registriesAllUp),
    getRegistry: vi.fn(),
    getHistory: vi.fn().mockResolvedValue(history),
    getLatest: vi.fn(),
    getCall: vi.fn().mockResolvedValue(callById),
    call: vi.fn().mockResolvedValue(callPing),
    compare: vi.fn().mockResolvedValue(comparisonHash),
    crashNode: vi.fn().mockResolvedValue({}),
    recoverNode: vi.fn().mockResolvedValue({}),
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
  render(<MemoryRouter><RmiPage experiment={experiment} api={api} /></MemoryRouter>)
  await screen.findByTestId('module-status')
  await waitFor(() => expect(api.getHistory).toHaveBeenCalled())
}

const button = (name) => screen.getByRole('button', { name })
const select = (label, value) => fireEvent.change(screen.getByLabelText(label), { target: { value } })
const actionError = () => screen.findByTestId('action-error')
const ALL_CAPS_WORD = /\b[A-Z]{2,}\b/g
const ALLOWED_ABBREVIATIONS = new Set(['RMI', 'SHA', 'UTF', 'DGC', 'UTC'])

describe('RmiPage: layout and states', () => {
  it('is registered for the rmi module and uses the shared layout with the concept and the explanation', async () => {
    expect(modulePages.rmi).toBe(RmiPage)
    await renderPage(makeApi())
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Client-Server via Java RMI')
    expect(screen.getByText(experiment.concept)).toBeTruthy()
    expect(screen.getByTestId('section-how-it-works').textContent).toContain('stub')
  })

  it('starts RMI on mount, once', async () => {
    const api = makeApi()
    await renderPage(api)
    expect(api.start).toHaveBeenCalledTimes(1)
    expect(screen.getByTestId('module-status').textContent).toContain('RMI endpoints exported on 5 of 5 nodes')
  })

  it('shows a loading skeleton until the first answer', () => {
    const api = makeApi()
    const never = new Promise(() => {})
    api.getOverview.mockReturnValue(never)
    api.start.mockReturnValue(never)
    render(<MemoryRouter><RmiPage experiment={experiment} api={api} /></MemoryRouter>)
    expect(screen.getByTestId('loading')).toBeTruthy()
  })

  it('shows an error with retry when the module cannot be loaded', async () => {
    const api = makeApi()
    const offline = new ApiError({ status: 0, title: 'Backend unreachable', detail: 'Network Error', originalError: { code: 'ERR_NETWORK' } })
    api.getOverview.mockRejectedValue(offline)
    api.start.mockRejectedValue(offline)
    render(<MemoryRouter><RmiPage experiment={experiment} api={api} /></MemoryRouter>)
    const alert = await screen.findByTestId('load-error')
    expect(alert.textContent).toContain('The backend is not reachable')
    const reads = api.getOverview.mock.calls.length
    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(api.getOverview.mock.calls.length).toBe(reads + 1))
  })

  it('guides the first step when nothing has been called, and shows n/a, never 0, for every unmeasured figure', async () => {
    await renderPage(makeApi({ history: [] }))
    expect(screen.getByTestId('history-empty').textContent).toContain('start with a ping')
    const cards = within(screen.getByTestId('section-measurements')).getAllByTestId('metric-card')
    expect(cards).toHaveLength(5)
    for (const card of cards) {
      expect(within(card).getByTestId('not-available')).toBeTruthy()
      expect(card.querySelector('p').textContent).not.toMatch(/\b0\b/)
    }
    const notice = screen.getByTestId('section-what-to-notice').textContent
    expect(notice).toContain('usually')
    expect(notice).not.toContain('Measured here')
  })

  it('appends measured figures to What to notice when the history holds them', async () => {
    await renderPage(makeApi())
    const notice = screen.getByTestId('section-what-to-notice').textContent
    expect(notice).toContain('the earliest kept ping to node 2 took 1.735 ms to look up and 7.237 ms for the call')
    expect(notice).toContain('Latest comparison: ping on node 3')
  })

  it('shows exactly one Simulated badge, for the co-hosting of the nodes', async () => {
    await renderPage(makeApi())
    expect(screen.getAllByTestId('simulated-badge')).toHaveLength(1)
    expect(within(screen.getByTestId('node-map')).getByTestId('simulated-badge')).toBeTruthy()
    expect(within(screen.getByTestId('section-measurements')).queryByTestId('simulated-badge')).toBeNull()
  })

  it('lists every registry and draws the last call from the overview', async () => {
    await renderPage(makeApi({ overview: overviewCrashed }))
    expect(screen.getAllByTestId(/^registry-\d$/)).toHaveLength(5)
    expect(screen.getByTestId('map-node-3').getAttribute('data-status')).toBe('down')
    expect(screen.getByTestId('call-arrow').getAttribute('data-outcome')).toBe('success')
  })

  it('writes no all-caps word except known abbreviations', async () => {
    await renderPage(makeApi())
    const words = (document.body.textContent.match(ALL_CAPS_WORD) ?? []).filter((word) => !ALLOWED_ABBREVIATIONS.has(word))
    expect(words).toEqual([])
  })
})

describe('RmiPage: calls and comparisons', () => {
  it('invokes a ping and shows the result, the toast and the announcement', async () => {
    const api = makeApi()
    await renderPage(api)
    fireEvent.click(button('Invoke call'))
    const result = await screen.findByTestId('action-result')
    expect(api.call).toHaveBeenCalledWith({ fromNode: 1, targetNode: 2, method: 'PING' })
    expect(result.textContent).toContain('jdk.proxy2.$Proxy111')
    expect(result.textContent).toContain('7.237 ms')
    expect(toast).toHaveBeenCalledWith({ title: 'ping to node 2 answered, round trip 7.237 ms.' })
  })

  it('disables the action buttons and shows progress while its own request is in flight', async () => {
    const api = makeApi()
    let finish
    api.call.mockReturnValue(new Promise((resolve) => { finish = resolve }))
    await renderPage(api)
    fireEvent.click(button('Invoke call'))
    expect(screen.getByTestId('pending').textContent).toContain('Calling node 2 (ping) from node 1')
    for (const name of ['Invoke call', 'Compare local and remote', 'Refresh registries', 'Crash node 1']) {
      expect(button(name).disabled).toBe(true)
    }
    finish(callPing)
    await screen.findByTestId('action-result')
    expect(button('Invoke call').disabled).toBe(false)
  })

  it('shows a BUSY overview from another tab as a banner but keeps the buttons enabled', async () => {
    const busyOverview = { ...copy(overviewAfter), status: 'BUSY', actionInProgress: busy.actionInProgress }
    const api = makeApi({ overview: busyOverview })
    await renderPage(api)
    expect(screen.getByTestId('busy-banner').textContent).toContain('COMPUTE_HASH from node 1 to node 2')
    expect(button('Invoke call').disabled).toBe(false)
    expect(button('Compare local and remote').disabled).toBe(false)
  })

  it('shows the real TIMED_OUT failure at the CALL stage as data, with a destructive toast', async () => {
    const api = makeApi()
    api.call.mockResolvedValue(callTimedOut)
    await renderPage(api)
    select('Method', 'COMPUTE_HASH')
    fireEvent.click(button('Invoke call'))
    const result = await screen.findByTestId('action-result')
    expect(api.call).toHaveBeenCalledWith({ fromNode: 1, targetNode: 2, method: 'COMPUTE_HASH', rounds: 100000 })
    expect(result.textContent).toContain('Timed out')
    expect(result.textContent).toContain('the method call')
    expect(result.textContent).toContain('51.310 ms')
    expect(toast).toHaveBeenCalledWith({ variant: 'destructive', title: 'computeHash to node 2 failed', description: 'Timed out at the method call.' })
  })

  it('allows a crashed target and shows its LOOKUP failure, but disables a crashed caller', async () => {
    const api = makeApi({ overview: overviewCrashed })
    api.call.mockResolvedValue(callCrashedTarget)
    await renderPage(api)
    const callerOption = within(screen.getByLabelText('Calling node')).getByRole('option', { name: 'Node 3 (down)' })
    expect(callerOption.disabled).toBe(true)
    const targetOption = within(screen.getByLabelText('Target node')).getByRole('option', { name: 'Node 3 (down)' })
    expect(targetOption.disabled).toBe(false)
    select('Target node', '3')
    fireEvent.click(button('Invoke call'))
    const result = await screen.findByTestId('action-result')
    expect(result.textContent).toContain('Connection refused')
    expect(result.textContent).toContain('the registry lookup')
    expect(within(result).getAllByTestId('not-available').length).toBeGreaterThan(0)
  })

  it('compares local and remote and shows a negative overhead as measured, with the note', async () => {
    const api = makeApi()
    await renderPage(api)
    select('Method', 'COMPUTE_HASH')
    fireEvent.click(button('Compare local and remote'))
    await screen.findByTestId('comparison-result')
    expect(api.compare).toHaveBeenCalledWith({ fromNode: 1, targetNode: 2, method: 'COMPUTE_HASH', rounds: 100000 })
    expect(screen.getByTestId('comparison-overhead').textContent).toBe('-4.563 ms')
    expect(screen.getByTestId('comparison-ratio').textContent).toBe('0.949')
    expect(screen.getByTestId('comparison-note').textContent).toBe(comparisonHash.note)
    expect(toast).toHaveBeenCalledWith({ title: 'Comparison finished: overhead -4.563 ms.' })
  })

  it('checks the limits from the overview before sending', async () => {
    const api = makeApi()
    await renderPage(api)
    select('Method', 'COMPUTE_HASH')
    fireEvent.change(screen.getByLabelText('SHA-256 rounds'), { target: { value: '1000001' } })
    fireEvent.click(button('Invoke call'))
    expect(screen.getByTestId('rmi-rounds-error').textContent).toBe('Rounds must be a whole number from 1 to 1000000.')
    expect(api.call).not.toHaveBeenCalled()
  })

  it('crashes a node through the shared cluster call and says so', async () => {
    const api = makeApi()
    await renderPage(api)
    fireEvent.click(button('Crash node 3'))
    await waitFor(() => expect(api.crashNode).toHaveBeenCalledWith(3))
    await waitFor(() => expect(toast).toHaveBeenCalledWith({ title: 'Node 3 crashed for every experiment.' }))
  })
})

describe('RmiPage: errors', () => {
  async function failWith(error, { compare = false } = {}) {
    const api = makeApi()
    api.call.mockRejectedValue(error)
    api.compare.mockRejectedValue(error)
    await renderPage(api)
    fireEvent.click(button(compare ? 'Compare local and remote' : 'Invoke call'))
    const alert = await actionError()
    return { api, alert }
  }

  it('shows the real 409 busy with the server message and what is running, and retries nothing', async () => {
    const { api, alert } = await failWith(problem(busy))
    expect(alert.getAttribute('data-kind')).toBe('busy')
    expect(alert.textContent).toContain(busy.detail)
    expect(alert.textContent).toContain('Running now: COMPUTE_HASH from node 1 to node 2')
    expect(api.call).toHaveBeenCalledTimes(1)
  })

  it('shows the real 409 "Node down" for a crashed caller and for a crashed comparison target', async () => {
    const first = await failWith(problem(callerCrashed))
    expect(first.alert.textContent).toBe('Node down: Node 3 is crashed.')
    cleanup()
    const second = await failWith(problem(compareTargetDown), { compare: true })
    expect(second.alert.getAttribute('data-kind')).toBe('node-down')
  })

  it('shows 409 "Node state conflict" (capture from the shared exception handler)', async () => {
    const { alert } = await failWith(problem(nodeStateConflict))
    expect(alert.textContent).toBe('Node state conflict: Node 2 is already up.')
  })

  it('shows a 400 with errors beside the fields, and a 400 without errors as a generic message', async () => {
    const api = makeApi()
    api.call.mockRejectedValueOnce(problem(roundsError)).mockRejectedValueOnce(problem(unreadable))
    await renderPage(api)
    select('Method', 'COMPUTE_HASH')
    fireEvent.click(button('Invoke call'))
    await actionError()
    expect(screen.getByTestId('rmi-rounds-error').textContent).toBe('rounds must be 1-1000000, was 0')
    fireEvent.click(button('Invoke call'))
    await waitFor(() => expect(screen.getByTestId('action-error').getAttribute('data-kind')).toBe('bad-request'))
    expect(screen.getByTestId('action-error').textContent).toBe('The backend could not read the request: Failed to read request.')
    expect(screen.queryByTestId('rmi-rounds-error')).toBeNull()
  })

  it('shows a 404, a client timeout and a network error each with its own message', async () => {
    const notFound = await failWith(problem(unknownNode))
    expect(notFound.alert.textContent).toBe('Unknown node: No node with id 9.')
    cleanup()
    const timeout = await failWith(new ApiError({ status: 0, title: 'Backend unreachable', detail: 'timeout of 20000ms exceeded',
      originalError: { code: 'ECONNABORTED', config: { timeout: 20000 } } }))
    expect(timeout.alert.getAttribute('data-kind')).toBe('timeout')
    expect(timeout.alert.textContent).toContain('within 20 s')
    cleanup()
    const network = await failWith(new ApiError({ status: 0, title: 'Backend unreachable', detail: 'Network Error',
      originalError: { code: 'ERR_NETWORK' } }))
    expect(network.alert.getAttribute('data-kind')).toBe('network')
  })
})

describe('RmiPage: history', () => {
  it('opens a history entry by id, and shows a 404 for an entry no longer kept', async () => {
    const api = makeApi()
    await renderPage(api)
    const rows = screen.getAllByTestId('history-row')
    fireEvent.click(within(rows[7]).getByRole('button', { name: 'Show call 1' }))
    const detail = await screen.findByTestId('history-detail')
    await waitFor(() => expect(within(detail).getByTestId('call-result')).toBeTruthy())
    expect(api.getCall).toHaveBeenCalledWith(1)

    api.getCall.mockRejectedValue(problem(unknownCall))
    fireEvent.click(within(rows[6]).getByRole('button', { name: 'Show call 2' }))
    await waitFor(() => expect(within(screen.getByTestId('history-detail')).getByRole('alert').textContent)
      .toBe(`Unknown call: ${unknownCall.detail}.`))
  })
})
