import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import overviewBefore from '@/test/fixtures/faulttolerance/overview-before-start.json'
import statusRunning from '@/test/fixtures/faulttolerance/status-stream-running.json'
import statusSplitBrain from '@/test/fixtures/faulttolerance/synthetic/status-split-brain-failed.json'
import { SplitBrainPanel } from './SplitBrainPanel'

afterEach(cleanup)

describe('SplitBrainPanel', () => {
  it('shows a passed check by text and icon', () => {
    render(<SplitBrainPanel splitBrain={statusRunning.splitBrain} />)
    const panel = screen.getByTestId('split-brain')
    expect(panel.getAttribute('data-passed')).toBe('true')
    expect(screen.getByText('No two primaries: check passed')).toBeTruthy()
    expect(panel.querySelector('svg.lucide-shield-check')).toBeTruthy()
    expect(panel.textContent).toContain('Live primaries nowNode 1')
    expect(panel.textContent).toContain('Violations seen since the reset0')
    expect(screen.queryByTestId('violations')).toBeNull()
  })

  it('shows a failed check with a different text, icon and the violations (synthetic status-split-brain-failed)', () => {
    render(<SplitBrainPanel splitBrain={statusSplitBrain.splitBrain} />)
    const panel = screen.getByTestId('split-brain')
    expect(panel.getAttribute('data-passed')).toBe('false')
    expect(screen.getByText('Two primaries seen: check failed')).toBeTruthy()
    expect(panel.querySelector('svg.lucide-shield-alert')).toBeTruthy()
    expect(panel.textContent).toContain('Live primaries nowNodes 1, 2')
    expect(screen.getByTestId('violations').textContent)
      .toBe('Duplicate primary: nodes 1, 2 at epoch 2 (highest 2). Nodes 1 and 2 both act as primary at epoch 2.')
  })

  it('shows an unknown highest epoch as unknown, and no primaries as none', () => {
    render(<SplitBrainPanel splitBrain={overviewBefore.current.splitBrain} />)
    expect(screen.getByTestId('split-brain').textContent).toContain('Highest epochUnknown')
    expect(screen.getByText('None right now')).toBeTruthy()
  })

  it('renders nothing without a check', () => {
    const { container } = render(<SplitBrainPanel splitBrain={null} />)
    expect(container.innerHTML).toBe('')
  })
})
