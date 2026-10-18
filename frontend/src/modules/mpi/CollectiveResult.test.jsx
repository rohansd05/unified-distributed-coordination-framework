import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import broadcast from '@/test/fixtures/mpi/collective-broadcast.json'
import scatter from '@/test/fixtures/mpi/collective-scatter.json'
import gather from '@/test/fixtures/mpi/collective-gather.json'
import partial from '@/test/fixtures/mpi/collective-partial.json'
import refused from '@/test/fixtures/mpi/collective-refused.json'
import skipped from '@/test/fixtures/mpi/collective-node-crashed-skipped.json'
import requestedRoot from '@/test/fixtures/mpi/collective-requested-root.json'
import { CollectiveResult, TOTAL_TIME_BASIS } from './CollectiveResult'

afterEach(cleanup)

const copy = (value) => JSON.parse(JSON.stringify(value))
const rowFor = (rank) => screen.getAllByTestId('transfer-row')[rank]
const notAvailable = (element) => within(element).queryByTestId('not-available')

describe('CollectiveResult: complete collectives', () => {
  it('shows a complete broadcast with its outcome, root, total time and basis texts', () => {
    render(<CollectiveResult collective={broadcast} />)
    expect(screen.getByTestId('outcome-badge').textContent).toBe('Complete')
    expect(screen.getByText('Broadcast of 1000 values from node 1')).toBeTruthy()
    expect(screen.getByTestId('collective-result').textContent).toContain('chosen as the node you chose')
    expect(screen.getByTestId('total-time').textContent).toBe('10.036 ms')
    expect(screen.getByText(TOTAL_TIME_BASIS)).toBeTruthy()
    expect(screen.getByTestId('latency-basis').textContent).toContain(broadcast.latencyBasis)
    expect(screen.getByTestId('size-basis').textContent).toContain(broadcast.payloadSizeBasis)
    expect(screen.getAllByTestId('transfer-row')).toHaveLength(5)
    expect(within(rowFor(1)).getByTestId('verified').textContent).toBe('verified')
    expect(within(rowFor(1)).getByText('8133 bytes')).toBeTruthy()
  })

  it('shows the root\'s local part with no number at all, each as n/a with a reason', () => {
    render(<CollectiveResult collective={broadcast} />)
    const local = rowFor(0)
    expect(local.getAttribute('data-status')).toBe('LOCAL')
    expect(within(local).getAllByTestId('not-available').length).toBe(8)   // latency, failure time, 2 sizes, reported, 3 Lamport
    expect(within(local).getByTestId('latency-cell').textContent).toContain('never crosses the network')
    expect(within(local).getByTestId('verified').textContent).toBe('not checked')
    expect(local.textContent).not.toMatch(/\b0 bytes\b|\b0 ns\b/)
  })

  it('shows scatter blocks with their offsets and element counts, and the whole-vector check', () => {
    render(<CollectiveResult collective={scatter} />)
    expect(screen.getAllByTestId('transfer-row').map((row) => row.children[4].textContent))
      .toEqual(['0, 201', '201, 201', '402, 201', '603, 200', '803, 200'])
    expect(within(screen.getByTestId('verification')).getByText('yes')).toBeTruthy()
  })

  it('shows a gather pointing to the root, with five gathered checksums and no values', () => {
    render(<CollectiveResult collective={gather} />)
    expect(within(rowFor(1)).getByText('rank to root')).toBeTruthy()
    const blocks = screen.getAllByTestId('gathered-block')
    expect(blocks).toHaveLength(5)
    expect(blocks.every((b) => b.getAttribute('data-hole') === 'false')).toBe(true)
    expect(blocks[1].textContent).toContain('fe6c2626c99a...')
    expect(blocks[1].textContent).toContain(gather.gathered[1].checksum)   // full value for screen readers
  })

  it('shows a requested root and its rank, and a skipped node without a rank', () => {
    render(<CollectiveResult collective={requestedRoot} />)
    expect(screen.getByTestId('collective-result').textContent).toContain('node 3, chosen as the node you chose; rank 2')
    cleanup()
    render(<CollectiveResult collective={skipped} />)
    const skippedRow = screen.getAllByTestId('rank-row').find((row) => row.getAttribute('data-role') === 'skipped')
    expect(skippedRow.textContent).toContain('Node 3')
    expect(skippedRow.textContent).toContain('skipped: crashed at the start')
    expect(notAvailable(skippedRow)).toBeTruthy()
  })

  it('explains a whole-vector check that does not apply (broadcast) as n/a, never as passed', () => {
    render(<CollectiveResult collective={broadcast} />)
    const verification = screen.getByTestId('verification')
    expect(verification.textContent).toContain('4 checked, 4 matched, 0 mismatched, 0 unknown')
    expect(notAvailable(verification)).toBeTruthy()
  })
})

describe('CollectiveResult: failures and refusals', () => {
  it('shows a partial broadcast\'s failed transfer: time to failure as text, no latency, the failure detail', () => {
    render(<CollectiveResult collective={partial} />)
    expect(screen.getByTestId('outcome-badge').textContent).toBe('Partial')
    const failed = rowFor(4)
    expect(failed.getAttribute('data-status')).toBe('FAILED')
    expect(within(failed).getByTestId('time-to-failure-cell').textContent).toBe('771.8 µs')
    expect(notAvailable(within(failed).getByTestId('latency-cell'))).toBeTruthy()
    expect(within(failed).getByTestId('latency-cell').textContent).not.toContain('771.8')
    expect(failed.textContent).toContain('Nothing was sent: the registry lookup failed')
    const detail = screen.getByTestId('failure-detail')
    expect(detail.textContent).toContain('Failed at the registry lookup with connection refused')
    expect(detail.textContent).toContain(partial.transfers[4].failureDescription)
    expect(detail.textContent).toContain('java.rmi.ConnectException caused by java.net.ConnectException')
    expect(detail.textContent).toContain(partial.transfers[4].failureMessage)
    expect(screen.getByTestId('verification').textContent).toContain('1 unknown')
  })

  it('shows a delivered transfer\'s latency and no time to failure', () => {
    render(<CollectiveResult collective={partial} />)
    expect(within(rowFor(1)).getByTestId('latency-cell').textContent).toBe('29.169 ms')
    expect(notAvailable(within(rowFor(1)).getByTestId('time-to-failure-cell'))).toBeTruthy()
  })

  it('shows a refused collective with its reason and message, and n/a for root rank, checksum, time and verification', () => {
    render(<CollectiveResult collective={refused} />)
    expect(screen.getByTestId('outcome-badge').textContent).toBe('Refused')
    expect(screen.getByTestId('refusal').textContent).toBe(`Count below participants. ${refused.refusal.message}`)
    expect(screen.queryByTestId('transfer-table')).toBeNull()
    expect(screen.queryByTestId('latency-basis')).toBeNull()
    expect(screen.queryByTestId('size-basis')).toBeNull()
    expect(screen.getByTestId('total-time').textContent).toContain('n/a')
    expect(screen.getByTestId('total-time').textContent).toContain('Refused: nothing was sent')
    expect(screen.queryByText(TOTAL_TIME_BASIS)).toBeNull()
    expect(screen.getByTestId('verification').textContent).toContain('Nothing was sent, so nothing was checked')
    expect(screen.getByTestId('collective-result').textContent).toContain('Refused before ranks were used')
    expect(screen.getByTestId('collective-result').textContent).toContain('Refused: no input was generated')
  })

  it('synthetic: a not-attempted transfer shows its reason and no figures (no capture has one)', () => {
    const c = copy(partial)
    Object.assign(c.transfers[4], {
      status: 'NOT_ATTEMPTED',   // changed: was FAILED
      timeToFailureNanos: null,   // changed: only FAILED has one
      failureStage: null, failureKind: null, failureDescription: null,   // changed: failure fields are FAILED only
      failureClass: null, failureMessage: null, failureCauseClass: null,   // changed
      notAttemptedReason: 'the collective deadline of 10000 ms had passed',   // changed: was null
    })
    render(<CollectiveResult collective={c} />)
    const row = rowFor(4)
    expect(row.getAttribute('data-status')).toBe('NOT_ATTEMPTED')
    expect(within(row).getByTestId('latency-cell').textContent).toContain('Not attempted: the deadline had passed')
    expect(notAvailable(within(row).getByTestId('time-to-failure-cell'))).toBeTruthy()
    expect(screen.getByTestId('not-attempted-detail').textContent).toBe('Not attempted: the collective deadline of 10000 ms had passed')
    expect(screen.queryByTestId('failure-detail')).toBeNull()
  })

  it('synthetic: a mismatched checksum fails verification (no capture has one)', () => {
    const c = copy(broadcast)
    c.transfers[1].reportedChecksum = '0000000000000000000000000000000000000000000000000000000000000000'   // changed
    c.transfers[1].verified = false   // changed: was true
    c.verification = { ...c.verification, passed: false, matched: 3, mismatched: 1 }   // changed: passed, matched, mismatched
    render(<CollectiveResult collective={c} />)
    expect(within(rowFor(1)).getByTestId('verified').textContent).toBe('mismatch')
    expect(screen.getByTestId('verification').textContent).toContain('Failed: a checksum did not match')
    expect(screen.getByTestId('verification').textContent).toContain('1 mismatched')
  })

  it('synthetic: a failed outcome and a gathered hole (no capture has either)', () => {
    const c = copy(gather)
    c.outcome = 'FAILED'   // changed: was COMPLETE (only the badge is under test)
    c.gathered[4].hole = true   // changed: was false
    c.gathered[4].checksum = null   // changed: a hole has no checksum
    render(<CollectiveResult collective={c} />)
    expect(screen.getByTestId('outcome-badge').getAttribute('data-outcome')).toBe('FAILED')
    expect(screen.getByTestId('outcome-badge').textContent).toBe('Failed')
    const hole = screen.getAllByTestId('gathered-block')[4]
    expect(hole.getAttribute('data-hole')).toBe('true')
    expect(hole.textContent).toContain('hole: nothing arrived')
  })
})
