import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import overviewBefore from '@/test/fixtures/faulttolerance/overview-before-start.json'
import runSyncRecovered from '@/test/fixtures/faulttolerance/run-sync-recovered.json'
import runSyncRestored from '@/test/fixtures/faulttolerance/run-sync-restored.json'
import runInProgress from '@/test/fixtures/faulttolerance/synthetic/run-in-progress.json'
import runPrimaryReturned from '@/test/fixtures/faulttolerance/synthetic/run-primary-returned.json'
import { FailoverTimeline } from './FailoverTimeline'

const { settings, glossary } = overviewBefore
const AXIS_START_X = '216'
const GUTTER_X = '684'

afterEach(cleanup)

const marker = (kind) => within(screen.getByTestId(`milestone-${kind}`)).getByTestId('marker')

describe('FailoverTimeline', () => {
  it('shows a real empty state when there is no run', () => {
    render(<FailoverTimeline run={null} settings={settings} glossary={glossary} />)
    expect(screen.getByTestId('failover-timeline').textContent).toMatch(/^No failover yet\./)
    expect(document.querySelector('svg')).toBeNull()
  })

  it('draws every milestone of a full run on the axis, with the four measured intervals', () => {
    render(<FailoverTimeline run={runSyncRecovered} settings={settings} glossary={glossary} />)
    expect(screen.getByText(/^Run 1: Node 1 at epoch 2 to node 2 at epoch 3\. Service restored\.$/)).toBeTruthy()
    const recorded = document.querySelectorAll('g[data-testid^="milestone-"][data-state="recorded"]')
    expect(recorded).toHaveLength(8)
    expect(marker('CRASHED').getAttribute('cx')).toBe(AXIS_START_X)
    expect(Number(marker('DETECTED').getAttribute('cx'))).toBeGreaterThan(Number(AXIS_START_X))
    expect(['detection', 'failover', 'serviceRestored', 'recovery'].every((id) => screen.getByTestId(`interval-${id}`))).toBe(true)
    expect(screen.getAllByText('2238.1 ms').length).toBeGreaterThan(0)
    expect(screen.getByText('500 ms')).toBeTruthy()
  })

  it('draws the derived detection window as its own labelled band', () => {
    render(<FailoverTimeline run={runSyncRestored} settings={settings} glossary={glossary} />)
    const band = screen.getByTestId('detection-band')
    expect(band.textContent).toBe('Expected detection window (derived)')
    expect(band.querySelector('circle')).toBeNull()
  })

  it('hides the band when a detector setting is missing (C4)', () => {
    render(<FailoverTimeline run={runSyncRestored} settings={{ ...settings, heartbeatIntervalMillis: null }} glossary={glossary} />)
    expect(screen.queryByTestId('detection-band')).toBeNull()
  })

  it('shows pending milestones as hollow markers in the "Not yet" column, never at 0 (synthetic run-in-progress)', () => {
    render(<FailoverTimeline run={runInProgress} settings={settings} glossary={glossary} />)
    for (const kind of ['ELECTED', 'PROMOTED', 'SERVICE_RESTORED', 'OLD_PRIMARY_RECOVERED', 'DEMOTED', 'RESYNCHRONISED']) {
      expect(marker(kind).getAttribute('cx')).toBe(GUTTER_X)
      expect(marker(kind).getAttribute('class')).toContain('fill-none')
    }
    expect(screen.getByText(/The run is still open/)).toBeTruthy()
    const items = screen.getAllByTestId('milestone-item')
    expect(items[2].textContent).toContain('Pending: not recorded yet.')
    expect(items[5].textContent).toContain('Waiting for "Recover old primary".')
  })

  it('says why milestones were never reached (synthetic run-primary-returned)', () => {
    render(<FailoverTimeline run={runPrimaryReturned} settings={settings} glossary={glossary} />)
    expect(screen.getByTestId('milestone-DETECTED').getAttribute('data-state')).toBe('not-reached')
    expect(screen.getAllByText(/Not reached: the old primary came back before anyone was chosen\./).length).toBeGreaterThan(0)
  })

  it('names each milestone\'s event in the log, linked by kind', () => {
    render(<FailoverTimeline run={runSyncRecovered} settings={settings} glossary={glossary} />)
    const items = screen.getAllByTestId('milestone-item')
    expect(items[1].textContent).toContain('In the event log: Failure detected')
    expect(items[6].textContent).toContain('In the event log: Old primary demoted')
  })

  it('marks a crash started outside the page as seen late', () => {
    render(<FailoverTimeline run={{ ...runSyncRestored, crashSource: 'OBSERVED' }} settings={settings} glossary={glossary} />)
    expect(screen.getByText(/Seen as the node went down \(slightly late\)/)).toBeTruthy()
  })

  it('moves markers only with a transition that reduced motion turns off', () => {
    render(<FailoverTimeline run={runSyncRecovered} settings={settings} glossary={glossary} />)
    expect(marker('DETECTED').getAttribute('class')).toContain('motion-reduce:transition-none')
  })
})
