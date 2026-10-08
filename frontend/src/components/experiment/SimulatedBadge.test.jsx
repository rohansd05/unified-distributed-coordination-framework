import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { SimulatedBadge } from './SimulatedBadge'

afterEach(cleanup)

describe('SimulatedBadge', () => {
  it('shows the word Simulated as visible text', () => {
    render(<SimulatedBadge reason="A sleep stands in for a disk read." />)

    expect(screen.getByTestId('simulated-badge').textContent).toContain('Simulated')
  })

  it('gives the reason as a tooltip that is also the badge description', () => {
    render(<SimulatedBadge reason="A sleep stands in for a disk read." />)

    const tooltip = screen.getByRole('tooltip', { hidden: true })
    expect(tooltip.textContent).toBe('A sleep stands in for a disk read.')
    const trigger = screen.getByText('Simulated')
    expect(trigger.getAttribute('aria-describedby')).toBe(tooltip.id)
  })

  it('can be reached with the keyboard, which opens the tooltip', () => {
    render(<SimulatedBadge reason="Why" />)

    const trigger = screen.getByText('Simulated')
    expect(trigger.tabIndex).toBe(0)
    trigger.focus()
    expect(document.activeElement).toBe(trigger)
    // The tooltip is shown by group-focus-within, so it must sit inside the focusable group.
    expect(screen.getByTestId('simulated-badge').contains(screen.getByRole('tooltip', { hidden: true }))).toBe(true)
  })

  it('keeps the closed tooltip out of the layout (display none), so it never widens a 375 px page', () => {
    render(<SimulatedBadge reason="Why" />)

    const classes = screen.getByRole('tooltip', { hidden: true }).className.split(' ')
    expect(classes).toContain('hidden')
    expect(classes).toContain('group-focus-within:block')
    expect(classes).not.toContain('invisible')
  })

  it('has no tooltip and no description without a reason', () => {
    render(<SimulatedBadge />)

    expect(screen.queryByRole('tooltip', { hidden: true })).toBeNull()
    expect(screen.getByText('Simulated').hasAttribute('aria-describedby')).toBe(false)
  })
})
