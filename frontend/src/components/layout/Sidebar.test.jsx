import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { EXPERIMENTS } from '@/lib/experiments'
import { renderRoute } from '@/test/renderRoute'
import { silenceXhr } from '@/test/silentXhr'
import { Sidebar } from '@/components/layout/Sidebar'
import { ClusterContext } from '@/services/cluster/ClusterContext'

silenceXhr()

afterEach(() => {
  cleanup()
  vi.unstubAllEnvs()
})

function mainNav() {
  return within(screen.getByRole('navigation', { name: 'Main' }))
}

describe('Sidebar', () => {
  it('lists all ten experiments with lab badges and correct links', () => {
    renderRoute('/')
    const experiments = within(mainNav().getByRole('list', { name: 'Experiments' }))
    const links = experiments.getAllByRole('link')

    expect(links).toHaveLength(10)
    EXPERIMENTS.forEach((e, i) => {
      expect(links[i].getAttribute('href')).toBe(`/experiments/${e.slug}`)
      expect(links[i].textContent).toBe(`Lab ${e.lab}${e.title}`)
    })
  })

  it('marks only the active item with aria-current="page"', () => {
    renderRoute('/experiments/4-election')
    const nav = mainNav()

    const active = nav.getAllByRole('link').filter((link) => link.getAttribute('aria-current') === 'page')
    expect(active).toHaveLength(1)
    expect(active[0].getAttribute('href')).toBe('/experiments/4-election')
    expect(nav.getByRole('link', { name: 'Overview' }).getAttribute('aria-current')).toBeNull()
  })

  it('hides Monitoring without VITE_GRAFANA_URL', () => {
    vi.stubEnv('VITE_GRAFANA_URL', '')
    renderRoute('/')
    expect(mainNav().queryByRole('link', { name: 'Monitoring' })).toBeNull()
  })

  it('shows Monitoring with VITE_GRAFANA_URL', () => {
    vi.stubEnv('VITE_GRAFANA_URL', 'http://localhost:3000')
    renderRoute('/')
    expect(mainNav().getByRole('link', { name: 'Monitoring' }).getAttribute('href')).toBe('/monitoring')
  })

  it('shows no status, never "Planned", for experiments missing from /api/modules (e.g. before it loads)', () => {
    render(
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <ClusterContext.Provider value={{ modules: [] }}>
          <Sidebar open={false} onClose={() => {}} />
        </ClusterContext.Provider>
      </MemoryRouter>,
    )

    expect(screen.queryAllByText('Planned')).toHaveLength(0)
    expect(screen.getByRole('link', { name: /Parallel Matrix Multiplication/ }).textContent)
      .not.toMatch(/Idle|Running|Busy|Error/)
  })
})
