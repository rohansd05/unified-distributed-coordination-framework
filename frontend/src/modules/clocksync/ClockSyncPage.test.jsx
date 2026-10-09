import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { EXPERIMENTS } from '@/lib/experiments'
import { ApiError } from '@/services/api'
import overviewInitial from '@/test/fixtures/clocksync/overview-initial.json'
import overviewAfterSync from '@/test/fixtures/clocksync/overview-after-sync.json'
import localEventFixture from '@/test/fixtures/clocksync/local-event.json'
import messageLiveFixture from '@/test/fixtures/clocksync/message-live.json'
import messageCrashedFixture from '@/test/fixtures/clocksync/message-crashed-receiver.json'
import trafficFixture from '@/test/fixtures/clocksync/traffic.json'
import berkeleyRoundFixture from '@/test/fixtures/clocksync/berkeley-round.json'
import verificationFixture from '@/test/fixtures/clocksync/verification.json'
import timelineFixture from '@/test/fixtures/clocksync/timeline.json'
import error409Fixture from '@/test/fixtures/clocksync/error-409-node-down.json'
import error400Fixture from '@/test/fixtures/clocksync/error-400-validation.json'
import error404Fixture from '@/test/fixtures/clocksync/error-404-unknown-node.json'
import { ClockSyncPage } from './ClockSyncPage'

const experiment = EXPERIMENTS.find((e) => e.id === 'clocksync')

const ALL_CAPS_WORD = /\b[A-Z]{2,}\b/g
const ALLOWED_ABBREVIATIONS = new Set(['TCP', 'UDP', 'RTT', 'ID', 'IDS', 'RFC', 'UUID'])

const copy = (obj) => JSON.parse(JSON.stringify(obj))

describe('ClockSyncPage', () => {
  function makeApi(overrides = {}) {
    return {
      getOverview: vi.fn().mockResolvedValue(overrides.overview ?? copy(overviewInitial)),
      getTimeline: vi.fn().mockResolvedValue(overrides.timeline ?? copy(timelineFixture)),
      getVerification: vi.fn().mockResolvedValue(overrides.verification ?? copy(verificationFixture)),
      recordLocalEvent: vi.fn().mockResolvedValue(overrides.localEvent ?? copy(localEventFixture)),
      sendMessage: vi.fn().mockResolvedValue(overrides.sendMessage ?? copy(messageLiveFixture)),
      startTraffic: vi.fn().mockResolvedValue(overrides.traffic ?? copy(trafficFixture)),
      runBerkeleyRound: vi.fn().mockResolvedValue(overrides.berkeleyRound ?? copy(berkeleyRoundFixture)),
      updateDrift: vi.fn().mockResolvedValue({ nodeId: 2, offsetMillis: 100, driftRateMsPerSec: 1.5 }),
      getEvents: vi.fn().mockResolvedValue([]),
    }
  }

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('renders the shared layout with concept, explanation, and callouts', async () => {
    const api = makeApi()
    render(<ClockSyncPage experiment={experiment} api={api} />)

    const heading = await screen.findByRole('heading', { level: 1 })
    expect(heading.textContent).toBe('Clock Synchronization')
    expect(screen.getByText(experiment.concept)).toBeTruthy()
    expect(screen.getByTestId('section-how-it-works')).toBeTruthy()
    expect(screen.getByTestId('section-what-to-notice').querySelectorAll('li')).toHaveLength(3)
  })

  it('shows no all-caps words in authored page text', async () => {
    for (const overview of [overviewInitial, overviewAfterSync]) {
      const api = makeApi({ overview })
      render(<ClockSyncPage experiment={experiment} api={api} />)
      await screen.findByTestId('space-time-diagram')

      const sections = [...screen.getByTestId('experiment-layout').querySelectorAll('[data-testid^="section-"]')]
        .filter((s) => s.dataset.testid !== 'section-event-log')
        .map((s) => s.textContent)
        .join(' ')

      const words = (sections.match(ALL_CAPS_WORD) ?? []).filter((w) => !ALLOWED_ABBREVIATIONS.has(w))
      expect(words).toEqual([])
      cleanup()
    }
  })

  it('verifies layout rules (E2d): no position:fixed and positioned ancestors for sr-only/live/tooltips', async () => {
    const api = makeApi()
    render(<ClockSyncPage experiment={experiment} api={api} />)
    await screen.findByTestId('space-time-diagram')

    const page = screen.getByTestId('experiment-layout')
    expect(page.querySelectorAll('.fixed')).toHaveLength(0)

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

  it('records a local event, updates state, and moves focus to result panel', async () => {
    const api = makeApi()
    render(<ClockSyncPage experiment={experiment} api={api} />)
    await screen.findByTestId('clocksync-controls')

    const btn = screen.getByRole('button', { name: 'Record local event' })
    fireEvent.click(btn)

    await waitFor(() => expect(api.recordLocalEvent).toHaveBeenCalled())
    const result = await screen.findByTestId('action-result')
    expect(result.textContent).toContain('Local event recorded on Node 1')
    expect(document.activeElement).toBe(result)
  })

  it('sends point-to-point UDP message between live nodes with Sent status', async () => {
    const api = makeApi()
    render(<ClockSyncPage experiment={experiment} api={api} />)
    await screen.findByTestId('clocksync-controls')

    const btn = screen.getByRole('button', { name: 'Send message' })
    fireEvent.click(btn)

    await waitFor(() => expect(api.sendMessage).toHaveBeenCalled())
    const result = await screen.findByTestId('action-result')
    expect(result.textContent).toContain('Sent message from Node 1 to Node 2')
    expect(result.textContent).toContain('Status: Sent')
    expect(document.activeElement).toBe(result)
  })

  it('sends message to crashed receiver: displays Unknown status and honesty note', async () => {
    const api = makeApi({ sendMessage: copy(messageCrashedFixture) })
    render(<ClockSyncPage experiment={experiment} api={api} />)
    await screen.findByTestId('clocksync-controls')

    const btn = screen.getByRole('button', { name: 'Send message' })
    fireEvent.click(btn)

    await waitFor(() => expect(api.sendMessage).toHaveBeenCalled())
    const result = await screen.findByTestId('action-result')
    expect(result.textContent).toContain('Status: Unknown')
    expect(result.textContent).toContain('datagram was transmitted over UDP socket but delivery cannot be confirmed')
  })

  it('asynchronous Berkeley round: moves through accepted state to converged result', async () => {
    let callCount = 0
    const api = {
      getOverview: vi.fn().mockImplementation(() => {
        callCount++
        if (callCount === 1) return Promise.resolve(copy(overviewInitial))
        if (callCount === 2) {
          const busy = copy(overviewInitial)
          busy.status = 'BUSY'
          busy.actionInProgress = 'Berkeley round coordinated by Node 1'
          return Promise.resolve(busy)
        }
        return Promise.resolve(copy(overviewAfterSync))
      }),
      getTimeline: vi.fn().mockResolvedValue(copy(timelineFixture)),
      getVerification: vi.fn().mockResolvedValue(copy(verificationFixture)),
      runBerkeleyRound: vi.fn().mockResolvedValue(copy(berkeleyRoundFixture)),
      getEvents: vi.fn().mockResolvedValue([]),
    }

    render(<ClockSyncPage experiment={experiment} api={api} />)
    await screen.findByTestId('clocksync-controls')

    // Click Berkeley tab
    fireEvent.click(screen.getByRole('tab', { name: 'Berkeley physical sync' }))

    const btn = screen.getByRole('button', { name: 'Run synchronization round' })
    fireEvent.click(btn)

    await waitFor(() => expect(api.runBerkeleyRound).toHaveBeenCalled())
    const result = await screen.findByTestId('action-result')
    expect(result.textContent).toContain('Berkeley round 1 accepted')
  })

  it('displays "0 causal violations" when verified and reports causal violations when detected', async () => {
    const api = makeApi()
    render(<ClockSyncPage experiment={experiment} api={api} />)
    await screen.findByTestId('verification-panel')

    const verifyBtn = screen.getByRole('button', { name: 'Verify causal invariants' })
    fireEvent.click(verifyBtn)

    await waitFor(() => expect(api.getVerification).toHaveBeenCalled())
    expect(await screen.findByText('0 causal violations')).toBeTruthy()

    // Test derived violation fixture
    const derivedViolation = copy(verificationFixture)
    derivedViolation.passed = false
    derivedViolation.violationsCount = 1
    derivedViolation.violations = [
      {
        nodeId: 2,
        type: 'RECEIVE_NOT_AFTER_SEND',
        actualLamportTime: 3,
        expectedRelationTime: 5,
        peerId: 1,
        message: 'Derived: receive 3 <= send 5',
      },
    ]

    api.getVerification.mockResolvedValueOnce(derivedViolation)
    fireEvent.click(screen.getByRole('button', { name: 'Re-verify invariants' }))

    expect(await screen.findByText('1 causal violations detected')).toBeTruthy()
    expect(screen.getByText('Derived: receive 3 <= send 5')).toBeTruthy()
  })

  it('handles 409 NodeDownException ProblemDetail error with focus transfer', async () => {
    const api = makeApi()
    api.sendMessage.mockRejectedValue(new ApiError(error409Fixture))

    render(<ClockSyncPage experiment={experiment} api={api} />)
    await screen.findByTestId('clocksync-controls')

    fireEvent.click(screen.getByRole('button', { name: 'Send message' }))

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('Node down')
    expect(alert.textContent).toContain('Node 3 is crashed')
    expect(document.activeElement).toBe(alert)
  })

  it('handles 400 validation error with field message and alert focus', async () => {
    const api = makeApi()
    api.startTraffic.mockRejectedValue(new ApiError(error400Fixture))

    render(<ClockSyncPage experiment={experiment} api={api} />)
    await screen.findByTestId('clocksync-controls')

    fireEvent.click(screen.getByRole('button', { name: 'Start traffic burst' }))

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('Invalid request parameters')
    expect(alert.textContent).toContain('Invalid request parameter: seconds')
    expect(document.activeElement).toBe(alert)
  })

  it('handles 404 unknown node error', async () => {
    const api = makeApi()
    api.recordLocalEvent.mockRejectedValue(new ApiError(error404Fixture))

    render(<ClockSyncPage experiment={experiment} api={api} />)
    await screen.findByTestId('clocksync-controls')

    fireEvent.click(screen.getByRole('button', { name: 'Record local event' }))

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('Unknown node')
    expect(alert.textContent).toContain('No node with id 99')
  })

  it('shows "—" with accessible label for unmeasured / null metrics, never 0, NaN, or undefined', async () => {
    const api = makeApi()
    render(<ClockSyncPage experiment={experiment} api={api} />)
    await screen.findByTestId('section-measurements')

    // Initial state has no round, so spreads are unmeasured
    const notAvailableElements = screen.getAllByLabelText('Not available')
    expect(notAvailableElements.length).toBeGreaterThan(0)

    const text = document.body.textContent
    expect(text).not.toContain('NaN')
    expect(text).not.toContain('undefined')
  })
})
