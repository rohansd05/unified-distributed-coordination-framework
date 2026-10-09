import { Menu, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useStomp } from '@/hooks/useStomp'
import { useCluster } from '@/hooks/useCluster'
import { cn } from '@/lib/utils'
import { isLeader } from '@/lib/clusterStatus'

/**
 * Status bar above every page.
 * Displays live WebSocket connection status, backend mode, cluster leader,
 * and up node count.
 */
export function TopBar({ menuOpen, onToggleMenu, menuButtonRef }) {
  const { status } = useStomp()
  const { info, cluster, loading, error } = useCluster()

  let connectionText = 'Connecting…'
  let dotClass = 'bg-muted-foreground'

  if (status === 'connected') {
    connectionText = 'Live'
    dotClass = 'bg-success'
  } else if (status === 'reconnecting') {
    connectionText = 'Reconnecting…'
    dotClass = 'bg-warning'
  } else if (status === 'connecting') {
    connectionText = 'Connecting…'
    dotClass = 'bg-muted-foreground'
  } else {
    // 'disconnected', 'error', or backend down
    connectionText = 'Backend unreachable'
    dotClass = 'bg-destructive'
  }

  // If cluster fetch failed with unreachable error while disconnected/connecting
  if (error && status !== 'connected') {
    connectionText = 'Backend unreachable'
    dotClass = 'bg-destructive'
  }

  const modeDisplay = loading || !info?.mode ? '—' : info.mode

  let leaderDisplay = '—'
  if (!loading) {
    const leaderNode = cluster?.nodes?.find(isLeader)
    leaderDisplay = leaderNode ? String(leaderNode.id) : 'None elected'
  }

  const nodesUpDisplay =
    loading || !cluster ? '—' : `${cluster.upCount}/${cluster.size}`

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
        <div className="flex items-baseline gap-2">
          <dt className="text-muted-foreground">Connection</dt>
          <dd className="flex items-center gap-1.5 font-mono">
            <span className={cn('size-2 rounded-full', dotClass)} aria-hidden="true" />
            <span>{connectionText}</span>
          </dd>
        </div>

        <div className="flex items-baseline gap-2">
          <dt className="text-muted-foreground">Mode</dt>
          <dd className="font-mono">{modeDisplay}</dd>
        </div>

        <div className="flex items-baseline gap-2">
          <dt className="text-muted-foreground">Leader</dt>
          <dd className="font-mono">{leaderDisplay}</dd>
        </div>

        <div className="flex items-baseline gap-2">
          <dt className="text-muted-foreground">Nodes up</dt>
          <dd className="font-mono">{nodesUpDisplay}</dd>
        </div>
      </dl>
    </header>
  )
}
