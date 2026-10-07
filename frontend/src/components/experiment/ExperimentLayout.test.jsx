import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import * as useModuleEventsModule from '@/hooks/useModuleEvents'
import { ExperimentLayout } from './ExperimentLayout'

const EXPERIMENT = { lab: 7, id: 'example', title: 'Example Experiment', concept: 'One idea in one line.' }

function renderLayout(experiment = EXPERIMENT) {
  return render(
    <ExperimentLayout
      experiment={experiment}
      howItWorks={<p>The explanation.</p>}
      controls={<button type="button">Do it</button>}
      visualisation={<div>The picture</div>}
      measurements={<div>The numbers</div>}
      whatToNotice={['First callout.', 'Second callout.']}
    />,
  )
}

beforeEach(() => {
  vi.spyOn(useModuleEventsModule, 'useModuleEvents').mockReturnValue({ events: [], loading: false, error: null })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('ExperimentLayout', () => {
  it('renders the HANDOFF 8.4 sections in order under one h1', () => {
    renderLayout()

    const headings = screen.getAllByRole('heading').map((h) => `${h.tagName} ${h.textContent}`)
    expect(headings).toEqual([
      'H1 Example Experiment',
      'H2 How it works',
      'H2 Controls',
      'H2 Live visualisation',
      'H2 Measurements',
      'H2 Event log',
      'H2 What to notice',
    ])
  })

  it('carries the stable test ids other tracks rely on', () => {
    renderLayout()

    expect(screen.getByTestId('experiment-layout')).toBeTruthy()
    for (const name of ['how-it-works', 'controls', 'visualisation', 'measurements', 'event-log', 'what-to-notice']) {
      expect(screen.getByTestId(`section-${name}`), name).toBeTruthy()
    }
  })

  it('shows the lab badge and the concept line under the title', () => {
    renderLayout()

    const header = screen.getByRole('heading', { level: 1 }).parentElement.parentElement
    expect(header.textContent).toContain('Lab 7')
    expect(screen.getByText('One idea in one line.')).toBeTruthy()
  })

  it('shows no concept line when the experiment has none', () => {
    renderLayout({ ...EXPERIMENT, concept: undefined })

    expect(screen.queryByText('One idea in one line.')).toBeNull()
  })

  it('adds the module event log for experiment.id by itself', () => {
    renderLayout()

    expect(screen.getByTestId('section-event-log').querySelector('[data-testid="module-event-log"]')).toBeTruthy()
    expect(useModuleEventsModule.useModuleEvents).toHaveBeenCalledWith(expect.objectContaining({ moduleId: 'example' }))
  })

  it('lets How it works collapse and expand, open by default', () => {
    renderLayout()

    const toggle = screen.getByRole('button', { name: 'How it works' })
    const panel = document.getElementById(toggle.getAttribute('aria-controls'))
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    expect(panel.hidden).toBe(false)

    fireEvent.click(toggle)
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    expect(panel.hidden).toBe(true)

    fireEvent.click(toggle)
    expect(panel.hidden).toBe(false)
  })

  it('renders an array of callouts as a list, and the other slots as given', () => {
    renderLayout()

    const items = screen.getByTestId('section-what-to-notice').querySelectorAll('li')
    expect([...items].map((li) => li.textContent)).toEqual(['First callout.', 'Second callout.'])
    expect(screen.getByTestId('section-controls').textContent).toContain('Do it')
    expect(screen.getByTestId('section-visualisation').textContent).toContain('The picture')
    expect(screen.getByTestId('section-measurements').textContent).toContain('The numbers')
  })
})
