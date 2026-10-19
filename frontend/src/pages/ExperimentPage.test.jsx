import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, screen, within } from '@testing-library/react'
import { LAZY_TIMEOUT_MS, renderRoute } from '@/test/renderRoute'
import * as useModuleEventsModule from '@/hooks/useModuleEvents'
import * as useMultithreadingModule from '@/modules/multithreading/useMultithreading'
import overviewBefore from '@/test/fixtures/multithreading/overview-before.json'

// Every lab has a page now; the placeholder tests below need one without, so lab 10's page is left out here.
vi.mock('@/modules/registry', async (importOriginal) => {
  const { matrix, ...modulePages } = (await importOriginal()).modulePages   // eslint-disable-line no-unused-vars
  return { modulePages }
})

afterEach(cleanup)

function content() {
  return within(document.getElementById('content'))
}

describe('ExperimentPage', () => {
  it('shows the Section 8.4 headings in order', () => {
    renderRoute('/experiments/10-matrix')

    const headings = content().getAllByRole('heading').map((h) => `${h.tagName} ${h.textContent}`)
    expect(headings).toEqual([
      'H1 Parallel Matrix Multiplication',
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

  it('says plainly that the page is not part of this build, with no phase or "planned" wording', () => {
    renderRoute('/experiments/10-matrix')
    expect(screen.getByText("This experiment's page is not part of this build.")).toBeTruthy()
    expect(document.body.textContent).not.toMatch(/phase|planned|coming soon/i)
  })
})

describe('ExperimentPage with a registered module page', () => {
  afterEach(() => vi.restoreAllMocks())

  it('renders the module page for /experiments/2-multithreading in the shared layout', async () => {
    vi.spyOn(useMultithreadingModule, 'useMultithreading').mockReturnValue({
      overview: overviewBefore, requests: [], throughput: [], loading: false, error: null, active: false, refresh: vi.fn(),
    })
    vi.spyOn(useModuleEventsModule, 'useModuleEvents').mockReturnValue({ events: [], loading: false, error: null })

    renderRoute('/experiments/2-multithreading')

    expect(await content().findByTestId('experiment-layout', {}, { timeout: LAZY_TIMEOUT_MS })).toBeTruthy()
    const headings = content().getAllByRole('heading')
      .filter((h) => h.tagName === 'H1' || h.tagName === 'H2')
      .map((h) => `${h.tagName} ${h.textContent}`)
    expect(headings).toEqual([
      'H1 Multithreading',
      'H2 How it works',
      'H2 Controls',
      'H2 Live visualisation',
      'H2 Measurements',
      'H2 Event log',
      'H2 What to notice',
    ])
    expect(content().queryByText("This experiment's page is not part of this build.")).toBeNull()
  })
})
