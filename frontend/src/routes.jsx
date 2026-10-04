import { AppLayout } from '@/components/layout/AppLayout'
import { AboutPage } from '@/pages/AboutPage'
import { ClusterPage } from '@/pages/ClusterPage'
import { ExperimentPage } from '@/pages/ExperimentPage'
import { MonitoringPage } from '@/pages/MonitoringPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { OverviewPage } from '@/pages/OverviewPage'
import { ScenariosPage } from '@/pages/ScenariosPage'
import { TimelinePage } from '@/pages/TimelinePage'

/*
 * Security rule: never build a <Link to> or navigate() target from untrusted input
 * (query strings, server data, user text). React Router 6.x has an open-redirect advisory
 * for such targets (GHSA-wrjc-x8rr-h8h6); it and GHSA-337j-9hxr-rhxg (SSR hydration only)
 * are fixed only in react-router 7.18+, a major upgrade outside R15. Every target here
 * comes from a literal path or the static experiment catalog.
 */

/**
 * Every page (docs/HANDOFF.md 8.2). Shared by main.jsx and the tests. /monitoring renders
 * NotFound unless VITE_GRAFANA_URL is set; MonitoringPage checks it at render time.
 */
export const routes = [
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <OverviewPage /> },
      { path: 'cluster', element: <ClusterPage /> },
      { path: 'experiments/:slug', element: <ExperimentPage /> },
      { path: 'scenarios', element: <ScenariosPage /> },
      { path: 'timeline', element: <TimelinePage /> },
      { path: 'monitoring', element: <MonitoringPage /> },
      { path: 'about', element: <AboutPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]

/** Opt in to every React Router v7 behaviour this version supports, so no warnings appear. */
export const ROUTER_FUTURE = {
  v7_fetcherPersist: true,
  v7_normalizeFormMethod: true,
  v7_partialHydration: true,
  v7_relativeSplatPath: true,
  v7_skipActionErrorRevalidation: true,
}

/** Future flags that belong on <RouterProvider>. */
export const PROVIDER_FUTURE = {
  v7_startTransition: true,
}
