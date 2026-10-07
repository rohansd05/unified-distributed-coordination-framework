import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, screen } from '@testing-library/react'
import { EXPERIMENTS } from '@/lib/experiments'
import { api } from '@/services/api'
import { multithreadingApi } from '@/modules/multithreading/multithreadingApi'
import { renderRoute } from '@/test/renderRoute'
import overviewBefore from '@/test/fixtures/multithreading/overview-before.json'

// Every page here renders with its real default api, so mock the calls the pages make on
// mount: no test may reach the network, whether or not a backend happens to be running.
beforeEach(() => {
  vi.spyOn(api, 'getEvents').mockResolvedValue([])
  vi.spyOn(multithreadingApi, 'getOverview').mockResolvedValue(overviewBefore)
  vi.spyOn(multithreadingApi, 'getRequests').mockResolvedValue([])
})

afterEach(() => {
  cleanup()
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
})

function heading() {
  return screen.getByRole('heading', { level: 1 }).textContent
}

describe('routes', () => {
  it.each([
    ['/', 'Overview'],
    ['/cluster', 'Cluster'],
    ['/scenarios', 'Scenarios'],
    ['/timeline', 'Timeline'],
    ['/about', 'About'],
  ])('%s renders the %s page', (path, title) => {
    renderRoute(path)
    expect(heading()).toBe(title)
  })

  it.each(EXPERIMENTS.map((e) => [e.slug, e.title]))('/experiments/%s renders "%s"', (slug, title) => {
    renderRoute(`/experiments/${slug}`)
    expect(heading()).toBe(title)
  })

  it.each(['/experiments/99-nope', '/does-not-exist'])('%s renders NotFound', (path) => {
    renderRoute(path)
    expect(heading()).toBe('Page not found')
    expect(screen.getByRole('link', { name: 'Back to Overview' }).getAttribute('href')).toBe('/')
  })

  it('/monitoring renders when VITE_GRAFANA_URL is set', () => {
    vi.stubEnv('VITE_GRAFANA_URL', 'http://localhost:3000')
    renderRoute('/monitoring')
    expect(heading()).toBe('Monitoring')
  })

  it('/monitoring renders NotFound when VITE_GRAFANA_URL is empty', () => {
    vi.stubEnv('VITE_GRAFANA_URL', '')
    renderRoute('/monitoring')
    expect(heading()).toBe('Page not found')
  })

  it('prints no React Router future-flag warning', () => {
    const warn = vi.spyOn(console, 'warn')
    renderRoute('/experiments/4-election')
    const futureWarnings = warn.mock.calls.filter((args) => String(args[0]).includes('Future Flag Warning'))
    expect(futureWarnings).toEqual([])
  })
})
