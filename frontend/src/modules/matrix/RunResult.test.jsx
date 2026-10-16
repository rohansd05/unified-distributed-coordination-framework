import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import overviewAfter from '@/test/fixtures/matrix/overview-after-start.json'
import complete316 from '@/test/fixtures/matrix/run-complete-316.json'
import partial from '@/test/fixtures/matrix/run-partial.json'
import failed from '@/test/fixtures/matrix/run-failed.json'
import requestedRoot from '@/test/fixtures/matrix/run-requested-root.json'
import refused from '@/test/fixtures/matrix/run-refused.json'
import mismatch from '@/test/fixtures/matrix/synthetic/run-checksum-mismatch.json'
import evicted from '@/test/fixtures/matrix/synthetic/run-evicted-block.json'
import timeouts from '@/test/fixtures/matrix/synthetic/run-timeouts.json'
import late from '@/test/fixtures/matrix/synthetic/run-not-attempted-deadline.json'
import { formatSpeedup } from './matrixModel'
import { RunResult } from './RunResult'

afterEach(cleanup)

const copy = (value) => JSON.parse(JSON.stringify(value))
const show = (run) => render(<RunResult run={run} overview={overviewAfter} />)
const figure = (id) => screen.getByTestId(`figure-${id}`)

describe('RunResult: a complete run', () => {
  it('shows the served speedup with the run\'s speedup basis beside it', () => {
    show(complete316)
    expect(screen.getByTestId('speedup').textContent).toBe(formatSpeedup(complete316.timing.derived.speedup))
    expect(screen.getByTestId('speedup-block').textContent).toContain(complete316.speedupBasis)
    expect(screen.queryByTestId('speedup-withheld')).toBeNull()
  })

  it('labels the compute-bound speedup as such, never as the measured one', () => {
    show(complete316)
    const row = screen.getByTestId('compute-bound').closest('div')
    expect(row.textContent).toContain('Compute-bound speedup (not the measured speedup)')
    expect(screen.getByTestId('compute-bound').textContent).toBe(formatSpeedup(complete316.timing.derived.computeBoundSpeedup))
  })

  it('labels every figure with the served glossary label and kind', () => {
    show(complete316)
    const figures = screen.getByTestId('figures')
    for (const entry of overviewAfter.glossary.filter((e) => ['sequentialNanos', 'scatterNanos', 'otherNanos'].includes(e.id))) {
      expect(figures.textContent).toContain(entry.label)
      expect(figures.textContent).toContain(entry.definition)
    }
    expect(figures.textContent).toContain('Measured on the root')
    expect(figures.textContent).toContain('Derived')
    expect(figures.textContent).toContain(overviewAfter.phaseTimeLabel)
    expect(figures.textContent).toContain(overviewAfter.payloadGenerationNote)
    expect(screen.getByText(overviewAfter.firstRunNote)).toBeTruthy()
  })

  it('shows the processors and the ceiling as a reported number and a bound, not measurements', () => {
    show(complete316)
    const block = screen.getByTestId('speedup-block')
    expect(block.textContent).toContain(`Processors${overviewAfter.processors}`)
    expect(block.textContent).toContain('A bound, not a measurement')
  })

  it('shows the verification and both checksums', () => {
    show(complete316)
    const verification = screen.getByTestId('verification')
    expect(within(verification).getByText('Passed')).toBeTruthy()
    expect(within(verification).getAllByTitle(complete316.verification.sequentialChecksum)).toHaveLength(2)
  })

  it('has one row per rank in the per-node table with the round-trip and fetch bases under it', () => {
    show(requestedRoot)
    const rows = screen.getAllByTestId('node-row')
    expect(rows).toHaveLength(requestedRoot.computes.length)
    expect(rows[requestedRoot.rootRank].textContent).toContain('(root)')
    expect(rows[requestedRoot.rootRank].textContent).toContain('Local')
    expect(screen.getByText(requestedRoot.computeRoundTripBasis)).toBeTruthy()
    expect(screen.getByText(requestedRoot.gatherLatencyBasis)).toBeTruthy()
    expect(screen.queryAllByTestId('hole-row')).toHaveLength(0)
  })

  it('stacks the phases and prints other time beside the bar, negative as it is (synthetic negative)', () => {
    const negative = copy(complete316)
    negative.timing.derived.otherNanos = -1200   // synthetic: other time can be negative
    show(negative)
    expect(screen.getByTestId('phase-stack')).toBeTruthy()
    expect(screen.getByTestId('stack-other').textContent).toContain('-1.2')
    expect(figure('otherNanos').textContent).toContain('-1.2')
  })
})

describe('RunResult: runs that did not complete', () => {
  it('a partial run: the withheld reason instead of a speedup, the hole, and verification unknown', () => {
    show(partial)
    expect(screen.getByTestId('speedup-withheld').textContent).toBe(partial.timing.derived.speedupWithheldReason)
    expect(screen.queryByTestId('speedup')).toBeNull()
    expect(screen.getAllByTestId('hole-row')).toHaveLength(1)
    expect(within(screen.getByTestId('verification')).getByText(/Unknown: a block is missing/)).toBeTruthy()
  })

  it('a failed run: an unknown gather time is "Not measured", never 0, and the stacked bar is hidden', () => {
    show(failed)
    expect(figure('gatherNanos').textContent).toBe('Not measured')
    expect(figure('communicationNanos').textContent).toBe('Not measured')
    expect(screen.queryByTestId('phase-stack')).toBeNull()
  })

  it('a refused run: the reason and message, and no figures', () => {
    show(refused)
    expect(screen.getByTestId('refusal').textContent).toContain(refused.refusal.message)
    expect(screen.getByTestId('refusal').textContent).toContain('Size below participants')
    expect(screen.queryByTestId('figures')).toBeNull()
    expect(screen.queryByTestId('verification')).toBeNull()
  })
})

describe('RunResult: synthetic states (fixtures/matrix/synthetic)', () => {
  it('synthetic checksum mismatch: verification failed, the mismatch counts, and the served withheld reason', () => {
    show(mismatch)
    const verification = screen.getByTestId('verification')
    expect(within(verification).getByText('Failed: the result does not match')).toBeTruthy()
    expect(verification.textContent).toContain(`Mismatched elements${mismatch.verification.mismatchedElements}`)
    expect(screen.getByTestId('speedup-withheld').textContent).toBe('No speedup: the parallel result did not match the sequential product.')
    expect(screen.getAllByTestId('node-row')[2].textContent).toContain('checksum differs')
  })

  it('synthetic evicted input and block: both holes with the node\'s reason and the fetch failure', () => {
    show(evicted)
    const holes = screen.getAllByTestId('hole-row').map((row) => row.textContent)
    expect(holes).toHaveLength(2)
    expect(holes[0]).toContain('Input missing')
    expect(holes[1]).toContain('keeps no block of C')
    expect(holes[1]).toContain('Time to failure of the fetch')
  })

  it('synthetic timeouts: the node\'s own timeout and the root\'s wait bound read differently', () => {
    show(timeouts)
    const rows = screen.getAllByTestId('node-row')
    expect(rows[1].textContent).toContain('Failed (Method threw, the node said compute timed out)')
    expect(rows[2].textContent).toContain('Failed (Timed out)')
    expect(rows[2].textContent).not.toContain('the node said')
  })

  it('synthetic steps after the deadline: not attempted, with the served reason', () => {
    show(late)
    const rows = screen.getAllByTestId('node-row')
    expect(rows[4].textContent).toContain('Not attempted')
    const holes = screen.getAllByTestId('hole-row').map((row) => row.textContent)
    expect(holes.some((text) => text.includes(late.gather[3].notAttemptedReason))).toBe(true)
    expect(holes.some((text) => text.includes(late.computes[4].notAttemptedReason))).toBe(true)
  })
})
