import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, screen } from '@testing-library/react'
import { EXPERIMENTS } from '@/lib/experiments'
import { renderRoute } from '@/test/renderRoute'

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
