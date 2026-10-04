import { Menu, X } from 'lucide-react'
import { Button } from '@/components/ui/button'

/** The four status slots; they become live in Step 2.3. */
const STATUS_SLOTS = ['Connection', 'Mode', 'Leader', 'Nodes up']

/**
 * Status bar above every page. Below md it starts with the menu button that opens the
 * sidebar drawer; `menuButtonRef` lets the layout return focus here when the drawer closes.
 */
export function TopBar({ menuOpen, onToggleMenu, menuButtonRef }) {
  return (
    <header className="sticky top-0 z-30 flex shrink-0 items-center gap-4 border-b bg-card px-4 py-2">
      <Button
        ref={menuButtonRef}
        variant="outline"
        size="icon"
        className="shrink-0 text-foreground md:hidden [&_svg]:size-5"
        aria-label={menuOpen ? 'Close menu' : 'Open menu'}
        aria-expanded={menuOpen}
        aria-controls="sidebar"
        onClick={onToggleMenu}
      >
        {menuOpen ? <X aria-hidden="true" /> : <Menu aria-hidden="true" />}
      </Button>

      <dl className="flex min-w-0 flex-1 flex-wrap items-center gap-x-6 gap-y-1 text-sm">
        {STATUS_SLOTS.map((label) => (
          <div key={label} className="flex items-baseline gap-2">
            <dt className="text-muted-foreground">{label}</dt>
            <dd className="font-mono">—</dd>
          </div>
        ))}
      </dl>
      <p className="hidden shrink-0 text-xs text-muted-foreground sm:block">Not connected yet</p>
    </header>
  )
}
