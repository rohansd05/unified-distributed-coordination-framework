import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import overviewBefore from '@/test/fixtures/rmi/overview-before-start.json'
import overviewAfter from '@/test/fixtures/rmi/overview-after-start.json'
import overviewCrashed from '@/test/fixtures/rmi/overview-node-crashed.json'
import callPing from '@/test/fixtures/rmi/call-ping.json'
import callSelf from '@/test/fixtures/rmi/call-self.json'
import callCrashedTarget from '@/test/fixtures/rmi/call-crashed-target.json'
import { NodeMap } from './NodeMap'

afterEach(cleanup)

describe('NodeMap', () => {
  it('draws every node from the overview with its state as colour, glyph and a word', () => {
    render(<NodeMap overview={overviewCrashed} call={null} />)
    expect(screen.getByTestId('map-node-3').getAttribute('data-status')).toBe('down')
    expect(screen.getByTestId('map-node-3').textContent).toContain('down')
    expect(screen.getByTestId('map-node-1').textContent).toContain('up')
    expect(screen.getByTestId('map-node-1').querySelectorAll('path')).toHaveLength(1)   // the glyph, not only colour
    expect(screen.getByText('Node 3: down, endpoint crashed.')).toBeTruthy()
  })

  it('marks nodes whose endpoint is not started', () => {
    render(<NodeMap overview={overviewBefore} call={null} />)
    expect(screen.getByTestId('map-node-2').getAttribute('data-status')).toBe('not-started')
    expect(screen.getByTestId('map-node-2').textContent).toContain('not started')
    expect(screen.getByTestId('map-sentence').textContent).toBe('No call yet: start with a ping.')
    expect(screen.queryByTestId('call-arrow')).toBeNull()
  })

  it('draws a solid arrow for a successful last call', () => {
    render(<NodeMap overview={overviewAfter} call={callPing} />)
    expect(screen.getByTestId('call-arrow').getAttribute('data-outcome')).toBe('success')
    expect(screen.queryByTestId('arrow-failure-kind')).toBeNull()
    expect(screen.getByRole('img').getAttribute('aria-label')).toBe('Last call: ping from node 1 to node 2 succeeded, round trip 7.237 ms.')
  })

  it('draws a failed call as a dashed arrow labelled with the failure kind', () => {
    render(<NodeMap overview={overviewCrashed} call={callCrashedTarget} />)
    const arrow = screen.getByTestId('call-arrow')
    expect(arrow.getAttribute('data-outcome')).toBe('failure')
    expect(arrow.querySelector('path').getAttribute('stroke-dasharray')).toBe('6 4')
    expect(screen.getByTestId('arrow-failure-kind').textContent).toBe('Connection refused')
  })

  it('draws a node calling itself as a loop', () => {
    render(<NodeMap overview={overviewAfter} call={callSelf} />)
    expect(screen.getByTestId('call-arrow').querySelector('path').getAttribute('d')).toContain('C')
  })

  it('carries the only Simulated badge, for co-hosting, with the overview\'s reason', () => {
    render(<NodeMap overview={overviewAfter} call={callPing} />)
    const badges = screen.getAllByTestId('simulated-badge')
    expect(badges).toHaveLength(1)
    expect(screen.getByRole('tooltip').textContent).toBe(overviewAfter.coHosting.reason)
  })
})
