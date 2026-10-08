import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import * as useStompModule from '@/hooks/useStomp'
import * as useModuleEventsModule from '@/hooks/useModuleEvents'
import { ApiError } from '@/services/api'
import { findBySlug } from '@/lib/experiments'
import { modulePages } from '@/modules/registry'
import overviewBefore from '@/test/fixtures/replication/overview-before.json'
import overviewAfterAsync from '@/test/fixtures/replication/overview-after-async.json'
import overviewTakeoverPending from '@/test/fixtures/replication/overview-takeover-pending.json'
import overviewAfterTakeover from '@/test/fixtures/replication/overview-after-takeover.json'
import replicasDiverged from '@/test/fixtures/replication/replicas-diverged.json'
import replicasConverged from '@/test/fixtures/replication/replicas-converged.json'
import writeSync from '@/test/fixtures/replication/write-sync.json'
import writeAsync from '@/test/fixtures/replication/write-async.json'
import readStale from '@/test/fixtures/replication/read-stale.json'
import crashBackup from '@/test/fixtures/replication/crash-backup.json'
import recoverBackup from '@/test/fixtures/replication/recover-backup.json'
import antiEntropy from '@/test/fixtures/replication/anti-entropy.json'
import staleInjection from '@/test/fixtures/replication/stale-injection.json'
import validationFixture from '@/test/fixtures/replication/error-400-validation.json'
import unknownNodeFixture from '@/test/fixtures/replication/error-404-unknown-node.json'
import nodeDownFixture from '@/test/fixtures/replication/error-409-node-down.json'
import nodeStateFixture from '@/test/fixtures/replication/error-409-node-state.json'
import eventsFixture from '@/test/fixtures/replication/events.json'
import { ReplicationPage } from './ReplicationPage'

const experiment = findBySlug('5-replication')
const copy = (value) => JSON.parse(JSON.stringify(value))

/** An ApiError exactly as services/api.js builds it from a real ProblemDetail body. */
function problem(body) {
  return new ApiError({ status: body.status, title: body.title, detail: body.detail, nodeId: body.nodeId ?? null,
    moduleId: body.moduleId ?? null, errors: body.errors ?? null })
}

/** The module's whole api, mocked: no test reaches the network. */
function makeApi({ overview = overviewBefore, replicas = replicasConverged } = {}) {
  return {
    getOverview: vi.fn().mockResolvedValue(overview),
    getReplicas: vi.fn().mockResolvedValue(replicas),
    read: vi.fn().mockResolvedValue(readStale),
    write: vi.fn().mockResolvedValue(writeSync),
    crashBackup: vi.fn().mockResolvedValue(crashBackup),
    recover: vi.fn().mockResolvedValue(recoverBackup),
    antiEntropy: vi.fn().mockResolvedValue(antiEntropy),
    injectStale: vi.fn().mockResolvedValue(staleInjection),
    getEvents: vi.fn().mockResolvedValue([]),
  }
}

let moduleEvents

beforeEach(() => {
  moduleEvents = []
  vi.spyOn(useStompModule, 'useStomp').mockImplementation(() => ({
    status: 'connected',
    subscribe: () => () => {},
    onConnect: () => () => {},
  }))
  vi.spyOn(useModuleEventsModule, 'useModuleEvents').mockImplementation(() => ({ events: moduleEvents, loading: false, error: null }))
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

async function renderPage(api) {
  render(<MemoryRouter><ReplicationPage experiment={experiment} api={api} /></MemoryRouter>)
  await waitFor(() => expect(api.getOverview).toHaveBeenCalled())
  await screen.findByTestId('module-status')
}

const form = (name) => screen.getByRole('form', { name })
const announcement = () => screen.getByTestId('section-controls').querySelector('[aria-live="polite"]').textContent
const pageText = () => document.body.textContent
const ALL_CAPS_WORD = /\b[A-Z]{2,}\b/g
const ALLOWED_ABBREVIATIONS = new Set(['TCP', 'ID'])

describe('ReplicationPage', () => {
  it('is registered for the replication module and uses the shared layout with the concept and the explanation', async () => {
    expect(modulePages.replication).toBe(ReplicationPage)
    await renderPage(makeApi())

    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Consistency and Replication')
    expect(screen.getByText(experiment.concept)).toBeTruthy()
    expect(screen.getByTestId('section-how-it-works').textContent).toContain('Lamport time')
  })

  it('shows the backend\'s last-writer-wins caveat as written, as the third thing to notice', async () => {
    await renderPage(makeApi())
    const callouts = screen.getByTestId('section-what-to-notice').querySelectorAll('li')
    expect(callouts).toHaveLength(3)
    expect(callouts[2].textContent).toBe(overviewBefore.conflictRuleNote)
  })

  it('loading: a busy placeholder until the overview arrives', async () => {
    const api = makeApi()
    api.getOverview.mockReturnValue(new Promise(() => {}))
    render(<MemoryRouter><ReplicationPage experiment={experiment} api={api} /></MemoryRouter>)
    expect(screen.getByText('Loading the replication module…')).toBeTruthy()
  })

  it('error: the backend message, an unreachable notice and a retry that reads again', async () => {
    const api = makeApi()
    api.getOverview.mockRejectedValue(new ApiError({ status: 0, title: 'Backend unreachable', detail: 'Network Error' }))
    render(<MemoryRouter><ReplicationPage experiment={experiment} api={api} /></MemoryRouter>)

    await screen.findByText('Could not load the replication module.')
    expect(screen.getByText('The backend is not reachable.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(api.getOverview.mock.calls.length).toBeGreaterThan(1))
  })

  it('empty (the real fresh overview): guidance everywhere, a dash for every unmeasured figure, never 0 or NaN', async () => {
    const empty = { ...copy(replicasConverged), referenceNodeId: null, consistent: null, divergences: null, rows: [] }
    await renderPage(makeApi({ replicas: empty }))

    expect(screen.getByText(/No keys yet/)).toBeTruthy()
    expect(screen.getByTestId('takeover-timeline').textContent).toContain('No primary selected yet.')
    expect(screen.getByTestId('health-table').textContent).toContain('No primary yet')
    const cards = within(screen.getByTestId('section-measurements')).getAllByTestId('metric-card')
    const values = cards.map((card) => card.querySelector('p.text-xl').textContent)
    expect(values).toEqual(['—', '450ms', '—', '—', '—'])
    expect(pageText()).not.toMatch(/NaN|undefined|Infinity|null/)
  })

  it('the asynchronous delay carries the Simulated badge with the backend\'s reason, on the model and the measurement', async () => {
    await renderPage(makeApi())
    const radio = screen.getByRole('radio', { name: /Asynchronous/ })
    const badge = within(radio.closest('label')).getByTestId('simulated-badge')
    expect(within(badge).getByRole('tooltip').textContent).toBe(overviewBefore.models[1].simulatedReason)
    const card = within(screen.getByTestId('section-measurements')).getByText('Asynchronous push delay').closest('[data-testid="metric-card"]')
    expect(within(card).getByRole('tooltip').textContent).toBe(overviewBefore.asyncDelayReason)
  })

  it('a synchronous write: sends key, value and model, shows every push, announces it and moves focus to the result', async () => {
    const api = makeApi()
    await renderPage(api)
    const write = form('Write a value')

    fireEvent.change(within(write).getByLabelText('Key'), { target: { value: 'balance' } })
    fireEvent.change(within(write).getByLabelText('Value'), { target: { value: '1000' } })
    fireEvent.click(within(write).getByRole('button', { name: 'Write' }))

    await waitFor(() => expect(api.write).toHaveBeenCalledWith({ key: 'balance', value: '1000', model: 'SYNCHRONOUS' }))
    const result = await within(write).findByRole('region', { name: 'Write result' })
    expect(result.textContent).toContain('Synchronous write of balance = 1000 confirmed by node 1')
    expect(result.textContent).toContain('Node 2: acknowledged, applied')
    await waitFor(() => expect(document.activeElement).toBe(result))
    expect(announcement()).toBe('Synchronous write of balance confirmed by node 1.')
  })

  it('an asynchronous write: PENDING in the result, the window open while the backend says PENDING, closed and announced on COMPLETE', async () => {
    const pending = { ...copy(overviewAfterAsync), status: 'RUNNING', latestWrite: writeAsync }
    const api = makeApi({ overview: pending })
    api.write.mockResolvedValue(writeAsync)
    await renderPage(api)
    expect(screen.getByTestId('pending-window').textContent).toContain('Replication window open')

    const write = form('Write a value')
    fireEvent.click(within(write).getByRole('radio', { name: /Asynchronous/ }))
    fireEvent.click(within(write).getByRole('button', { name: 'Write' }))
    await waitFor(() => expect(api.write).toHaveBeenCalledWith({ key: '', value: '', model: 'ASYNCHRONOUS' }))
    expect((await within(write).findByRole('region', { name: 'Write result' })).textContent)
      .toContain('The backups have not answered yet')

    api.getOverview.mockResolvedValue(overviewAfterAsync)   // the backend now reports COMPLETE
    await waitFor(() => expect(screen.queryByTestId('pending-window')).toBeNull(), { timeout: 3000 })
    await waitFor(() => expect(announcement()).toBe('Replication window closed: every backup has answered for balance.'), { timeout: 3000 })
  })

  it('read one replica: the state in words and shape, and the primary\'s version for comparison', async () => {
    const api = makeApi()
    await renderPage(api)
    const read = form('Read a replica')

    fireEvent.change(within(read).getByLabelText('Key'), { target: { value: 'balance' } })
    fireEvent.click(within(read).getByRole('button', { name: 'Read' }))

    await waitFor(() => expect(api.read).toHaveBeenCalledWith(2, 'balance'))
    const result = await within(read).findByRole('region', { name: 'Read result' })
    expect(result.textContent).toContain('Stale')
    expect(result.textContent).toContain('Node 2 holds 1000')
    expect(result.textContent).toContain('The primary, node 1, holds 2000')
    expect(result.querySelector('[data-glyph="stale"]')).not.toBeNull()
  })

  it('crash a backup: no dialog, announced, and focus moves to "Recover node 3"', async () => {
    const api = makeApi()
    await renderPage(api)

    fireEvent.click(screen.getByRole('button', { name: 'Crash node 3' }))

    await waitFor(() => expect(api.crashBackup).toHaveBeenCalledWith(3))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    const recover = await screen.findByRole('button', { name: 'Recover node 3' })
    await waitFor(() => expect(document.activeElement).toBe(recover))
    expect(announcement()).toBe('Node 3 crashed. Its replication port is closed, for every experiment.')
  })

  it('recover a crashed node: announced, and focus moves to its crash button', async () => {
    const api = makeApi({ overview: overviewTakeoverPending })
    api.recover.mockResolvedValue({ ...recoverBackup, nodeId: 1 })
    await renderPage(api)

    fireEvent.click(screen.getByRole('button', { name: 'Recover node 1' }))

    await waitFor(() => expect(api.recover).toHaveBeenCalledWith(1))
    const crash = await screen.findByRole('button', { name: 'Crash node 1' })
    await waitFor(() => expect(document.activeElement).toBe(crash))
    expect(announcement()).toBe('Node 1 recovered.')
  })

  it('recover a node that comes back as the primary (it has no crash button): focus moves to its label, which says primary', async () => {
    const api = makeApi({ overview: overviewTakeoverPending })
    api.recover.mockResolvedValue({ ...recoverBackup, nodeId: 1, role: 'PRIMARY', actingPrimary: true })
    await renderPage(api)

    fireEvent.click(screen.getByRole('button', { name: 'Recover node 1' }))

    await waitFor(() => expect(api.recover).toHaveBeenCalledWith(1))
    const row = screen.getAllByTestId('node-action-row')[0]
    const label = within(row).getByTestId('node-label')
    await waitFor(() => expect(document.activeElement).toBe(label))
    expect(label.textContent).toBe('Node 1, primary, up')
    expect(within(row).queryByRole('button')).toBeNull()
    expect(within(row).getByRole('link', { name: 'Cluster page' })).toBeTruthy()
    expect(announcement()).toBe('Node 1 recovered and is the primary again.')
  })

  it('the primary has no crash button here, only a link to the Cluster page', async () => {
    await renderPage(makeApi())
    const primaryRow = screen.getAllByTestId('node-action-row')[0]
    expect(primaryRow.textContent).toContain('Node 1, primary, up')
    expect(within(primaryRow).queryByRole('button')).toBeNull()
    expect(within(primaryRow).getByRole('link', { name: 'Cluster page' }).getAttribute('href')).toBe('/cluster')
  })

  it('anti-entropy offers only live backups (the real pending overview: node 1 crashed, node 2 primary) and reports the push', async () => {
    const api = makeApi({ overview: overviewTakeoverPending })
    await renderPage(api)
    const repair = form('Run anti-entropy')
    const options = within(repair).getAllByRole('option').map((o) => o.textContent)
    expect(options).toEqual(['Node 3', 'Node 4', 'Node 5'])

    fireEvent.click(within(repair).getByRole('button', { name: 'Run anti-entropy' }))

    await waitFor(() => expect(api.antiEntropy).toHaveBeenCalledWith(3))
    expect((await within(repair).findByRole('region', { name: 'Anti-entropy result' })).textContent)
      .toContain(`Node 1 pushed ${antiEntropy.pushed} items to node 3: ${antiEntropy.applied} applied`)
  })

  it('a stale update: delivered to the chosen backup, and its rejection explained with both Lamport times', async () => {
    const api = makeApi()
    await renderPage(api)
    const stale = form('Deliver a stale update')

    fireEvent.change(within(stale).getByLabelText('Key'), { target: { value: 'balance' } })
    fireEvent.change(within(stale).getByLabelText('Old value'), { target: { value: '500' } })
    fireEvent.click(within(stale).getByRole('button', { name: 'Deliver stale update' }))

    await waitFor(() => expect(api.injectStale).toHaveBeenCalledWith({ backupNodeId: 2, key: 'balance', staleValue: '500' }))
    expect((await within(stale).findByRole('region', { name: 'Stale update result' })).textContent)
      .toContain(`Node 2 rejected it as stale and kept 2000: the stale version had Lamport ${staleInjection.staleItem.lamportTime}`)
  })

  it('400: the field message beside the field and the ProblemDetail as an alert that takes focus (real validation body)', async () => {
    const api = makeApi()
    api.write.mockRejectedValue(problem(validationFixture))
    await renderPage(api)
    const write = form('Write a value')

    fireEvent.click(within(write).getByRole('button', { name: 'Write' }))

    const alert = await within(write).findByRole('alert')
    expect(alert.textContent).toBe(`${validationFixture.title}: ${validationFixture.detail}`)
    await waitFor(() => expect(document.activeElement).toBe(alert))
    const key = within(write).getByLabelText('Key')
    expect(key.getAttribute('aria-invalid')).toBe('true')
    expect(write.textContent).toContain(validationFixture.errors.key)
  })

  it('409 Node down and 409 Node state conflict and 404 Unknown node: the backend\'s title and detail are shown (real bodies)', async () => {
    const api = makeApi({ overview: overviewTakeoverPending })
    api.antiEntropy.mockRejectedValue(problem(nodeDownFixture))
    api.recover.mockRejectedValue(problem(nodeStateFixture))
    api.read.mockRejectedValue(problem(unknownNodeFixture))
    await renderPage(api)

    fireEvent.click(within(form('Run anti-entropy')).getByRole('button', { name: 'Run anti-entropy' }))
    expect((await within(form('Run anti-entropy')).findByRole('alert')).textContent)
      .toBe(`Node down: ${nodeDownFixture.detail}`)

    fireEvent.click(screen.getByRole('button', { name: 'Recover node 1' }))
    const nodes = screen.getAllByTestId('node-action-row')[0].closest('fieldset')
    expect((await within(nodes).findByRole('alert')).textContent).toBe(`Node state conflict: ${nodeStateFixture.detail}`)

    fireEvent.click(within(form('Read a replica')).getByRole('button', { name: 'Read' }))
    expect((await within(form('Read a replica')).findByRole('alert')).textContent)
      .toBe(`Unknown node: ${unknownNodeFixture.detail}`)
  })

  it('busy (derived: status BUSY with an action): every action is disabled and the status says what is running', async () => {
    await renderPage(makeApi({ overview: { ...copy(overviewAfterTakeover), status: 'BUSY', actionInProgress: 'Write of \'k\' [synchronous]' } }))
    expect(screen.getByTestId('module-status').textContent).toContain('Busy (Write of \'k\' [synchronous])')
    for (const name of ['Write', 'Run anti-entropy', 'Deliver stale update', 'Crash node 2']) {
      expect(screen.getByRole('button', { name }).disabled).toBe(true)
    }
  })

  it('the takeover timeline and the grid come from the real takeover overview and events', async () => {
    moduleEvents = eventsFixture
    await renderPage(makeApi({ overview: overviewAfterTakeover, replicas: replicasDiverged }))
    expect(screen.getAllByTestId('timeline-entry')).toHaveLength(3)
    expect(screen.getAllByTestId('replica-cell').some((cell) => cell.getAttribute('data-state') === 'MISSING')).toBe(true)
    expect(screen.getByTestId('health-table').textContent).toContain('Measured by node 1')
  })

  it('shows no all-caps words anywhere it authors, whatever it renders (known abbreviations aside)', async () => {
    for (const overview of [overviewBefore, overviewAfterAsync, overviewTakeoverPending, overviewAfterTakeover]) {
      moduleEvents = eventsFixture
      await renderPage(makeApi({ overview, replicas: replicasDiverged }))
      // The kit's event log shows the backend's own event messages verbatim; everything else is this page's text.
      const authored = [...screen.getByTestId('experiment-layout').querySelectorAll('[data-testid^="section-"]')]
        .filter((section) => section.dataset.testid !== 'section-event-log')
        .map((section) => section.textContent).join(' ')
      const words = (authored.match(ALL_CAPS_WORD) ?? []).filter((word) => !ALLOWED_ABBREVIATIONS.has(word))
      expect(words).toEqual([])
      expect(pageText()).not.toMatch(/NaN|undefined|Infinity/)
      cleanup()
    }
  })

  it('layout rules (E2d): nothing is position-fixed, and every sr-only or live element has a positioned ancestor in the page', async () => {
    const pending = { ...copy(overviewAfterAsync), status: 'RUNNING', latestWrite: writeAsync }
    await renderPage(makeApi({ overview: pending, replicas: replicasDiverged }))
    const page = screen.getByTestId('experiment-layout')
    expect(page.querySelectorAll('.fixed')).toHaveLength(0)

    // This page's own content (the kit's header and event log are Track A's, under <main class="relative">).
    for (const name of ['controls', 'visualisation', 'measurements']) {
      const section = screen.getByTestId(`section-${name}`)
      const hidden = [...section.querySelectorAll('.sr-only, [aria-live], [role="tooltip"]')]
      expect(hidden.length, name).toBeGreaterThan(0)
      for (const element of hidden) {
        const positioned = element.parentElement.closest('.relative')
        expect(positioned, element.outerHTML.slice(0, 80)).not.toBeNull()
        expect(section.contains(positioned)).toBe(true)
      }
    }
  })
})
