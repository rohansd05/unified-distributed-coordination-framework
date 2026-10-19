import { Suspense, useEffect, useRef, useState } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { Sidebar } from '@/components/layout/Sidebar'
import { TopBar } from '@/components/layout/TopBar'
import { WakingBanner } from '@/components/layout/WakingBanner'
import { Toaster } from '@/components/ui/toaster'
import { useCluster } from '@/hooks/useCluster'

/** Shown while a page's chunk loads (14.1: one chunk per experiment page, Timeline and Scenarios). */
function PageLoading() {
  return (
    <div role="status" data-testid="page-loading" className="flex items-center gap-3 py-16 text-sm text-muted-foreground">
      <span className="size-2 animate-pulse rounded-full bg-primary motion-reduce:animate-none" aria-hidden="true" />
      Loading the page…
    </div>
  )
}

/**
 * Full-height app shell: skip link, sidebar, top bar and the routed page.
 *
 * The document never scrolls. <main id="content"> is the content scroller and the sidebar
 * scrolls on its own, so the sidebar and top bar always stay in view. <main> is also
 * position: relative, so it is the containing block of every absolutely positioned element in
 * a page (sr-only text, aria-live regions, tooltips). Without that, such an element takes the
 * document as its containing block, escapes main's overflow and makes the document scroll.
 *
 * Below md the sidebar is a drawer. It closes on a backdrop click, Escape, its own close
 * button, the top-bar button or navigation. Focus moves into the drawer when it opens and
 * back to the menu button when it closes.
 *
 * While the backend wakes from a cold start (useCluster().wakingSince), a calm banner replaces
 * any error and the page waits; it mounts, and so loads its data, once the backend answers.
 */
export function AppLayout() {
  const [menuOpen, setMenuOpen] = useState(false)
  const { pathname } = useLocation()
  const menuButtonRef = useRef(null)
  const closeButtonRef = useRef(null)
  const wasOpen = useRef(false)
  const { wakingSince } = useCluster()
  const waking = wakingSince != null

  const closeMenu = () => setMenuOpen(false)

  // Close the drawer whenever the route changes.
  useEffect(() => {
    setMenuOpen(false)
  }, [pathname])

  // Move focus into the drawer on open, and back to the menu button on close.
  useEffect(() => {
    if (menuOpen) {
      closeButtonRef.current?.focus()
    } else if (wasOpen.current) {
      menuButtonRef.current?.focus()
    }
    wasOpen.current = menuOpen
  }, [menuOpen])

  // Escape closes the drawer while it is open.
  useEffect(() => {
    if (!menuOpen) {
      return undefined
    }
    const onKeyDown = (event) => {
      if (event.key === 'Escape') {
        setMenuOpen(false)
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [menuOpen])

  return (
    <div data-testid="app-shell" className="flex h-dvh overflow-hidden">
      <a
        href="#content"
        className="sr-only z-[60] rounded-md bg-primary px-4 py-2 text-primary-foreground focus:not-sr-only focus:fixed focus:left-4 focus:top-4"
      >
        Skip to content
      </a>

      <Sidebar open={menuOpen} onClose={closeMenu} closeButtonRef={closeButtonRef} />
      {menuOpen && (
        <div
          data-testid="sidebar-backdrop"
          aria-hidden="true"
          className="fixed inset-0 z-40 bg-background/70 md:hidden"
          onClick={closeMenu}
        />
      )}

      <div className="flex min-w-0 flex-1 flex-col">
        <TopBar
          menuOpen={menuOpen}
          onToggleMenu={() => setMenuOpen((open) => !open)}
          menuButtonRef={menuButtonRef}
        />
        {waking && <WakingBanner since={wakingSince} />}
        <main id="content" tabIndex={-1} className="relative flex-1 overflow-y-auto px-6 py-8 focus:outline-none">
          <div className="mx-auto max-w-5xl space-y-8">
            {waking ? (
              <p data-testid="waking-placeholder" className="rounded-lg border border-dashed bg-card p-6 text-sm text-muted-foreground">
                This page loads as soon as the backend answers.
              </p>
            ) : (
              <Suspense fallback={<PageLoading />}>
                <Outlet />
              </Suspense>
            )}
          </div>
        </main>
      </div>
      <Toaster />
    </div>
  )
}
