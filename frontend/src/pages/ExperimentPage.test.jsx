import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, screen, within } from '@testing-library/react'
import { renderRoute } from '@/test/renderRoute'

afterEach(cleanup)

function content() {
  return within(document.getElementById('content'))
}

describe('ExperimentPage', () => {
  it('shows the Section 8.4 headings in order', () => {
    renderRoute('/experiments/4-election')

    const headings = content().getAllByRole('heading').map((h) => `${h.tagName} ${h.textContent}`)
    expect(headings).toEqual([
      'H1 Bully and Ring Election',
      'H2 How it works',
      'H2 Controls',
      'H2 Live visualisation',
      'H2 Measurements',
      'H2 Event log',
      'H2 What to notice',
    ])
  })

  it('shows the lab badge and the module id', () => {
    renderRoute('/experiments/10-matrix')

    const header = content().getByRole('heading', { level: 1 }).parentElement
    expect(header.textContent).toContain('Lab 10')
    expect(content().getByText('module id: matrix')).toBeTruthy()
  })

  it('says which phase the module arrives in', () => {
    renderRoute('/experiments/4-election')
    expect(screen.getByText('This module arrives in Phase 5.')).toBeTruthy()
  })
})
