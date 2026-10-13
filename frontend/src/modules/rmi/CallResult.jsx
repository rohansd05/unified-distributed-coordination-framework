import { CheckCircle2, XCircle } from 'lucide-react'
import {
  exactNanos,
  failureKindDescription,
  failureKindLabel,
  formatBytes,
  formatNanos,
  formatRatio,
  methodLabel,
  outcomeLabel,
  pathLabel,
  stageLabel,
} from './labels'

/**
 * A value the backend sent as null: the visible text "n/a", with the reason as a tooltip and
 * for screen readers. Never 0, never left out (R7).
 */
export function NotAvailable({ reason }) {
  return (
    <span title={reason} data-testid="not-available" className="text-muted-foreground">
      n/a<span className="sr-only"> ({reason})</span>
    </span>
  )
}

/** A figure, or NotAvailable when the backend sent null. */
function Figure({ text, exact, reason, mono }) {
  if (text === null || text === undefined) return <NotAvailable reason={reason} />
  return <span title={exact ?? undefined} className={mono ? 'font-mono' : 'tabular-nums'}>{text}</span>
}

function Row({ label, children, note }) {
  return (
    <div className="grid gap-1 py-1.5 sm:grid-cols-[11rem_1fr] sm:gap-3">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0 break-words">
        {children}
        {note && <p className="mt-0.5 text-xs text-muted-foreground">{note}</p>}
      </dd>
    </div>
  )
}

/** Why a field of this call is null, in a few words. */
function reasons(call) {
  const local = call.path === 'LOCAL'
  const lookupFailed = call.failureStage === 'LOOKUP'
  const failed = call.outcome !== 'SUCCESS'
  return {
    lookup: 'The registry lookup failed',
    roundTrip: 'No round trip: the call did not complete',
    timeToFailure: 'The call did not fail',
    argumentBytes: local ? 'Nothing is marshalled on a direct call' : 'Nothing was sent: the registry lookup failed',
    resultBytes: local ? 'Nothing is marshalled on a direct call' : 'No reply came back',
    stub: 'No stub: the registry lookup failed',
    send: lookupFailed ? 'Nothing was sent: the registry lookup failed' : 'Not recorded',
    reply: failed ? 'No reply came back' : 'Not recorded',
  }
}

/** The reply's own fields for its method, as the backend sent them. */
function ReplyFields({ reply }) {
  if (!reply) return null
  const rows = []
  if (reply.method === 'NODE_INFO') {
    rows.push(['Registry port', <span key="p" className="font-mono">{reply.registryPort}</span>])
    rows.push(['Capacity profile', reply.capacityProfile])
    rows.push(['Uptime', reply.uptimeMillis === null ? null : `${reply.uptimeMillis} ms`])
    rows.push(['Java version', reply.javaVersion])
  }
  if (reply.method === 'ECHO') {
    rows.push(['Text returned', reply.text === '' ? '(empty)' : reply.text])
  }
  if (reply.method === 'COMPUTE_HASH') {
    rows.push(['Rounds', reply.rounds])
    rows.push(['Seed', reply.seed])
    rows.push(['Digest', <span key="d" className="break-all font-mono text-xs">{reply.digestHex}</span>])
    rows.push(['Hashing time on the node', formatNanos(reply.serverNanos)])
  }
  return rows.map(([label, value]) => (
    <Row key={label} label={label}>
      {value === null || value === undefined ? <NotAvailable reason="Not sent for this method" /> : value}
    </Row>
  ))
}

/**
 * One call (RmiCallDto) exactly as the backend reported it: outcome; for a success the reply,
 * stub class, times, sizes; for a remote failure the stage, kind (described by the overview),
 * exception class and message as plain text, and the time to failure; and the Lamport values.
 * Every numeric field is always shown, as "n/a" when it is null.
 *
 * @param {object} props
 * @param {object} props.call an RmiCallDto
 * @param {object} [props.overview] for the failure descriptions and the two basis texts
 */
export function CallResult({ call, overview }) {
  const ok = call.outcome === 'SUCCESS'
  const why = reasons(call)
  const description = ok ? null : failureKindDescription(overview, call.failureKind) ?? call.failureDescription
  return (
    <div data-testid="call-result" className="space-y-2 text-sm">
      <p className="flex flex-wrap items-center gap-2 font-medium">
        {ok
          ? <CheckCircle2 aria-hidden="true" className="size-4 text-success" />
          : <XCircle aria-hidden="true" className="size-4 text-destructive" />}
        <span>{outcomeLabel(call.outcome)}:</span>
        <span>{methodLabel(call.method)} from node {call.fromNode} to node {call.targetNode}</span>
        <span className="font-normal text-muted-foreground">{pathLabel(call.path)}, call <span className="font-mono">{call.id}</span></span>
      </p>
      <dl className="divide-y divide-border/60">
        {ok ? (
          <Row label="Result">{call.resultSummary}</Row>
        ) : (
          <>
            <Row label="Failed at">the {stageLabel(call.failureStage)}</Row>
            <Row label="Failure kind" note={description}>{failureKindLabel(call.failureKind)}</Row>
            <Row label="Exception">
              <span className="break-all font-mono text-xs">{call.failureClass}</span>
              {call.failureCauseClass && (
                <span className="block break-all font-mono text-xs text-muted-foreground">caused by {call.failureCauseClass}</span>
              )}
            </Row>
            <Row label="Message">
              {call.failureMessage === null
                ? <NotAvailable reason="The exception had no message" />
                : <span data-testid="failure-message" className="whitespace-pre-wrap break-words">{call.failureMessage}</span>}
            </Row>
          </>
        )}
        <Row label="Arguments">{call.argumentSummary}</Row>
        <Row label="Registry lookup" note={overview?.lookupBasis}>
          <Figure text={formatNanos(call.lookupNanos)} exact={exactNanos(call.lookupNanos)} reason={why.lookup} />
        </Row>
        <Row label="Round trip">
          <Figure text={formatNanos(call.roundTripNanos)} exact={exactNanos(call.roundTripNanos)} reason={why.roundTrip} />
        </Row>
        <Row label="Time to failure">
          <Figure text={formatNanos(call.timeToFailureNanos)} exact={exactNanos(call.timeToFailureNanos)} reason={why.timeToFailure} />
        </Row>
        <Row label="Arguments marshalled" note={call.payloadSizeBasis ?? overview?.payloadSizeBasis}>
          <Figure text={formatBytes(call.argumentBytes)} reason={why.argumentBytes} />
        </Row>
        <Row label="Reply marshalled">
          <Figure text={formatBytes(call.resultBytes)} reason={why.resultBytes} />
        </Row>
        <Row label="Stub class">
          <Figure text={call.stubClassName} reason={why.stub} mono />
        </Row>
        <Row label="Lamport time">
          <span className="flex flex-wrap gap-x-4 gap-y-1">
            <span>at send <Figure text={call.senderLamportAtSend} reason={why.send} mono /></span>
            <span>reply <Figure text={call.replyLamport} reason={why.reply} mono /></span>
            <span>after reply <Figure text={call.senderLamportAfterReply} reason={why.reply} mono /></span>
          </span>
        </Row>
        {ok && <ReplyFields reply={call.reply} />}
      </dl>
    </div>
  )
}

/**
 * A local-versus-remote comparison (ComparisonDto) as measured: both times, the overhead (which
 * can be negative) and the ratio (which can be below 1), never corrected, always with the
 * backend's note. Each half opens as a full CallResult.
 */
export function ComparisonResult({ comparison, overview }) {
  const digests = comparison.digestsMatch === null ? null : comparison.digestsMatch ? 'Yes, identical' : 'No, they differ'
  const note = comparison.note ?? overview?.comparisonNote
  return (
    <div data-testid="comparison-result" className="space-y-3 text-sm">
      <p className="font-medium">
        Comparison <span className="font-mono">{comparison.id}</span>: {methodLabel(comparison.method)} on node {comparison.targetNode},
        called from node {comparison.fromNode}
      </p>
      <dl className="divide-y divide-border/60">
        <Row label="Direct call">
          <Figure text={formatNanos(comparison.localNanos)} exact={exactNanos(comparison.localNanos)} reason="The direct call failed" />
        </Row>
        <Row label="Through RMI">
          <Figure text={formatNanos(comparison.remoteNanos)} exact={exactNanos(comparison.remoteNanos)} reason="The remote call failed" />
        </Row>
        <Row label="Overhead (remote minus direct)">
          <span data-testid="comparison-overhead">
            <Figure text={formatNanos(comparison.overheadNanos)} exact={exactNanos(comparison.overheadNanos)}
              reason="Needs both sides to succeed" />
          </span>
        </Row>
        <Row label="Ratio (remote over direct)">
          <span data-testid="comparison-ratio">
            <Figure text={formatRatio(comparison.ratio)} exact={comparison.ratio === null ? null : String(comparison.ratio)}
              reason="Needs both sides to succeed and a direct time above 0" />
          </span>
        </Row>
        <Row label="Digests match">
          <Figure text={digests} reason="Compared for computeHash only, when both sides succeed" />
        </Row>
      </dl>
      {note && <p data-testid="comparison-note" className="rounded-md border-l-4 border-primary bg-background/60 px-3 py-2 text-xs">{note}</p>}
      {[['Direct call', comparison.local], ['Through RMI', comparison.remote]].map(([label, half]) => half && (
        <details key={label} className="rounded-md border px-3 py-2">
          <summary className="cursor-pointer text-sm font-medium">{label}: every figure</summary>
          <div className="mt-2"><CallResult call={half} overview={overview} /></div>
        </details>
      ))}
    </div>
  )
}
