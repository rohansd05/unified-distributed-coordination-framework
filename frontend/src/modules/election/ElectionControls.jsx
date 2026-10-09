import { useId, useState } from 'react'
import { Button } from '@/components/ui/button'
import { isNodeCrashed, nodeActionLabel } from '@/lib/clusterStatus'
import { cn } from '@/lib/utils'
import { isRingOfOne } from './electionModel'
import { algorithmLabel, secondsFrom } from './labels'

const ALGORITHMS = [
  { value: 'BULLY', hint: 'Asks every higher node; the highest live node wins.' },
  { value: 'RING', hint: 'Passes a token round the ring, skipping dead nodes.' },
]

function ErrorAlert({ error }) {
  if (!error) {
    return null
  }
  return (
    <p role="alert" className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm">
      {error.title && <span className="font-medium">{error.title}: </span>}
      {error.detail || error.message || 'The backend did not answer.'}
    </p>
  )
}

/**
 * Start Bully or Ring from a node, and crash or recover any node through the shared cluster
 * API, with no confirmation step: crashing is the experiment. Errors (409 busy, 409 node down,
 * 400, 404) show as alerts with the backend's own words.
 *
 * @param {object} props
 * @param {object|null} props.overview
 * @param {object} props.api see createElectionApi
 * @param {(round: object) => void} props.onStarted called with the 202 round
 * @param {() => void} props.onChanged called after any action, to refresh
 * @param {(text: string) => void} props.onAnnounce
 */
export function ElectionControls({ overview, api, onStarted, onChanged, onAnnounce }) {
  // TODO(L1): replaced by the shared role provider in Phase 9A. Choosing the algorithm and the
  // starting node is this page's own control; it is not a shared role selector.
  const [algorithm, setAlgorithm] = useState('BULLY')
  const [nodeId, setNodeId] = useState(1)
  const [starting, setStarting] = useState(false)
  const [startError, setStartError] = useState(null)
  const [nodeError, setNodeError] = useState(null)
  const [nodeBusy, setNodeBusy] = useState(null)
  const nodeSelectId = useId()
  const nodes = Array.isArray(overview?.nodes) ? overview.nodes : []
  const busy = overview?.status === 'BUSY'
  const ringOfOne = algorithm === 'RING' && isRingOfOne(overview)
  const timeoutSeconds = secondsFrom(overview?.settings?.roundTimeoutMillis)

  async function start(event) {
    event.preventDefault()
    setStarting(true)
    setStartError(null)
    try {
      const round = await api.startElection({ algorithm, nodeId: Number(nodeId) })
      onStarted(round)
    } catch (err) {
      setStartError(err)
      onAnnounce(`The election did not start: ${err.detail || err.message}`)
    } finally {
      setStarting(false)
      onChanged()
    }
  }

  async function crashOrRecover(node) {
    const crashed = isNodeCrashed(node)
    setNodeBusy(node.nodeId)
    setNodeError(null)
    try {
      await (crashed ? api.recoverNode(node.nodeId) : api.crashNode(node.nodeId))
      onAnnounce(crashed ? `Node ${node.nodeId} recovered.` : `Node ${node.nodeId} crashed.`)
    } catch (err) {
      setNodeError(err)
    } finally {
      setNodeBusy(null)
      onChanged()
    }
  }

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
      <form aria-label="Start an election" onSubmit={start} className="space-y-4 rounded-lg border bg-card p-4">
        <fieldset className="space-y-2">
          <legend className="text-sm font-medium">Algorithm</legend>
          <div className="flex flex-wrap gap-2">
            {ALGORITHMS.map((option) => (
              <label key={option.value}
                className={cn('flex min-w-[9rem] flex-1 cursor-pointer gap-2 rounded-md border p-2 text-sm',
                  'focus-within:ring-2 focus-within:ring-ring', algorithm === option.value && 'border-primary bg-primary/10')}>
                <input type="radio" name="algorithm" value={option.value} checked={algorithm === option.value}
                  onChange={() => setAlgorithm(option.value)} className="mt-1 accent-primary" />
                <span>
                  <span className="font-medium">{algorithmLabel(option.value)}</span>
                  <span className="block text-xs text-muted-foreground">{option.hint}</span>
                </span>
              </label>
            ))}
          </div>
        </fieldset>
        <div className="space-y-1">
          <label htmlFor={nodeSelectId} className="text-sm font-medium">Start from node</label>
          <select id={nodeSelectId} value={nodeId} onChange={(e) => setNodeId(Number(e.target.value))}
            className="block w-full rounded-md border bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
            {nodes.map((node) => (
              <option key={node.nodeId} value={node.nodeId} disabled={isNodeCrashed(node)}>
                Node {node.nodeId}{isNodeCrashed(node) ? ' (crashed)' : ''}
              </option>
            ))}
          </select>
        </div>
        {ringOfOne && (
          <p className="rounded-md border border-warning/60 bg-warning/10 p-2 text-xs">
            Only one node is up. A Ring election cannot elect anyone; it will time out after {timeoutSeconds ?? '—'} s.
          </p>
        )}
        {busy && <p className="text-xs text-muted-foreground">An election is in progress. Wait for it to finish.</p>}
        <Button type="submit" disabled={starting || busy || nodes.length === 0}>
          {starting ? 'Starting…' : 'Start election'}
        </Button>
        <ErrorAlert error={startError} />
      </form>

      <div className="space-y-3 rounded-lg border bg-card p-4">
        <h3 className="text-sm font-medium">Crash or recover a node</h3>
        <p className="text-xs text-muted-foreground">
          Uses the cluster controls, so every experiment sees the same failure. No confirmation is asked.
        </p>
        <ul className="space-y-2">
          {nodes.map((node) => (
            <li key={node.nodeId} className="flex flex-wrap items-center justify-between gap-2 text-sm">
              <span>
                Node {node.nodeId}{' '}
                <span className={isNodeCrashed(node) ? 'text-destructive' : 'text-muted-foreground'}>
                  {isNodeCrashed(node) ? 'Crashed' : 'Up'}
                </span>
              </span>
              <Button type="button" size="sm" variant={isNodeCrashed(node) ? 'outline' : 'destructive'}
                disabled={nodeBusy === node.nodeId} onClick={() => crashOrRecover(node)}>
                {nodeActionLabel({ id: node.nodeId, status: node.status })}
              </Button>
            </li>
          ))}
        </ul>
        <ErrorAlert error={nodeError} />
      </div>
    </div>
  )
}
