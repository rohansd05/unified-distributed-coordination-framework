import { useEffect, useRef, useState } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { Sidebar } from '@/components/layout/Sidebar'
import { TopBar } from '@/components/layout/TopBar'
import { Toaster } from '@/components/ui/toaster'

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
 */
export function AppLayout() {
  const [menuOpen, setMenuOpen] = useState(false)
  const { pathname } = useLocation()
  const menuButtonRef = useRef(null)
  const closeButtonRef = useRef(null)
  const wasOpen = useRef(false)

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
        <main id="content" tabIndex={-1} className="relative flex-1 overflow-y-auto px-6 py-8 focus:outline-none">
          <div className="mx-auto max-w-5xl space-y-8">
            <Outlet />
          </div>
        </main>
      </div>
      <Toaster />
    </div>
  )
}
