import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { ProblemAlert } from './ProblemAlert'

afterEach(cleanup)

const alert = () => screen.getByTestId('action-error')

describe('ProblemAlert', () => {
  it('renders nothing without a problem', () => {
    const { container } = render(<ProblemAlert problem={null} />)
    expect(container.innerHTML).toBe('')
  })

  it('shows 409 no leader as a calm status with its heading', () => {
    render(<ProblemAlert problem={{ kind: 'no-leader', message: 'No leader is elected.' }} />)
    expect(alert().getAttribute('role')).toBe('status')
    expect(alert().textContent).toBe('No leaderNo leader is elected.')
    expect(alert().className).toContain('border-warning')
  })

  it('shows 409 busy calmly and names the action in progress', () => {
    render(<ProblemAlert problem={{ kind: 'busy', message: 'Busy.', actionInProgress: 'A batch' }} />)
    expect(alert().getAttribute('role')).toBe('alert')
    expect(alert().dataset.kind).toBe('busy')
    expect(alert().className).toContain('border-warning')
    expect(alert().textContent).toContain('Running now: A batch')
  })

  it('shows other problems as errors, with field errors by label or field name', () => {
    render(<ProblemAlert problem={{ kind: 'validation', message: 'Refused:', reason: 'X', fieldErrors: { rate: 'too high', seed: 'missing' } }}
      fieldLabels={{ rate: 'Rate' }} />)
    expect(alert().className).toContain('border-destructive')
    expect(alert().dataset.reason).toBe('X')
    expect(screen.getByText('Rate: too high')).toBeTruthy()
    expect(screen.getByText('seed: missing')).toBeTruthy()
  })

  it('adds the page\'s own lines and passes the ref for focus', () => {
    const ref = { current: null }
    render(<ProblemAlert problem={{ kind: 'timeout', message: 'Timed out.' }} alertRef={ref}><p>Extra line</p></ProblemAlert>)
    expect(ref.current).toBe(alert())
    expect(alert().textContent).toBe('Timed out.Extra line')
  })
})
