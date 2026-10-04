import { NavLink } from 'react-router-dom'
import { Activity, GitBranch, Info, LayoutDashboard, ListOrdered, Server, X } from 'lucide-react'
import { LabBadge } from '@/components/LabBadge'
import { Button } from '@/components/ui/button'
import { EXPERIMENTS } from '@/lib/experiments'
import { grafanaUrl } from '@/lib/env'
import { cn } from '@/lib/utils'

/**
 * Active items get aria-current="page" from NavLink, plus a left bar and a heavier weight,
 * so the highlight never relies on colour alone.
 */
function itemClass({ isActive }) {
  return cn(
    'flex items-center gap-3 rounded-md border-l-4 px-3 py-2 text-sm transition-colors',
    'hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
    isActive
      ? 'border-success bg-background/40 font-semibold text-foreground'
      : 'border-transparent text-muted-foreground',
  )
}

function Item({ to, icon: Icon, end, children }) {
  return (
    <li>
      <NavLink to={to} end={end} className={itemClass}>
        {Icon && <Icon className="size-4 shrink-0" aria-hidden="true" />}
        <span className="truncate">{children}</span>
      </NavLink>
    </li>
  )
}

/**
 * The main navigation (docs/HANDOFF.md 8.3).
 *
 * At md and above it is a static, full-height column that scrolls on its own. Below md it is
 * an overlay drawer: off-screen and `invisible` (so its links leave the tab order) until
 * `open`. `closeButtonRef` receives focus when the drawer opens.
 */
export function Sidebar({ open, onClose, closeButtonRef }) {
  const showMonitoring = grafanaUrl() !== null

  return (
    <aside
      id="sidebar"
      data-state={open ? 'open' : 'closed'}
      className={cn(
        'flex w-72 max-w-[85vw] shrink-0 flex-col overflow-y-auto border-r bg-secondary text-secondary-foreground',
        'fixed inset-y-0 left-0 z-50 duration-200 motion-reduce:transition-none',
        // Opening switches visibility instantly so focus can move in; closing keeps the
        // drawer visible until it has slid out (the new state's transition rule applies).
        open ? 'visible translate-x-0 transition-transform' : 'invisible -translate-x-full transition-[transform,visibility]',
        'md:visible md:static md:z-auto md:w-64 md:max-w-none md:translate-x-0 md:transition-none',
      )}
    >
      <div className="flex items-start justify-between gap-2 px-5 py-4">
        <div>
          <p className="text-lg font-semibold tracking-tight">UDCF</p>
          <p className="text-xs text-muted-foreground">Distributed Coordination Framework</p>
        </div>
        <Button
          ref={closeButtonRef}
          variant="ghost"
          size="icon"
          className="-mr-2 shrink-0 md:hidden [&_svg]:size-5"
          aria-label="Close navigation"
          onClick={onClose}
        >
          <X aria-hidden="true" />
        </Button>
      </div>
      <nav aria-label="Main" className="flex-1 space-y-6 px-3 pb-6">
        <ul className="space-y-1">
          <Item to="/" end icon={LayoutDashboard}>Overview</Item>
          <Item to="/cluster" icon={Server}>Cluster</Item>
        </ul>

        <div>
          <h2 id="nav-experiments" className="px-3 pb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            Experiments
          </h2>
          <ul aria-labelledby="nav-experiments" className="space-y-1">
            {EXPERIMENTS.map((experiment) => (
              <li key={experiment.slug}>
                <NavLink to={`/experiments/${experiment.slug}`} className={itemClass}>
                  <LabBadge lab={experiment.lab} />
                  <span className="truncate">{experiment.title}</span>
                </NavLink>
              </li>
            ))}
          </ul>
        </div>

        <ul className="space-y-1">
          <Item to="/scenarios" icon={GitBranch}>Scenarios</Item>
          <Item to="/timeline" icon={ListOrdered}>Timeline</Item>
          {showMonitoring && <Item to="/monitoring" icon={Activity}>Monitoring</Item>}
          <Item to="/about" icon={Info}>About</Item>
        </ul>
      </nav>
    </aside>
  )
}
