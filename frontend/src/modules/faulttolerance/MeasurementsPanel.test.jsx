import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import overviewBefore from '@/test/fixtures/faulttolerance/overview-before-start.json'
import runSyncRecovered from '@/test/fixtures/faulttolerance/run-sync-recovered.json'
import runSyncRestored from '@/test/fixtures/faulttolerance/run-sync-restored.json'
import runAsyncRestored from '@/test/fixtures/faulttolerance/run-async-restored.json'
import runPrimaryReturned from '@/test/fixtures/faulttolerance/synthetic/run-primary-returned.json'
import { MeasurementsPanel } from './MeasurementsPanel'

const { settings, glossary } = overviewBefore

afterEach(cleanup)

const cards = (testId) => within(screen.getByTestId(testId)).getAllByTestId('metric-card')

describe('MeasurementsPanel', () => {
  it('shows the four measurements, then outage and time to first update, with the served labels', () => {
    render(<MeasurementsPanel run={runSyncRecovered} glossary={glossary} settings={settings} />)
    expect(cards('four-measurements').map((c) => c.textContent)).toEqual([
      expect.stringContaining('Detection time'), expect.stringContaining('Failover time'),
      expect.stringContaining('Recovery time'), expect.stringContaining('Data loss'),
    ])
    expect(cards('four-measurements')[0].textContent).toContain('2238.1 ms')
    expect(cards('four-measurements')[2].textContent).toContain('30.1 ms')
    expect(cards('beside-measurements').map((c) => c.textContent)).toEqual([
      expect.stringContaining('Outage'), expect.stringContaining('Time to first update'),
    ])
  })

  it('shows a measured 0 data loss as 0, with no Simulated badge', () => {
    render(<MeasurementsPanel run={runSyncRestored} glossary={glossary} settings={settings} />)
    expect(cards('four-measurements')[3].textContent).toContain('0 of 11')
    expect(screen.queryByTestId('simulated-badge')).toBeNull()
  })

  it('puts the Simulated badge with its reason on a simulated data loss only', () => {
    render(<MeasurementsPanel run={runAsyncRestored} glossary={glossary} settings={settings} />)
    const badges = screen.getAllByTestId('simulated-badge')
    expect(badges).toHaveLength(1)
    expect(within(cards('four-measurements')[3]).getByTestId('simulated-badge').textContent).toContain(runAsyncRestored.dataLoss.simulatedReason)
  })

  it('shows every null as "Not measured" with its reason, never 0 (synthetic run-primary-returned)', () => {
    render(<MeasurementsPanel run={runPrimaryReturned} glossary={glossary} settings={settings} />)
    const all = [...cards('four-measurements'), ...cards('beside-measurements')]
    for (const card of all) {
      expect(within(card).getByTestId('not-measured').textContent).toBe('Not measured (The old primary came back before anyone was chosen)')
      expect(card.textContent).not.toMatch(/(^|[^\d.])0([^\d.]|$)/)
    }
  })

  it('shows "Not measured" for recovery before the old primary is recovered', () => {
    render(<MeasurementsPanel run={runSyncRestored} glossary={glossary} settings={settings} />)
    expect(within(cards('four-measurements')[2]).getByTestId('not-measured').textContent).toContain('The old primary has not been recovered yet')
  })

  it('keeps the derived figures apart, labelled Derived, and compares them with the measured detection', () => {
    render(<MeasurementsPanel run={runSyncRestored} glossary={glossary} settings={settings} />)
    const derived = screen.getByTestId('derived-figures')
    expect(within(derived).getAllByText('Derived')).toHaveLength(2)
    expect(derived.textContent).toContain('1800 to 3200 ms')
    expect(derived.textContent).toContain('10300 ms')
    expect(screen.getByTestId('detection-comparison').textContent).toBe('The measured detection is inside the expected window.')
    expect(within(screen.getByTestId('four-measurements')).queryByText('Derived')).toBeNull()
    expect(within(screen.getByTestId('four-measurements')).getAllByText('Measured')).toHaveLength(4)
  })

  it('gives every term its served definition, reachable from a named button', () => {
    render(<MeasurementsPanel run={runSyncRestored} glossary={glossary} settings={settings} />)
    const button = screen.getByRole('button', { name: 'What "Detection time" means' })
    const tooltip = document.getElementById(button.getAttribute('aria-describedby'))
    expect(tooltip.getAttribute('role')).toBe('tooltip')
    expect(tooltip.textContent).toBe(glossary.find((g) => g.id === 'detection').definition)
  })

  it('says there is no failover yet without a run', () => {
    render(<MeasurementsPanel run={null} glossary={glossary} settings={settings} />)
    expect(screen.getByText(/^No failover since the reset\./)).toBeTruthy()
    expect(screen.getAllByTestId('not-measured')).toHaveLength(6)
  })
})
