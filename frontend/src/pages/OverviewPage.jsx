import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useCluster } from '@/hooks/useCluster'
import { useEventStream } from '@/hooks/useEventStream'
import { useToast } from '@/hooks/use-toast'
import { api as defaultApi } from '@/services/api'
import { getConfig } from '@/lib/config'
import { isNodeCrashed } from '@/lib/clusterStatus'
import { ClusterGraph } from '@/components/cluster/ClusterGraph'
import { EventStream } from '@/components/events/EventStream'
import { Button } from '@/components/ui/button'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'

export function OverviewPage({ api = defaultApi }) {
  const { cluster, loading: clusterLoading, error: clusterError, refresh } = useCluster()
  const { events, loading: eventsLoading, error: eventsError } = useEventStream({ api })
  const { toast } = useToast()

  const [selectedNodeId, setSelectedNodeId] = useState(1)
  const [pendingAction, setPendingAction] = useState(null)
  const [resetDialogOpen, setResetDialogOpen] = useState(false)

  const nodes = useMemo(() => cluster?.nodes || [], [cluster?.nodes])
  const upCount = cluster?.upCount ?? 0
  const size = cluster?.size ?? nodes.length

  // Find selected node or fallback to first node
  const selectedNode = useMemo(() => {
    if (!nodes.length) return null
    return nodes.find((n) => n.id === selectedNodeId) || nodes[0]
  }, [nodes, selectedNodeId])

  const handleCrash = async (nodeId) => {
    setPendingAction(`crash-${nodeId}`)
    const targetNode = nodes.find((n) => n.id === nodeId)
    const running = targetNode?.runningServices || []
    try {
      await api.crashNode(nodeId)
      const description = running.length > 0
        ? `Stopped services: ${running.join(', ')}.`
        : 'No services were running on it yet.'
      toast({
        title: `Node ${nodeId} crashed`,
        description,
      })
    } catch (err) {
      toast({
        variant: 'destructive',
        title: 'Crash failed',
        description: err.detail || err.message || `Failed to crash node ${nodeId}`,
      })
    } finally {
      setPendingAction(null)
    }
  }

  const handleRecover = async (nodeId) => {
    setPendingAction(`recover-${nodeId}`)
    try {
      await api.recoverNode(nodeId)
      toast({
        title: `Node ${nodeId} recovered`,
        description: `All sockets re-bound on node ${nodeId}.`,
      })
    } catch (err) {
      toast({
        variant: 'destructive',
        title: 'Recovery failed',
        description: err.detail || err.message || `Failed to recover node ${nodeId}`,
      })
    } finally {
      setPendingAction(null)
    }
  }

  const handleReset = async () => {
    setPendingAction('reset')
    try {
      await api.resetCluster()
      toast({
        title: 'Cluster reset',
        description: 'All nodes recovered, Lamport clocks restarted, event history cleared.',
      })
      setResetDialogOpen(false)
    } catch (err) {
      toast({
        variant: 'destructive',
        title: 'Reset failed',
        description: err.detail || err.message || 'Failed to reset cluster',
      })
    } finally {
      setPendingAction(null)
    }
  }

  const isSelectedCrashed = isNodeCrashed(selectedNode)
  const isSelectedPending =
    pendingAction === `crash-${selectedNode?.id}` ||
    pendingAction === `recover-${selectedNode?.id}`
  const selectedThreads = selectedNode?.capacity?.threads ?? 0

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between border-b border-border/60 pb-5">
        <div>
          <h1 className="text-2xl font-semibold text-foreground tracking-tight">Overview</h1>
          <p className="text-sm text-muted-foreground">
            Shared distributed coordination cluster hosting experiments on real sockets
          </p>
        </div>

        <div className="flex items-center gap-3">
          <span
            data-testid="health-statement"
            className="inline-flex items-center gap-2 rounded-full border border-border bg-card px-3 py-1 text-xs font-medium text-foreground"
          >
            <span
              className={`h-2 w-2 rounded-full ${upCount === size && size > 0 ? 'bg-success' : 'bg-destructive'}`}
              aria-hidden="true"
            />
            {clusterLoading ? '— of — nodes up' : `${upCount} of ${size} nodes up`}
          </span>

          <AlertDialog open={resetDialogOpen} onOpenChange={setResetDialogOpen}>
            <AlertDialogTrigger asChild>
              <Button variant="outline" size="sm" disabled={pendingAction === 'reset' || clusterLoading}>
                {pendingAction === 'reset' ? 'Resetting…' : 'Reset cluster'}
              </Button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Reset cluster to clean slate?</AlertDialogTitle>
                <AlertDialogDescription>
                  This recovers every node, restarts all Lamport clocks to 0 (the reset event is stamped 1), and clears the event history. All experiment modules will return to their idle state.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel disabled={pendingAction === 'reset'}>Cancel</AlertDialogCancel>
                <AlertDialogAction
                  onClick={(e) => {
                    e.preventDefault()
                    handleReset()
                  }}
                  disabled={pendingAction === 'reset'}
                  className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                >
                  {pendingAction === 'reset' ? 'Resetting…' : 'Reset cluster'}
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>

          <Link
            to="/cluster"
            className="inline-flex h-9 items-center justify-center rounded-md border border-input bg-background px-3 text-xs font-medium text-foreground hover:bg-accent hover:text-accent-foreground"
          >
            View cluster details
          </Link>
        </div>
      </div>

      {clusterLoading && (
        <div data-testid="overview-loading" className="grid gap-6 md:grid-cols-5">
          <div className="md:col-span-3 aspect-square animate-pulse rounded-lg bg-card/60" />
          <div className="md:col-span-2 h-80 animate-pulse rounded-lg bg-card/60" />
        </div>
      )}

      {!clusterLoading && clusterError && (
        <div data-testid="overview-error" className="rounded-lg border border-destructive/40 bg-card p-6 text-center">
          <h2 className="text-lg font-semibold text-foreground">Cluster connection unavailable</h2>
          <p className="mt-2 text-sm text-destructive font-medium">
            Can&apos;t reach the backend at {getConfig().apiBaseUrl || 'http://localhost:8080'}.
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            Start it with: <code className="font-mono text-foreground font-semibold">cd backend; .\mvnw.cmd spring-boot:run</code>
          </p>
          <Button onClick={refresh} className="mt-4" variant="outline" size="sm">
            Try again
          </Button>
        </div>
      )}

      {!clusterLoading && !clusterError && (
        <div className="grid gap-6 md:grid-cols-5 items-start">
          {/* SVG Topology Graph (60% width on desktop) */}
          <div className="md:col-span-3 rounded-lg border border-border bg-card p-4 flex flex-col items-center justify-center">
            <ClusterGraph
              nodes={nodes}
              selectedNodeId={selectedNode?.id}
              onSelectNode={setSelectedNodeId}
            />
          </div>

          {/* Selected Node Details Inspector (40% width on desktop) */}
          <div className="md:col-span-2 rounded-lg border border-border bg-card p-5 space-y-4" data-testid="node-inspector">
            <div className="flex items-center justify-between border-b border-border pb-3">
              <div>
                <h2 className="text-base font-semibold text-foreground">
                  Node {selectedNode?.id || '—'}
                </h2>
                <p className="text-xs text-muted-foreground">
                  Capacity: {selectedNode?.capacity?.name || '—'} ({selectedThreads} {selectedThreads === 1 ? 'worker thread' : 'worker threads'}, {selectedNode?.capacity?.workMultiplier || 1}x work)
                </p>
              </div>
              <span
                className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-medium ${
                  isSelectedCrashed
                    ? 'bg-destructive/15 text-destructive border border-destructive/30'
                    : 'bg-success/15 text-success border border-success/30'
                }`}
              >
                <span
                  className={`h-1.5 w-1.5 rounded-full ${isSelectedCrashed ? 'bg-destructive' : 'bg-success'}`}
                />
                {isSelectedCrashed ? 'Crashed' : 'Up'}
              </span>
            </div>

            {/* Running Services */}
            <div>
              <span className="text-xs font-medium text-muted-foreground">Running services</span>
              <p className="mt-0.5 text-xs text-foreground">
                {selectedNode?.runningServices && selectedNode.runningServices.length > 0
                  ? selectedNode.runningServices.join(', ')
                  : 'None running yet'}
              </p>
            </div>

            {/* Roles (only rendered if present) */}
            {selectedNode?.roles && selectedNode.roles.length > 0 && (
              <div>
                <span className="text-xs font-medium text-muted-foreground">Roles</span>
                <p className="mt-0.5 text-xs font-medium text-success">
                  {selectedNode.roles.join(', ')}
                </p>
              </div>
            )}

            {/* Real Internal Ports */}
            <div>
              <span className="text-xs font-medium text-muted-foreground">Internal socket ports</span>
              <div className="mt-1.5 grid grid-cols-3 gap-2">
                <div className="rounded border border-border/80 bg-navy/20 p-2">
                  <span className="block text-[10px] text-muted-foreground">RMI</span>
                  <span className="font-mono text-xs text-foreground">{selectedNode?.ports?.rmi || '—'}</span>
                </div>
                <div className="rounded border border-border/80 bg-navy/20 p-2">
                  <span className="block text-[10px] text-muted-foreground">Clock UDP</span>
                  <span className="font-mono text-xs text-foreground">{selectedNode?.ports?.clock || '—'}</span>
                </div>
                <div className="rounded border border-border/80 bg-navy/20 p-2">
                  <span className="block text-[10px] text-muted-foreground">Election UDP</span>
                  <span className="font-mono text-xs text-foreground">{selectedNode?.ports?.election || '—'}</span>
                </div>
                <div className="rounded border border-border/80 bg-navy/20 p-2">
                  <span className="block text-[10px] text-muted-foreground">Replication TCP</span>
                  <span className="font-mono text-xs text-foreground">{selectedNode?.ports?.replication || '—'}</span>
                </div>
                <div className="rounded border border-border/80 bg-navy/20 p-2">
                  <span className="block text-[10px] text-muted-foreground">Requests TCP</span>
                  <span className="font-mono text-xs text-foreground">{selectedNode?.ports?.requests || '—'}</span>
                </div>
                <div className="rounded border border-border/80 bg-navy/20 p-2">
                  <span className="block text-[10px] text-muted-foreground">MapReduce TCP</span>
                  <span className="font-mono text-xs text-foreground">{selectedNode?.ports?.mapreduce || '—'}</span>
                </div>
              </div>
            </div>

            {/* Action button */}
            <div className="pt-2">
              {isSelectedCrashed ? (
                <Button
                  variant="default"
                  className="w-full bg-primary hover:bg-primary/90 text-primary-foreground text-xs"
                  disabled={isSelectedPending}
                  onClick={() => handleRecover(selectedNode?.id)}
                >
                  {isSelectedPending ? `Recovering node ${selectedNode?.id}…` : `Recover node ${selectedNode?.id}`}
                </Button>
              ) : (
                <Button
                  variant="destructive"
                  className="w-full text-xs"
                  disabled={isSelectedPending}
                  onClick={() => handleCrash(selectedNode?.id)}
                >
                  {isSelectedPending ? `Crashing node ${selectedNode?.id}…` : `Crash node ${selectedNode?.id}`}
                </Button>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Live Event Stream */}
      <EventStream events={events} loading={eventsLoading} error={eventsError} />
    </div>
  )
}
