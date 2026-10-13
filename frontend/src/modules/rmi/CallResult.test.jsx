import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import overview from '@/test/fixtures/rmi/overview-after-start.json'
import callPing from '@/test/fixtures/rmi/call-ping.json'
import callNodeInfo from '@/test/fixtures/rmi/call-node-info.json'
import callHash from '@/test/fixtures/rmi/call-compute-hash.json'
import callSelf from '@/test/fixtures/rmi/call-self.json'
import callTimedOut from '@/test/fixtures/rmi/call-timed-out.json'
import callCrashedTarget from '@/test/fixtures/rmi/call-crashed-target.json'
import comparisonHash from '@/test/fixtures/rmi/comparison-compute-hash.json'
import comparisonPing from '@/test/fixtures/rmi/comparison-ping.json'
import { CallResult, ComparisonResult } from './CallResult'

afterEach(cleanup)

/** The dd of a row, found by its dt label. */
function cell(label) {
  const term = screen.getAllByText(label, { selector: 'dt' })[0]
  return term.nextElementSibling
}

describe('CallResult', () => {
  it('shows a successful remote call: result, stub class, times, sizes with their basis, and the Lamport values', () => {
    render(<CallResult call={callPing} overview={overview} />)
    expect(screen.getByText('Succeeded:')).toBeTruthy()
    expect(cell('Result').textContent).toBe('reply from node 2')
    expect(cell('Stub class').textContent).toBe('jdk.proxy2.$Proxy111')
    expect(cell('Round trip').textContent).toBe('7.237 ms')
    expect(cell('Round trip').querySelector('[title]').getAttribute('title')).toBe('7237200 ns')
    expect(cell('Registry lookup').textContent).toContain('1.735 ms')
    expect(cell('Registry lookup').textContent).toContain(overview.lookupBasis)
    expect(cell('Arguments marshalled').textContent).toContain('96 bytes')
    expect(cell('Arguments marshalled').textContent).toContain(overview.payloadSizeBasis)
    expect(cell('Reply marshalled').textContent).toBe('86 bytes')
    expect(cell('Lamport time').textContent).toBe('at send 3reply 5after reply 6')
  })

  it('shows a null as n/a with its reason, never as 0 or hidden: a success has no time to failure', () => {
    render(<CallResult call={callPing} overview={overview} />)
    const value = cell('Time to failure')
    expect(within(value).getByTestId('not-available').getAttribute('title')).toBe('The call did not fail')
    expect(value.textContent).toContain('n/a')
    expect(value.textContent).not.toMatch(/\b0\b/)
  })

  it('shows the real TIMED_OUT failure at the CALL stage with its exception, plain-text message and time to failure', () => {
    render(<CallResult call={callTimedOut} overview={overview} />)
    expect(screen.getByText('Remote failure:')).toBeTruthy()
    expect(cell('Failed at').textContent).toBe('the method call')
    expect(cell('Failure kind').textContent).toBe('Timed outThe node did not answer within the call timeout.')
    expect(cell('Exception').textContent).toContain('java.rmi.UnmarshalException')
    expect(cell('Exception').textContent).toContain('caused by java.net.SocketTimeoutException')
    expect(screen.getByTestId('failure-message').textContent).toBe(callTimedOut.failureMessage)
    expect(screen.getByTestId('failure-message').innerHTML).not.toContain('<')
    expect(cell('Time to failure').textContent).toBe('51.310 ms')
    expect(cell('Registry lookup').textContent).toContain('23.815 ms')
    expect(within(cell('Round trip')).getByTestId('not-available').getAttribute('title')).toBe('No round trip: the call did not complete')
    expect(within(cell('Reply marshalled')).getByText('n/a')).toBeTruthy()
  })

  it('shows a LOOKUP failure (crashed target): no lookup time, no stub, nothing sent, all as n/a', () => {
    render(<CallResult call={callCrashedTarget} overview={overview} />)
    expect(cell('Failed at').textContent).toBe('the registry lookup')
    expect(cell('Failure kind').textContent).toContain('Connection refused')
    expect(cell('Time to failure').textContent).toBe('10.577 ms')
    for (const label of ['Registry lookup', 'Round trip', 'Arguments marshalled', 'Reply marshalled', 'Stub class']) {
      expect(within(cell(label)).getByTestId('not-available')).toBeTruthy()
    }
    expect(within(cell('Lamport time')).getAllByText('n/a')).toHaveLength(3)
  })

  it('shows each method\'s reply fields: nodeInfo, computeHash, and a node calling itself', () => {
    const { unmount } = render(<CallResult call={callNodeInfo} overview={overview} />)
    expect(cell('Capacity profile').textContent).toBe('SLOW')
    expect(cell('Uptime').textContent).toBe('408 ms')
    unmount()
    const second = render(<CallResult call={callHash} overview={overview} />)
    expect(cell('Digest').textContent).toBe(callHash.reply.digestHex)
    expect(cell('Hashing time on the node').textContent).toBe('120.973 ms')
    second.unmount()
    render(<CallResult call={callSelf} overview={overview} />)
    expect(screen.getByText('ping from node 2 to node 2')).toBeTruthy()
  })
})

describe('ComparisonResult', () => {
  it('shows a negative overhead and a ratio below 1 exactly as measured, with the backend note', () => {
    render(<ComparisonResult comparison={comparisonHash} overview={overview} />)
    expect(screen.getByTestId('comparison-overhead').textContent).toBe('-4.563 ms')
    expect(screen.getByTestId('comparison-overhead').textContent.charCodeAt(0)).toBe(45)
    expect(screen.getByTestId('comparison-ratio').textContent).toBe('0.949')
    expect(screen.getByTestId('comparison-ratio').querySelector('[title]').getAttribute('title')).toBe(String(comparisonHash.ratio))
    expect(cell('Direct call').textContent).toBe('90.138 ms')
    expect(cell('Through RMI').textContent).toBe('85.574 ms')
    expect(cell('Digests match').textContent).toBe('Yes, identical')
    expect(screen.getByTestId('comparison-note').textContent).toBe(comparisonHash.note)
  })

  it('shows digestsMatch null as n/a, and the direct half without sizes (nothing marshalled)', () => {
    render(<ComparisonResult comparison={comparisonPing} overview={overview} />)
    expect(within(cell('Digests match')).getByTestId('not-available').getAttribute('title'))
      .toBe('Compared for computeHash only, when both sides succeed')
    const local = screen.getAllByTestId('call-result')[0]
    expect(local.textContent).toContain('Local (direct call)')
    const sizeRow = within(local).getAllByText('Arguments marshalled', { selector: 'dt' })[0].nextElementSibling
    expect(within(sizeRow).getByTestId('not-available').getAttribute('title')).toBe('Nothing is marshalled on a direct call')
  })
})
