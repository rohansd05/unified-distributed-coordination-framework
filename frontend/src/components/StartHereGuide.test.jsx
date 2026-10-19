import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { START_HERE_KEY, StartHereGuide } from './StartHereGuide'

function renderGuide() {
  return render(<MemoryRouter><StartHereGuide /></MemoryRouter>)
}

const dismissButton = () => screen.getByRole('button', { name: 'Dismiss the start here guide' })

beforeEach(() => window.localStorage.clear())

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('StartHereGuide', () => {
  it('shows three short steps on a first visit, with links to an experiment and the scenarios', () => {
    renderGuide()
    const guide = screen.getByTestId('start-here')
    expect(within(guide).getByRole('heading', { level: 2 }).textContent).toBe('Start here')
    expect(within(guide).getAllByRole('heading', { level: 3 }).map((h) => h.textContent))
      .toEqual(['Look at the map', 'Open an experiment', 'Run a scenario'])
    expect(within(guide).getByRole('link', { name: 'Experiment 4, Bully and Ring Election' }).getAttribute('href')).toBe('/experiments/4-election')
    expect(within(guide).getByRole('link', { name: 'scenario' }).getAttribute('href')).toBe('/scenarios')
  })

  it('stays dismissed after a reload', () => {
    renderGuide()
    fireEvent.click(dismissButton())
    expect(screen.queryByTestId('start-here')).toBeNull()
    expect(window.localStorage.getItem(START_HERE_KEY)).toBe('true')

    cleanup()
    renderGuide()
    expect(screen.queryByTestId('start-here')).toBeNull()
  })

  it('works without storage: shown, then dismissed for this visit', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    renderGuide()
    expect(screen.getByTestId('start-here')).toBeTruthy()

    fireEvent.click(dismissButton())
    expect(screen.queryByTestId('start-here')).toBeNull()
  })
})
