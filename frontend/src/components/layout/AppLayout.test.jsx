import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, fireEvent, screen, within } from '@testing-library/react'
import { renderRoute } from '@/test/renderRoute'

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
  it('shows the four status slots with "—" and "Not connected yet"', () => {
    renderRoute('/')

    for (const label of ['Connection', 'Mode', 'Leader', 'Nodes up']) {
      const term = screen.getByText(label, { selector: 'dt' })
      expect(term.nextElementSibling.textContent).toBe('—')
    }
    expect(screen.getByText('Not connected yet')).toBeTruthy()
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
