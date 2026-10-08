import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { VerificationPanel } from './VerificationPanel'

describe('VerificationPanel', () => {
  afterEach(() => {
    cleanup()
  })
  it('renders initial prompt when verification has not been run', () => {
    const onVerify = vi.fn()
    render(<VerificationPanel verification={null} onVerify={onVerify} />)

    expect(screen.getByText(/Click "Verify causal invariants" to check/i)).toBeTruthy()
    const btn = screen.getByRole('button', { name: 'Verify causal invariants' })
    fireEvent.click(btn)
    expect(onVerify).toHaveBeenCalledTimes(1)
  })

  it('renders "0 causal violations" when verified and passed', () => {
    const passedDto = {
      totalEventsChecked: 20,
      receiveEventsChecked: 9,
      violationsCount: 0,
      passed: true,
      summary: 'PASS - Causal invariant preserved across all 20 events.',
      violations: [],
      retainedEventsCount: 20,
      droppedEventsCount: 0,
      retainedWindowNote: 'Log within capacity. All recorded events verified.',
    }

    render(<VerificationPanel verification={passedDto} onVerify={vi.fn()} />)

    expect(screen.getByText('0 causal violations')).toBeTruthy()
    expect(screen.getByText(/Total events checked:/i).textContent).toContain('20')
    expect(screen.getByText(/Receive events checked:/i).textContent).toContain('9')
    expect(screen.getByText('Log within capacity. All recorded events verified.')).toBeTruthy()
  })

  it('renders violation list when violations are detected', () => {
    const violationDto = {
      totalEventsChecked: 10,
      receiveEventsChecked: 4,
      violationsCount: 1,
      passed: false,
      summary: 'FAIL - Causal invariant violated.',
      violations: [
        {
          nodeId: 2,
          type: 'RECEIVE_NOT_AFTER_SEND',
          actualLamportTime: 3,
          expectedRelationTime: 4,
          peerId: 1,
          message: 'Receive time 3 <= send time 4',
        },
      ],
      retainedEventsCount: 10,
      droppedEventsCount: 0,
      retainedWindowNote: 'Log within capacity.',
    }

    render(<VerificationPanel verification={violationDto} onVerify={vi.fn()} />)

    expect(screen.getByText('1 causal violations detected')).toBeTruthy()
    expect(screen.getByTestId('violation-item')).toBeTruthy()
    expect(screen.getByText('Receive not after send (Node 2)')).toBeTruthy()
    expect(screen.getByText('Receive time 3 <= send time 4')).toBeTruthy()
  })
})
