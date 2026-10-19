import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { PROVIDER_FUTURE, ROUTER_FUTURE, routes } from '@/routes'
import { ClusterContext } from '@/services/cluster/ClusterContext'
import { LAZY_TIMEOUT_MS, renderRoute } from '@/test/renderRoute'
import { silenceXhr } from '@/test/silentXhr'
import * as useModuleEventsModule from '@/hooks/useModuleEvents'
import * as useMultithreadingModule from '@/modules/multithreading/useMultithreading'
import overviewAfter from '@/test/fixtures/multithreading/overview-after-batch.json'

/*
 * The pages render with their real api clients and no backend. A silent XMLHttpRequest keeps
 * every request pending, so jsdom never prints connection-refused (AggregateError) traces.
 */
silenceXhr()

afterEach(cleanup)

/*
 * jsdom cannot evaluate media queries or load the Tailwind CSS, so these tests assert the
 * classes and state that drive each breakpoint; the Playwright check covers the visuals.
 */

function menuButton() {
  return screen.getByRole('button', { name: /^(Open|Close) menu$/ })
}

function sidebar() {
  return document.getElementById('sidebar')
}

function backdrop() {
  return screen.queryByTestId('sidebar-backdrop')
}

function openMenu() {
  fireEvent.click(menuButton())
  expect(sidebar().dataset.state).toBe('open')
}

describe('AppLayout and TopBar', () => {
  it('shows the four status slots in the top bar', () => {
    renderRoute('/')

    expect(screen.getByText('Connection', { selector: 'dt' }).nextElementSibling.textContent).toBe('Backend unreachable')
    for (const label of ['Mode', 'Leader', 'Nodes up']) {
      const term = screen.getByText(label, { selector: 'dt' })
      expect(term.nextElementSibling.textContent).toBe('—')
    }
  })

  it('has a skip link targeting the main content', () => {
    renderRoute('/')
    const skip = screen.getByRole('link', { name: 'Skip to content' })
    expect(skip.getAttribute('href')).toBe('#content')
    expect(document.getElementById('content').tagName).toBe('MAIN')
  })

  it('is a full-height shell where main scrolls and the top bar stays in view', () => {
    renderRoute('/')
    const shell = screen.getByTestId('app-shell')
    const main = document.getElementById('content')
    const topBar = menuButton().closest('header')

    expect(shell.classList.contains('h-dvh')).toBe(true)
    expect(shell.classList.contains('overflow-hidden')).toBe(true)
    expect(main.classList.contains('overflow-y-auto')).toBe(true)
    expect(topBar.classList.contains('sticky')).toBe(true)
    expect(topBar.classList.contains('top-0')).toBe(true)
    expect(sidebar().classList.contains('overflow-y-auto')).toBe(true)
  })

  it('the menu button is named and wired to the sidebar, and hidden from md up', () => {
    renderRoute('/')
    const button = menuButton()

    expect(button.getAttribute('aria-label')).toBe('Open menu')
    expect(button.getAttribute('aria-expanded')).toBe('false')
    expect(button.getAttribute('aria-controls')).toBe('sidebar')
    expect(button.classList.contains('md:hidden')).toBe(true)
    expect(button.querySelector('svg')).toBeTruthy()
  })

  it('starts with the drawer closed below md and always visible from md up', () => {
    renderRoute('/')
    const aside = sidebar()

    expect(aside.dataset.state).toBe('closed')
    expect(aside.classList.contains('invisible')).toBe(true)
    expect(aside.classList.contains('-translate-x-full')).toBe(true)
    expect(aside.classList.contains('fixed')).toBe(true)
    for (const cls of ['md:visible', 'md:static', 'md:translate-x-0']) {
      expect(aside.classList.contains(cls), cls).toBe(true)
    }
    expect(backdrop()).toBeNull()
  })

  it('opening the menu shows the drawer and backdrop and moves focus into the drawer', () => {
    renderRoute('/')

    openMenu()

    const button = menuButton()
    expect(button.getAttribute('aria-label')).toBe('Close menu')
    expect(button.getAttribute('aria-expanded')).toBe('true')
    expect(sidebar().classList.contains('visible')).toBe(true)
    expect(sidebar().classList.contains('translate-x-0')).toBe(true)
    expect(sidebar().classList.contains('invisible')).toBe(false)
    expect(backdrop()).toBeTruthy()
    expect(sidebar().contains(document.activeElement)).toBe(true)
    expect(document.activeElement.getAttribute('aria-label')).toBe('Close navigation')
  })

  it('a backdrop click closes the drawer and returns focus to the menu button', () => {
    renderRoute('/')
    openMenu()

    fireEvent.click(backdrop())

    expect(sidebar().dataset.state).toBe('closed')
    expect(backdrop()).toBeNull()
    expect(menuButton().getAttribute('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(menuButton())
  })

  it('Escape closes the drawer', () => {
    renderRoute('/')
    openMenu()

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(sidebar().dataset.state).toBe('closed')
    expect(document.activeElement).toBe(menuButton())
  })

  it('the drawer close button and the menu button both close it', () => {
    renderRoute('/')
    openMenu()
    fireEvent.click(screen.getByRole('button', { name: 'Close navigation' }))
    expect(sidebar().dataset.state).toBe('closed')

    openMenu()
    fireEvent.click(menuButton())
    expect(sidebar().dataset.state).toBe('closed')
  })

  it('following a navigation link closes the drawer', () => {
    renderRoute('/')
    openMenu()

    const nav = within(screen.getByRole('navigation', { name: 'Main' }))
    fireEvent.click(nav.getByRole('link', { name: 'Cluster' }))

    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Cluster')
    expect(sidebar().dataset.state).toBe('closed')
    expect(menuButton().getAttribute('aria-expanded')).toBe('false')
  })
})

/*
 * Guards the E2d fix: the document must never scroll. <main id="content"> is the scroll
 * container AND position: relative, so every absolutely positioned element of a page
 * (sr-only text, aria-live regions, tooltips) has <main> as its containing block and stays
 * inside main's overflow. jsdom does no layout, so it cannot measure scrollHeight or
 * containing blocks; these tests check the classes and the DOM placement that produce them,
 * and the browser measurement in the step report is the real proof.
 */
describe('AppLayout keeps the document from scrolling', () => {
  afterEach(() => vi.restoreAllMocks())

  it('main is the positioned scroll container inside the full-height, overflow-hidden shell', () => {
    renderRoute('/experiments/4-election')
    const shell = screen.getByTestId('app-shell')
    const main = document.getElementById('content')

    expect(shell.classList.contains('h-dvh')).toBe(true)
    expect(shell.classList.contains('overflow-hidden')).toBe(true)
    expect(main.classList.contains('relative')).toBe(true)
    expect(main.classList.contains('overflow-y-auto')).toBe(true)
  })

  it('every visually hidden element and live region of a module page is rendered inside main', async () => {
    vi.spyOn(useMultithreadingModule, 'useMultithreading').mockReturnValue({
      overview: overviewAfter, requests: [], throughput: [], loading: false, error: null, active: false, refresh: vi.fn(),
    })
    vi.spyOn(useModuleEventsModule, 'useModuleEvents').mockReturnValue({ events: [], loading: false, error: null })

    renderRoute('/experiments/2-multithreading')
    await screen.findByTestId('experiment-layout', {}, { timeout: LAZY_TIMEOUT_MS })
    const main = document.getElementById('content')
    const shell = screen.getByTestId('app-shell')
    const live = shell.querySelector('[aria-live="polite"]')

    expect(live).toBeTruthy()
    expect(main.contains(live)).toBe(true)
    const pageHidden = [...shell.querySelectorAll('.sr-only, [role="tooltip"]')]
      .filter((element) => !document.getElementById('sidebar').contains(element))
      .filter((element) => element.textContent !== 'Skip to content')
    expect(pageHidden.length).toBeGreaterThan(0)
    for (const element of pageHidden) {
      expect(main.contains(element), element.outerHTML.slice(0, 80)).toBe(true)
    }
  })
})

describe('AppLayout while the backend wakes from a cold start (14.1)', () => {
  const NOW = 1_700_000_000_000

  function renderWaking(wakingSince) {
    const value = (since) => ({ info: null, cluster: null, modules: null, loading: false, error: new Error('down'), wakingSince: since, refresh: vi.fn() })
    const router = createMemoryRouter(routes, { initialEntries: ['/'], future: ROUTER_FUTURE })
    const tree = (since) => (
      <ClusterContext.Provider value={value(since)}>
        <RouterProvider router={router} future={PROVIDER_FUTURE} />
      </ClusterContext.Provider>
    )
    const view = render(tree(wakingSince))
    return { awake: () => view.rerender(tree(null)) }
  }

  afterEach(() => vi.useRealTimers())

  it('shows a calm banner with elapsed seconds, holds the page, and never says "unreachable"', () => {
    vi.useFakeTimers({ now: NOW })
    renderWaking(NOW - 12_000)

    const banner = screen.getByTestId('waking-banner')
    expect(within(banner).getByRole('status').textContent).toBe(
      'The backend is waking up — free hosting sleeps after 15 minutes without visitors. This takes about a minute.')
    expect(screen.getByTestId('waking-elapsed').textContent).toBe('12 s')
    act(() => { vi.advanceTimersByTime(1000) })
    expect(screen.getByTestId('waking-elapsed').textContent).toBe('13 s')

    expect(screen.getByTestId('waking-placeholder')).toBeTruthy()
    expect(screen.queryByTestId('overview-error')).toBeNull()
    expect(screen.getByText('Connection', { selector: 'dt' }).nextElementSibling.textContent).toBe('Waking up…')
    expect(document.body.textContent).not.toMatch(/unreachable/i)
    expect(banner.className).not.toMatch(/destructive/)
  })

  it('hides the banner and mounts the page once the backend answers', () => {
    const { awake } = renderWaking(Date.now())
    expect(screen.queryByRole('heading', { level: 1 })).toBeNull()

    awake()

    expect(screen.queryByTestId('waking-banner')).toBeNull()
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Overview')
  })
})
