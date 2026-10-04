import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { PROVIDER_FUTURE, ROUTER_FUTURE, routes } from '@/routes'

/** Renders the real route table at `path`, with the same future flags as the app. */
export function renderRoute(path) {
  const router = createMemoryRouter(routes, { initialEntries: [path], future: ROUTER_FUTURE })
  return { router, ...render(<RouterProvider router={router} future={PROVIDER_FUTURE} />) }
}
