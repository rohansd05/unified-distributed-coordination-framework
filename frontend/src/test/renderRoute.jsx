import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { PROVIDER_FUTURE, ROUTER_FUTURE, routes } from '@/routes'

/** How long a test waits for a lazy page's chunk (14.1); the first import is slow under a full parallel run. */
export const LAZY_TIMEOUT_MS = 5000

/** Renders the real route table at `path`, with the same future flags as the app. */
export function renderRoute(path) {
  const router = createMemoryRouter(routes, { initialEntries: [path], future: ROUTER_FUTURE })
  return { router, ...render(<RouterProvider router={router} future={PROVIDER_FUTURE} />) }
}
