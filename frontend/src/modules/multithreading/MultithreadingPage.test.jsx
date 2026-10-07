import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import * as useStompModule from '@/hooks/useStomp'
import * as useToastModule from '@/hooks/use-toast'
import * as useModuleEventsModule from '@/hooks/useModuleEvents'
import { ApiError } from '@/services/api'
import { findBySlug } from '@/lib/experiments'
import overviewBefore from '@/test/fixtures/multithreading/overview-before.json'
import overviewAfter from '@/test/fixtures/multithreading/overview-after-batch.json'
import requestsFixture from '@/test/fixtures/multithreading/requests.json'
import batchFixture from '@/test/fixtures/multithreading/batch.json'
import backpressureFixture from '@/test/fixtures/multithreading/backpressure.json'
import busyFixture from '@/test/fixtures/multithreading/error-409-module-busy.json'
import nodeDownFixture from '@/test/fixtures/multithreading/error-409-node-down.json'
import validationFixture from '@/test/fixtures/multithreading/error-400-validation.json'
import { MultithreadingPage } from './MultithreadingPage'

const experiment = findBySlug('2-multithreading')

/** An ApiError exactly as services/api.js builds it from a real ProblemDetail body. */
function problem(body) {
  return new ApiError({ status: body.status, title: body.title, detail: body.detail, nodeId: body.nodeId ?? null,
    moduleId: body.moduleId ?? null, errors: body.errors ?? null })
}

function makeApi({ overview = overviewBefore, requests = [] } = {}) {
  return {
    getOverview: vi.fn().mockResolvedValue(overview),
    getRequests: vi.fn().mockResolvedValue(requests),
    submitBatch: vi.fn().mockResolvedValue(batchFixture),
    runBackpressure: vi.fn().mockResolvedValue(backpressureFixture),
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
  render(<MultithreadingPage experiment={experiment} api={api} />)
  await waitFor(() => expect(api.getOverview).toHaveBeenCalled())
  await screen.findByTestId('executor-view')
}

function pageText() {
  return document.body.textContent
}

describe('MultithreadingPage', () => {
  it('uses the shared layout with the approved concept, explanation and callouts', async () => {
    await renderPage(makeApi())

    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Multithreading')
    expect(screen.getByText(experiment.concept)).toBeTruthy()
    expect(screen.getByTestId('section-how-it-works').textContent).toContain('AbortPolicy')
    expect(screen.getByTestId('section-how-it-works').textContent)
      .toContain('the difference is configured rather than measured.')
    expect(screen.getByTestId('section-how-it-works').textContent).toContain('A Slow node is slow')
    expect(screen.getByTestId('section-how-it-works').textContent).not.toContain('SLOW')
    expect(screen.getByTestId('section-what-to-notice').querySelectorAll('li')).toHaveLength(3)
  })

  it('before first use: every node says "Not started yet" and every measurement is "—", never 0 or NaN', async () => {
    await renderPage(makeApi())

    expect(screen.getByTestId('executor-view').textContent).toContain('Not started yet')
    const cards = screen.getAllByTestId('metric-card')
    expect(cards).toHaveLength(6)
    for (const card of cards) {
      expect(within(card).getByLabelText('Not available').textContent).toBe('—')
    }
    expect(pageText()).not.toMatch(/NaN|undefined|Infinity/)
  })

  it('labels the sleeping workloads Simulated with their reasons, and not the hashing one', async () => {
    await renderPage(makeApi())

    const workloads = within(screen.getByRole('group', { name: 'Workload' }))
    const badges = workloads.getAllByTestId('simulated-badge')
    expect(badges).toHaveLength(2)
    const hashing = workloads.getByLabelText(/Hashing \(CPU\)/).closest('label')
    expect(within(hashing).queryByTestId('simulated-badge')).toBeNull()
    expect(workloads.getAllByRole('tooltip', { hidden: true }).map((t) => t.textContent))
      .toEqual(overviewBefore.workloads.filter((w) => w.simulated).map((w) => w.simulatedReason))
  })

  it('says the capacity profiles are configured, not measured', async () => {
    await renderPage(makeApi())

    expect(screen.getByText(overviewBefore.capacityNote)).toBeTruthy()
  })

  it('shows the real executor numbers after a batch, and the requests per thread', async () => {
    await renderPage(makeApi({ overview: overviewAfter, requests: requestsFixture }))

    const strip = screen.getByTestId('section-measurements')
    expect(strip.textContent).toContain('Completed tasks100')
    expect(strip.textContent).toContain('Throughput3.33per second')
    expect(strip.textContent).toContain('Average latency232ms')
    expect(strip.textContent).toContain('p95 latency400ms')
    expect(screen.getByTestId('executor-view').textContent).toContain('0 of 4 threads busy, 0 of 200 queued.')
    await screen.findByTestId('thread-tally')
    expect(screen.getByTestId('request-table').textContent).toContain(requestsFixture[0].threadName)
    expect(pageText()).not.toMatch(/NaN|undefined/)
  })

  it('sends the batch form to the selected node and shows the real result', async () => {
    const api = makeApi()
    await renderPage(api)

    fireEvent.change(screen.getByLabelText('Requests in the batch'), { target: { value: '100' } })
    fireEvent.click(screen.getByLabelText(/^Mixed/))
    fireEvent.click(screen.getByRole('button', { name: 'Send batch' }))

    await waitFor(() => expect(api.submitBatch).toHaveBeenCalledWith(1, { count: 100, type: 'MIXED', payloadSize: 50 }))
    expect((await screen.findByTestId('last-batch')).textContent)
      .toBe(`Batch ${batchFixture.batchId} on node 1: 0 of 100 rejected, 100 accepted.`)
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ description: '100 accepted, 0 rejected.' }))
  })

  it('runs the backpressure demo and shows how many were rejected', async () => {
    const api = makeApi()
    await renderPage(api)

    fireEvent.click(screen.getByLabelText(/^Node 3/))
    fireEvent.click(screen.getByRole('button', { name: 'Run backpressure demo' }))

    await waitFor(() => expect(api.runBackpressure).toHaveBeenCalledWith(3))
    expect((await screen.findByTestId('last-batch')).textContent).toContain('50 of 251 rejected, 201 accepted.')
  })

  it('shows the real 409 Module busy message', async () => {
    const api = makeApi()
    api.runBackpressure.mockRejectedValue(problem(busyFixture))
    await renderPage(api)

    fireEvent.click(screen.getByRole('button', { name: 'Run backpressure demo' }))

    expect((await screen.findByText(busyFixture.detail)).getAttribute('role')).toBe('alert')
  })

  it('puts each real 400 field message under its field', async () => {
    const api = makeApi()
    api.submitBatch.mockRejectedValue(problem(validationFixture))
    await renderPage(api)

    fireEvent.click(screen.getByRole('button', { name: 'Send batch' }))

    const count = screen.getByLabelText('Requests in the batch')
    await waitFor(() => expect(count.getAttribute('aria-invalid')).toBe('true'))
    expect(document.getElementById(count.getAttribute('aria-describedby')).textContent).toBe(validationFixture.errors.count)
    const payload = screen.getByLabelText('Work units per request')
    expect(document.getElementById(payload.getAttribute('aria-describedby')).textContent)
      .toBe(validationFixture.errors.payloadSize)
  })

  it('shows the real 409 Node down message', async () => {
    const api = makeApi()
    api.submitBatch.mockRejectedValue(problem(nodeDownFixture))
    await renderPage(api)

    fireEvent.click(screen.getByLabelText(/^Node 2/))
    fireEvent.click(screen.getByRole('button', { name: 'Send batch' }))

    expect(await screen.findByText('Node 2 is crashed')).toBeTruthy()
  })

  it('shows a crashed node as crashed, with "—" measurements', async () => {
    const crashedOverview = {
      ...overviewAfter,
      nodes: overviewAfter.nodes.map((node) =>
        node.nodeId === 1 ? { ...node, nodeStatus: 'CRASHED', serviceRunning: false, stats: null } : node),
    }
    await renderPage(makeApi({ overview: crashedOverview, requests: requestsFixture }))

    expect(screen.getByTestId('executor-view').textContent).toContain('Crashed.')
    expect(screen.getByRole('group', { name: 'Node' }).textContent).toContain('Crashed')
    for (const card of screen.getAllByTestId('metric-card')) {
      expect(within(card).getByLabelText('Not available').textContent).toBe('—')
    }
    expect(pageText()).not.toMatch(/NaN|undefined/)
  })

  it('guides the user when the node has no requests yet', async () => {
    await renderPage(makeApi({ overview: overviewAfter, requests: [] }))

    expect(await screen.findByText(/No requests on this node yet/)).toBeTruthy()
  })

  it('handles a failed fetch with a clear message, restart directions and a retry, without throwing', async () => {
    const api = makeApi()
    api.getOverview.mockRejectedValue(new ApiError({ status: 0, title: 'Backend unreachable', detail: 'Network Error' }))
    render(<MultithreadingPage experiment={experiment} api={api} />)

    expect(await screen.findByText('Could not load the multithreading data.')).toBeTruthy()
    expect(screen.getByText('The backend is not reachable.')).toBeTruthy()
    expect(pageText()).toContain('.\\mvnw.cmd spring-boot:run')
    expect(screen.getByRole('button', { name: 'Send batch' }).disabled).toBe(true)

    api.getOverview.mockResolvedValue(overviewBefore)
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByTestId('executor-view')).toBeTruthy()
    expect(pageText()).not.toMatch(/NaN|undefined/)
  })

  it('shows the not-reachable banner while STOMP is reconnecting', async () => {
    stompStatus = 'reconnecting'
    await renderPage(makeApi())

    expect(screen.getByText('The backend is not reachable.')).toBeTruthy()
  })

  it('loads the requests of the node the user selects', async () => {
    const api = makeApi()
    await renderPage(api)

    fireEvent.click(screen.getByLabelText(/^Node 3/))

    await waitFor(() => expect(api.getRequests).toHaveBeenCalledWith(3, 100))
    expect(screen.getByText('Latest requests on node 3')).toBeTruthy()
  })
})
