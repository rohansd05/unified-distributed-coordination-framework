import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import * as useStompModule from '@/hooks/useStomp'
import { PROVIDER_FUTURE, ROUTER_FUTURE } from '@/routes'
import { ScenariosPage } from './ScenariosPage'

// Hand-built in the 9A.6 shapes (docs/tracks/track-b-swanand.md, "9A.6 APIs"); messages from the
// 9A.7 end-to-end run. Not a capture.
const SCENARIOS = [
  {
    id: 'mapreduce-incident',
    title: 'Analyse the incident with MapReduce',
    summary: 'The leader crashes and a new one takes over.',
    modules: ['election', 'mapreduce'],
    steps: [
      { index: 1, narration: 'We run a first MapReduce job.' },
      { index: 2, narration: 'We crash the leader, on every protocol at once.' },
    ],
  },
  {
    id: 'matrix-leader-crash',
    title: 'The leader dies during a matrix multiply',
    summary: 'A parallel matrix multiply runs from the leader when the leader crashes.',
    modules: ['election', 'rmi', 'mpi', 'matrix'],
    steps: [{ index: 1, narration: 'We start the largest multiply.' }],
  },
]

const FINISHED = {
  runId: 1,
  scenarioId: 'mapreduce-incident',
  state: 'PASSED',
  startedAt: '2026-10-19T10:00:00Z',
  finishedAt: '2026-10-19T10:00:05Z',
  verdict: 'Passed: all 2 steps met their expectations.',
  endState: {
    description: 'Nodes 1, 2, 3 and 4 are up; node 5 is down. Node 4 is the leader.',
    leaderId: 4,
    primaryId: null,
    nodes: [1, 2, 3, 4].map((nodeId) => ({ nodeId, status: 'UP', roles: nodeId === 4 ? ['LEADER'] : [] }))
      .concat({ nodeId: 5, status: 'CRASHED', roles: [] }),
  },
  steps: [
    { index: 1, narration: 'We run a first MapReduce job.', state: 'PASSED', startedAt: '2026-10-19T10:00:00Z', durationMs: 947,
      facts: { coordinatorId: 5 }, message: 'Node 5 coordinated the word count job.' },
    { index: 2, narration: 'We crash the leader, on every protocol at once.', state: 'PASSED', startedAt: '2026-10-19T10:00:01Z',
      durationMs: 2, facts: { crashedNodeId: 5 }, message: 'Node 5 went down on every protocol (event 320).' },
  ],
}

const ev = (sequence, type, data, message = null) =>
  ({ sequence, lamportTime: sequence, nodeId: 0, module: 'scenario', type, data, message, wallTime: '2026-10-19T10:00:00Z' })

let stomp
let emit

function makeApi(overrides = {}) {
  return {
    getScenarios: vi.fn().mockResolvedValue(SCENARIOS),
    getLatestRun: vi.fn().mockResolvedValue(null),
    getRun: vi.fn().mockResolvedValue(FINISHED),
    startRun: vi.fn().mockResolvedValue({ runId: 1, scenarioId: 'mapreduce-incident' }),
    ...overrides,
  }
}

function renderPage(api = makeApi()) {
  const router = createMemoryRouter([{ path: '/scenarios', element: <ScenariosPage api={api} /> }],
    { initialEntries: ['/scenarios'], future: ROUTER_FUTURE })
  render(<RouterProvider router={router} future={PROVIDER_FUTURE} />)
  return api
}

const steps = () => screen.queryAllByTestId('scenario-step')
const runButtons = () => screen.getAllByRole('button', { name: /^Run "/ })

beforeEach(() => {
  stomp = {
    status: 'connected',
    subscribe: vi.fn((destination, handler) => { emit = handler; return () => {} }),
    onConnect: vi.fn(() => () => {}),
  }
  vi.spyOn(useStompModule, 'useStomp').mockImplementation(() => stomp)
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('ScenariosPage', () => {
  it('lists every scenario with its summary, modules and a Run button, and an empty run panel', async () => {
    renderPage()
    const cards = await screen.findAllByTestId('scenario-card')
    expect(cards).toHaveLength(2)
    expect(within(cards[0]).getByRole('heading', { name: 'Analyse the incident with MapReduce' })).toBeTruthy()
    expect(within(cards[0]).getByText('Exp 7: MapReduce')).toBeTruthy()
    expect(within(cards[1]).getByText('Exp 10: Parallel Matrix Multiplication')).toBeTruthy()
    expect(runButtons().every((b) => !b.disabled)).toBe(true)
    expect(screen.getByText(/No scenario has run since the backend started/)).toBeTruthy()
    expect(screen.queryByText(/planned|coming soon|phase 13/i)).toBeNull()
    expect(stomp.subscribe).toHaveBeenCalledWith('/topic/events', expect.any(Function))
  })

  it('runs a scenario, follows it live from /topic/events, then shows the verdict and the end state', async () => {
    const api = renderPage()
    await screen.findAllByTestId('scenario-card')
    fireEvent.click(screen.getByRole('button', { name: 'Run "Analyse the incident with MapReduce"' }))
    await waitFor(() => expect(api.startRun).toHaveBeenCalledWith('mapreduce-incident'))
    await waitFor(() => expect(steps()).toHaveLength(2))
    expect(steps().map((s) => s.dataset.state)).toEqual(['PENDING', 'PENDING'])
    expect(runButtons().every((b) => b.disabled)).toBe(true)

    act(() => {
      emit(ev(10, 'SCENARIO_STARTED', { runId: 1, scenarioId: 'mapreduce-incident', title: 'x', steps: 2 }))
      emit(ev(11, 'STEP_STARTED', { runId: 1, scenarioId: 'mapreduce-incident', stepIndex: 1, narration: 'x' }))
      emit({ ...ev(12, 'JOB_STARTED', { runId: 'c18028b1' }), module: 'mapreduce' })
    })
    expect(steps()[0].dataset.state).toBe('RUNNING')
    expect(steps()[0].getAttribute('aria-current')).toBe('step')
    expect(within(steps()[0]).getByText('Running')).toBeTruthy()

    act(() => emit(ev(13, 'STEP_PASSED', { runId: 1, stepIndex: 1, durationMs: 947, facts: { coordinatorId: 5 } },
      'Step 1 passed: Node 5 coordinated the word count job.')))
    expect(steps()[0].dataset.state).toBe('PASSED')
    expect(within(steps()[0]).getByText('Node 5 coordinated the word count job.')).toBeTruthy()
    expect(within(steps()[0]).getByText('947 ms')).toBeTruthy()
    expect(within(steps()[0]).getByText('Coordinator id')).toBeTruthy()

    act(() => emit(ev(14, 'SCENARIO_FINISHED', { runId: 1, scenarioId: 'mapreduce-incident', outcome: 'PASSED',
      verdict: 'Passed: all 2 steps met their expectations.' })))
    await waitFor(() => expect(api.getRun).toHaveBeenCalledWith(1))
    const verdict = await screen.findByTestId('verdict')
    expect(verdict.textContent).toContain('Passed: all 2 steps met their expectations.')
    expect((await screen.findByTestId('end-state')).textContent).toContain('Node 4 is the leader.')
    expect(screen.getByRole('img', { name: /node 4 up, leader; node 5 down/ })).toBeTruthy()
    expect(runButtons().every((b) => !b.disabled)).toBe(true)
    expect(screen.getByText('Last run: Passed')).toBeTruthy()
    expect(screen.getByText('took 5.0 s')).toBeTruthy()
  })

  it('answers 409 calmly: another scenario is running, and that run is shown', async () => {
    const busy = Object.assign(new Error('busy'), { status: 409, title: 'Scenario busy' })
    const running = { ...FINISHED, runId: 4, scenarioId: 'matrix-leader-crash', state: 'RUNNING', finishedAt: null,
      verdict: null, endState: null, steps: [{ index: 1, narration: 'We start the largest multiply.', state: 'RUNNING',
        startedAt: null, durationMs: null, facts: {}, message: null }] }
    const api = makeApi({
      startRun: vi.fn().mockRejectedValue(busy),
      getLatestRun: vi.fn().mockResolvedValueOnce(null).mockResolvedValue(running),
    })
    renderPage(api)
    await screen.findAllByTestId('scenario-card')
    fireEvent.click(screen.getByRole('button', { name: 'Run "Analyse the incident with MapReduce"' }))
    const problem = await screen.findByTestId('start-problem')
    expect(problem.getAttribute('role')).toBe('status')
    expect(problem.textContent).toMatch(/Another scenario is running/)
    expect(await screen.findByRole('region', { name: 'The leader dies during a matrix multiply' })).toBeTruthy()
    expect(screen.getByText('Running now')).toBeTruthy()
  })

  it('shows a failed verdict with the failed and skipped steps in words', async () => {
    const failed = { ...FINISHED, state: 'FAILED', verdict: 'Failed at step 1: Job c18028b1 failed.',
      steps: [{ ...FINISHED.steps[0], state: 'FAILED', message: 'Job c18028b1 failed.' },
        { ...FINISHED.steps[1], state: 'SKIPPED', startedAt: null, durationMs: null, facts: {}, message: 'Skipped: step 1 failed.' }] }
    renderPage(makeApi({ getLatestRun: vi.fn().mockResolvedValue(failed) }))
    const verdict = await screen.findByTestId('verdict')
    expect(verdict.textContent).toContain('Failed at step 1: Job c18028b1 failed.')
    expect(within(steps()[0]).getByText('Failed')).toBeTruthy()
    expect(within(steps()[1]).getByText('Skipped')).toBeTruthy()
  })

  it('polls GET /runs/latest every second while a run is active and no event arrives', async () => {
    const running = { ...FINISHED, state: 'RUNNING', finishedAt: null, verdict: null, endState: null,
      steps: FINISHED.steps.map((s, i) => (i === 0 ? s : { ...s, state: 'RUNNING', message: null, durationMs: null })) }
    const api = makeApi({ getLatestRun: vi.fn().mockResolvedValueOnce(running).mockResolvedValue(FINISHED) })
    renderPage(api)
    await waitFor(() => expect(steps()).toHaveLength(2))
    await waitFor(() => expect(api.getLatestRun.mock.calls.length).toBeGreaterThanOrEqual(2), { timeout: 3000 })
    expect((await screen.findByTestId('verdict')).textContent).toContain('Passed')
  })

  it('links to the Timeline with the scenario modules in the URL', async () => {
    renderPage(makeApi({ getLatestRun: vi.fn().mockResolvedValue(FINISHED) }))
    const link = await screen.findByRole('link', { name: 'See in Timeline' })
    const params = new URLSearchParams(link.getAttribute('href').split('?')[1])
    expect(link.getAttribute('href').startsWith('/timeline?')).toBe(true)
    expect(params.get('modules')).toBe('scenario,cluster,election,mapreduce')
    expect(params.get('order')).toBe('arrival')
  })

  it('presenter view shows the current narration in large text, follows live steps and closes with Escape', async () => {
    renderPage()
    await screen.findAllByTestId('scenario-card')
    act(() => {
      emit(ev(10, 'SCENARIO_STARTED', { runId: 1, scenarioId: 'mapreduce-incident', title: 'x', steps: 2 }))
      emit(ev(11, 'STEP_STARTED', { runId: 1, stepIndex: 1 }))
    })
    const toggle = screen.getByRole('button', { name: 'Presenter view' })
    fireEvent.click(toggle)
    expect(toggle.getAttribute('aria-pressed')).toBe('true')
    const dialog = screen.getByRole('dialog', { name: 'Presenter view' })
    expect(within(dialog).getByTestId('presenter-narration').textContent).toBe('We run a first MapReduce job.')
    expect(within(dialog).getByText('Step 1 of 2')).toBeTruthy()
    act(() => {
      emit(ev(12, 'STEP_PASSED', { runId: 1, stepIndex: 1, durationMs: 9, facts: {} }, 'Step 1 passed: Node 5 coordinated.'))
      emit(ev(13, 'STEP_STARTED', { runId: 1, stepIndex: 2 }))
    })
    expect(within(dialog).getByTestId('presenter-narration').textContent).toBe('We crash the leader, on every protocol at once.')
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('shows a calm notice when the scenarios cannot be loaded', async () => {
    const down = Object.assign(new Error('Network Error'), { status: 0, detail: 'Unable to connect to backend' })
    renderPage(makeApi({ getScenarios: vi.fn().mockRejectedValue(down) }))
    expect((await screen.findByText(/The scenarios could not be loaded/)).textContent).toContain('Unable to connect to backend')
  })
})
