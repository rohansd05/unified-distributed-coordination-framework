import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import * as useStompModule from '@/hooks/useStomp'
import * as useToastModule from '@/hooks/use-toast'
import * as useClusterModule from '@/hooks/useCluster'
import * as useModuleEventsModule from '@/hooks/useModuleEvents'
import { ApiError } from '@/services/api'
import { findBySlug } from '@/lib/experiments'
import overviewIdle from '@/test/fixtures/mapreduce/overview-idle.json'
import overviewAfterRuns from '@/test/fixtures/mapreduce/overview-after-runs.json'
import runAccepted from '@/test/fixtures/mapreduce/run-accepted.json'
import sampleRun from '@/test/fixtures/mapreduce/run-sample-word-count.json'
import crashRun from '@/test/fixtures/mapreduce/run-crash-retry.json'
import emptyRun from '@/test/fixtures/mapreduce/run-event-log-empty-result.json'
import history from '@/test/fixtures/mapreduce/runs-history.json'
import clusterAfterCrash from '@/test/fixtures/mapreduce/cluster-after-crash.json'
import busyFixture from '@/test/fixtures/mapreduce/error-409-busy.json'
import noLiveWorkerFixture from '@/test/fixtures/mapreduce/error-409-no-live-worker.json'
import unknownJobFixture from '@/test/fixtures/mapreduce/error-404-unknown-job.json'
import tooLargeFixture from '@/test/fixtures/mapreduce/error-413-body-too-large.json'
import missingFieldsFixture from '@/test/fixtures/mapreduce/error-400-missing-fields.json'
import { formatMillis } from './labels'
import { MapReducePage } from './MapReducePage'

const experiment = findBySlug('7-mapreduce')
const ALL_CAPS_WORD = /\b[A-Z]{2,}\b/g
const ALLOWED_ABBREVIATIONS = new Set(['TCP', 'UTF', 'R', 'MIB', 'KIB'])

/** An ApiError exactly as services/api.js builds it from a real ProblemDetail body. */
function problem(body) {
  return new ApiError({ status: body.status, title: body.title, detail: body.detail, nodeId: body.nodeId ?? null,
    moduleId: body.moduleId ?? null, errors: body.errors ?? null })
}

function makeApi(overrides = {}) {
  return {
    getOverview: vi.fn().mockResolvedValue(overviewIdle),
    getRuns: vi.fn().mockResolvedValue([]),
    getLatestRun: vi.fn().mockResolvedValue(null),
    getRun: vi.fn().mockResolvedValue(sampleRun),
    startRun: vi.fn().mockResolvedValue(runAccepted),
    exportEvents: vi.fn().mockResolvedValue(''),
    recoverNode: vi.fn().mockResolvedValue({}),
    ...overrides,
  }
}

let toast
let cluster
let refreshCluster

beforeEach(() => {
  toast = vi.fn()
  cluster = null
  refreshCluster = vi.fn()
  vi.spyOn(useToastModule, 'useToast').mockReturnValue({ toast, toasts: [], dismiss: vi.fn() })
  vi.spyOn(useStompModule, 'useStomp').mockReturnValue({ status: 'connected', subscribe: () => () => {}, onConnect: () => () => {} })
  vi.spyOn(useModuleEventsModule, 'useModuleEvents').mockReturnValue({ events: [], loading: false, error: null })
  vi.spyOn(useClusterModule, 'useCluster').mockImplementation(() => ({
    info: null, cluster, modules: [], loading: false, error: null, refresh: refreshCluster,
  }))
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

async function renderPage(api) {
  render(<MemoryRouter><MapReducePage experiment={experiment} api={api} /></MemoryRouter>)
  await waitFor(() => expect(api.getOverview).toHaveBeenCalled())
  await screen.findByRole('group', { name: 'Job' })
}

const run = () => fireEvent.click(screen.getByRole('button', { name: 'Run' }))
const measurementCards = () => within(screen.getByTestId('section-measurements')).getAllByTestId('metric-card')

describe('MapReducePage', () => {
  it('uses the shared layout: lab 7, the concept, how it works with sum;count, and three callouts; nothing simulated', async () => {
    await renderPage(makeApi())

    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('MapReduce')
    expect(screen.getByText('Split one big job across the workers, then combine their partial answers into one exact result.')).toBeTruthy()
    expect(screen.getByTestId('section-how-it-works').textContent).toContain('"sum;count"')
    expect(screen.getByTestId('section-what-to-notice').querySelectorAll('li')).toHaveLength(3)
    expect(screen.queryByTestId('simulated-badge')).toBeNull()
    expect(document.body.textContent).toContain('nothing on this page is simulated')
  })

  it('empty state (/runs/latest 404): guidance, every measurement "—", never 0 or NaN', async () => {
    await renderPage(makeApi())

    expect(screen.getByTestId('empty-state').textContent).toContain('No run yet.')
    for (const card of measurementCards()) {
      expect(within(card).getByLabelText('Not available').textContent).toBe('—')
    }
    expect(document.body.textContent).not.toMatch(/NaN|undefined/)
  })

  it('full flow: 202 RUNNING, polled to COMPLETED, results and a success toast', async () => {
    const api = makeApi({
      getRun: vi.fn().mockResolvedValue(sampleRun),
      getLatestRun: vi.fn().mockResolvedValueOnce(null).mockResolvedValue(sampleRun),
    })
    await renderPage(api)

    run()

    await waitFor(() => expect(api.startRun).toHaveBeenCalledWith({ jobId: 'word-count', inputType: 'SAMPLE', upload: null, crashWorkerId: null }))
    expect(toast).toHaveBeenCalledWith({ title: 'Word count started' })
    const total = formatMillis(sampleRun.report.timings.totalMillis)
    await waitFor(() => expect(screen.getByTestId('pipeline-status').textContent)
      .toBe(`Word count on Bundled sample text: completed in ${total} ms, 116 result keys.`), { timeout: 3000 })
    expect(api.getRun).toHaveBeenCalledWith(runAccepted.runId)
    expect(toast).toHaveBeenCalledWith({ title: `Word count completed in ${total} ms` })
    expect(within(screen.getByTestId('section-measurements')).getByTestId('results-count').textContent).toBe('All 116 keys.')
    expect(screen.getByRole('button', { name: 'Run' }).disabled).toBe(false)
  })

  it('upload: the file is read in the browser and sent as the JSON/Base64 body', async () => {
    const api = makeApi()
    await renderPage(api)
    fireEvent.click(screen.getByRole('radio', { name: /Your own \.txt file/ }))
    fireEvent.change(screen.getByLabelText('Text file (.txt, UTF-8)'), { target: { files: [new File(['hi'], 'notes.txt', { type: 'text/plain' })] } })

    run()

    await waitFor(() => expect(api.startRun).toHaveBeenCalledWith({
      jobId: 'word-count',
      inputType: 'UPLOAD',
      upload: { fileName: 'notes.txt', contentType: 'text/plain', contentBase64: 'aGk=', bytes: 2 },
      crashWorkerId: null,
    }))
  })

  it('upload over the cap: a clear message and nothing is sent', async () => {
    const api = makeApi({ getOverview: vi.fn().mockResolvedValue({ ...overviewIdle, limits: { ...overviewIdle.limits, uploadMaxBytes: 1 } }) })
    await renderPage(api)
    fireEvent.click(screen.getByRole('radio', { name: /Your own \.txt file/ }))
    fireEvent.change(screen.getByLabelText('Text file (.txt, UTF-8)'), { target: { files: [new File(['hi'], 'notes.txt')] } })
    run()

    expect(screen.getByText('The file is 2 bytes, larger than the 1 byte limit.')).toBeTruthy()
    expect(api.startRun).not.toHaveBeenCalled()
  })

  it('crash run: the retried tasks, the stage hit, the identical result proven against the earlier run, and Recover', async () => {
    cluster = clusterAfterCrash
    const api = makeApi({
      getOverview: vi.fn().mockResolvedValue(overviewAfterRuns),
      getRuns: vi.fn().mockResolvedValue(history),
      getLatestRun: vi.fn().mockResolvedValue(crashRun),
      getRun: vi.fn().mockResolvedValue(sampleRun),
    })
    await renderPage(api)

    await waitFor(() => expect(screen.getByTestId('section-what-to-notice').textContent)
      .toContain('All 116 result rows are identical to the earlier run d4055014 without a crash.'))
    expect(api.getRun).toHaveBeenCalledWith('d4055014')
    expect(screen.getByTestId('pipeline-crash').textContent).toBe('Node 3 was crashed right after its first map task was sent (the map stage).')
    expect(within(screen.getByTestId('pipeline-retries')).getAllByRole('listitem')).toHaveLength(2)
    expect(screen.getByTestId('task-table').textContent).toContain('Connection refused: connect')

    fireEvent.click(within(screen.getByTestId('down-nodes')).getByRole('button', { name: 'Recover node 3' }))
    await waitFor(() => expect(api.recoverNode).toHaveBeenCalledWith(3))
    await waitFor(() => expect(toast).toHaveBeenCalledWith({ title: 'Node 3 recovered for every experiment' }))
    expect(refreshCluster).toHaveBeenCalled()
  })

  it('crash run without a matching earlier run: states only what is known, never "identical"', async () => {
    const api = makeApi({ getRuns: vi.fn().mockResolvedValue([history[0]]), getLatestRun: vi.fn().mockResolvedValue(crashRun) })
    await renderPage(api)

    await waitFor(() => expect(screen.getByTestId('section-what-to-notice').textContent)
      .toContain('There is no earlier run of the same job on the same input without a crash'))
    expect(screen.getByTestId('section-what-to-notice').textContent).not.toContain('identical')
    expect(screen.getByTestId('section-what-to-notice').textContent).toContain('Node 3 was crashed during the map stage; 2 tasks were re-executed')
  })

  it('event-log input with an empty result: the backend\'s own notice, word for word', async () => {
    await renderPage(makeApi({ getLatestRun: vi.fn().mockResolvedValue(emptyRun) }))

    const notice = 'No line in this input has a node and a measured latency, so no average could be computed.'
    expect((await screen.findByTestId('run-notice')).textContent).toBe(notice)
    expect(screen.getByTestId('results-empty').textContent).toContain(notice)
    expect(screen.getByTestId('lines-dropped').textContent).toBe('Older event-log lines left out by the byte cap: 0.')
  })

  it.each([
    ['409 busy', busyFixture],
    ['409 no live worker', noLiveWorkerFixture],
    ['404 unknown job', unknownJobFixture],
    ['413 body too large', tooLargeFixture],
  ])('%s: the backend\'s title and detail inline, and the controls recover', async (_, body) => {
    const api = makeApi({ startRun: vi.fn().mockRejectedValue(problem(body)) })
    await renderPage(api)

    run()

    const alert = await screen.findByTestId('action-error')
    expect(alert.textContent).toContain(`${body.title}. ${body.detail}`)
    expect(toast).toHaveBeenCalledWith({ variant: 'destructive', title: body.title, description: body.detail })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Run' }).disabled).toBe(false))
  })

  it('409 no live worker links to the Cluster page', async () => {
    await renderPage(makeApi({ startRun: vi.fn().mockRejectedValue(problem(noLiveWorkerFixture)) }))
    run()
    const alert = await screen.findByTestId('action-error')
    expect(within(alert).getByRole('link', { name: 'Open the Cluster page' }).getAttribute('href')).toBe('/cluster')
  })

  it('400: the backend\'s field messages appear next to their fields', async () => {
    await renderPage(makeApi({ startRun: vi.fn().mockRejectedValue(problem(missingFieldsFixture)) }))
    run()
    expect(await screen.findByText('jobId is required')).toBeTruthy()
    expect(screen.getByText('inputType is required')).toBeTruthy()
  })

  it('error loading the overview: a message and a retry that recovers', async () => {
    const api = makeApi({
      getOverview: vi.fn()
        .mockRejectedValueOnce(new ApiError({ status: 0, title: 'Backend unreachable', detail: 'Network Error' }))
        .mockResolvedValue(overviewIdle),
    })
    render(<MemoryRouter><MapReducePage experiment={experiment} api={api} /></MemoryRouter>)

    expect((await screen.findByText('Could not load the MapReduce data.'))).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await screen.findByRole('group', { name: 'Job' })
  })

  it('while loading: skeletons', () => {
    const api = makeApi({ getOverview: vi.fn(() => new Promise(() => {})) })
    render(<MemoryRouter><MapReducePage experiment={experiment} api={api} /></MemoryRouter>)
    expect(document.querySelectorAll('[aria-busy="true"]').length).toBeGreaterThan(0)
  })

  it('a RUNNING run disables Run and shows "—" for every measurement', async () => {
    await renderPage(makeApi({ getLatestRun: vi.fn().mockResolvedValue(runAccepted), getRun: vi.fn(() => new Promise(() => {})) }))

    expect(screen.getByRole('button', { name: 'Run' }).disabled).toBe(true)
    for (const card of measurementCards()) {
      expect(within(card).getByLabelText('Not available').textContent).toBe('—')
    }
  })

  it('no ALL-CAPS words reach the page (R17), apart from known abbreviations', async () => {
    cluster = clusterAfterCrash
    await renderPage(makeApi({ getRuns: vi.fn().mockResolvedValue(history), getLatestRun: vi.fn().mockResolvedValue(crashRun) }))
    await screen.findByTestId('pipeline-crash')

    const words = (document.body.textContent.match(ALL_CAPS_WORD) ?? []).filter((word) => !ALLOWED_ABBREVIATIONS.has(word))
    expect(words).toEqual([])
  })
})
