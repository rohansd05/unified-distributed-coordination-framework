import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import App from './App'
import { TOKENS } from '@/lib/tokens'

afterEach(cleanup)

describe('App shell placeholder', () => {
  it('renders the main heading', () => {
    render(<App />)

    const heading = screen.getByRole('heading', { level: 1 })
    expect(heading.textContent).toBe('Unified Distributed Coordination Framework')
  })

  it('renders all ten design token names', () => {
    render(<App />)

    expect(TOKENS).toHaveLength(10)
    for (const name of ['background', 'surface', 'border', 'navy', 'primary', 'accent', 'warning',
      'danger', 'text', 'muted']) {
      expect(screen.getByText(name, { selector: 'p' })).toBeTruthy()
    }
  })

  it('renders the primary and outline buttons', () => {
    render(<App />)

    expect(screen.getByRole('button', { name: 'Primary action' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Outline action' })).toBeTruthy()
  })
})
