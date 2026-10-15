import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import overviewBefore from '@/test/fixtures/faulttolerance/overview-before-start.json'
import overviewAfterStart from '@/test/fixtures/faulttolerance/overview-after-start.json'
import statusRunning from '@/test/fixtures/faulttolerance/status-stream-running.json'
import runSyncRestored from '@/test/fixtures/faulttolerance/run-sync-restored.json'
import runAsyncRestored from '@/test/fixtures/faulttolerance/run-async-restored.json'
import runInProgress from '@/test/fixtures/faulttolerance/synthetic/run-in-progress.json'
import { DataLossPanel } from './DataLossPanel'

const { notes } = overviewBefore

afterEach(cleanup)

const panel = () => screen.getByTestId('data-loss-panel')

describe('DataLossPanel', () => {
  it('shows a synchronous measured 0 as "0 lost", with no Simulated badge', () => {
    render(<DataLossPanel run={runSyncRestored} status={statusRunning} notes={notes} />)
    expect(screen.getByTestId('loss-headline').textContent).toMatch(/^0 lost/)
    expect(panel().textContent).toContain('Acknowledged (told it succeeded)11')
    expect(screen.queryByTestId('simulated-badge')).toBeNull()
  })

  it('shows the asynchronous in-flight loss with the Simulated badge, its reason and the reported delay', () => {
    render(<DataLossPanel run={runAsyncRestored} status={statusRunning} notes={notes} />)
    expect(screen.getByTestId('loss-headline').textContent).toMatch(/^4 lost/)
    expect(screen.getByTestId('simulated-badge').textContent).toContain(runAsyncRestored.dataLoss.simulatedReason)
    expect(panel().textContent).toContain('Lost, sent in asynchronous mode4')
    expect(panel().textContent).toContain('Lost, sent in synchronous mode0')
    expect(panel().textContent).toContain('setting-0401, setting-0402, setting-0403, setting-0404')
    expect(panel().textContent).toContain('Simulated delay before each push450 ms')
  })

  it('shows a run without a data loss check yet as not measured, never 0 (synthetic run-in-progress)', () => {
    render(<DataLossPanel run={runInProgress} status={statusRunning} notes={notes} />)
    expect(screen.getByTestId('not-measured').textContent).toBe('Not measured yet (Checked when the service is restored)')
    expect(screen.queryByTestId('loss-headline')).toBeNull()
  })

  it('shows the live check as not assessed when no primary serves', () => {
    render(<DataLossPanel run={null} status={overviewBefore.current} notes={notes} />)
    expect(screen.getByText('Data loss right now (live check, no failover yet)')).toBeTruthy()
    expect(screen.getByTestId('not-measured').textContent).toBe('Not assessed (No primary serves right now)')
  })

  it('says why the live check is not assessed', () => {
    render(<DataLossPanel run={null} status={overviewAfterStart.current} notes={notes} />)
    expect(panel().textContent).toContain('Not assessed: nothing has been acknowledged yet.')
  })

  it('explains asynchronous loss with the served notes, verbatim', () => {
    render(<DataLossPanel run={runAsyncRestored} status={statusRunning} notes={notes} />)
    for (const note of [notes[1], notes[2], notes[3]]) {
      expect(screen.getByText(note)).toBeTruthy()
    }
  })
})
