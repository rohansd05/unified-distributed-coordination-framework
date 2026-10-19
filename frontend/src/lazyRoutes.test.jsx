import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, screen } from '@testing-library/react'
import { LAZY_TIMEOUT_MS, renderRoute } from '@/test/renderRoute'
import { silenceXhr } from '@/test/silentXhr'

/*
 * 14.1 code splitting: experiment pages, Timeline and Scenarios are lazy chunks. React caches a
 * loaded chunk, so each path is rendered once here, in its own file (fresh module cache). A
 * silent XMLHttpRequest keeps the pages' requests pending, so no test reaches the network.
 */
silenceXhr()

afterEach(cleanup)

describe('lazy routes', () => {
  it.each([
    ['/timeline', 'Timeline'],
    ['/scenarios', 'Scenarios'],
    ['/experiments/1-rmi', 'Client-Server via Java RMI'],
  ])('%s shows a calm loading state, then renders its page', async (path, title) => {
    renderRoute(path)
    expect(screen.getByTestId('page-loading').textContent).toBe('Loading the page…')
    expect(screen.getByRole('status').textContent).toBe('Loading the page…')

    expect((await screen.findByRole('heading', { level: 1 }, { timeout: LAZY_TIMEOUT_MS })).textContent).toBe(title)
    expect(screen.queryByTestId('page-loading')).toBeNull()
  })

  it('Overview is in the main chunk: it renders at once', () => {
    renderRoute('/')
    expect(screen.queryByTestId('page-loading')).toBeNull()
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Overview')
  })
})
