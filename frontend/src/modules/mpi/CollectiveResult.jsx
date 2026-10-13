import { AlertTriangle, Ban, CheckCircle2, XCircle } from 'lucide-react'
import { NotAvailable } from '@/modules/rmi/CallResult'
import {
  directionLabel,
  exactNanos,
  failureKindLabel,
  formatBytes,
  formatNanos,
  operationLabel,
  outcomeLabel,
  refusalLabel,
  rootSourceLabel,
  shortChecksum,
  stageLabel,
  transferStatusLabel,
} from './labels'
import { peerOf, rankRows } from './mpiModel'

/** What totalNanos is (E9a), shown beside it wherever it appears. */
export const TOTAL_TIME_BASIS = 'Measured on the root from just before the first remote transfer to just after the last: '
  + 'one measurement, not a sum of the transfers and not a round trip.'

/** A figure, or NotAvailable when the backend sent null. */
function Figure({ text, exact, reason, mono }) {
  if (text === null || text === undefined) return <NotAvailable reason={reason} />
  return <span title={exact ?? undefined} className={mono ? 'font-mono' : 'tabular-nums'}>{text}</span>
}

function Checksum({ value, reason }) {
  if (typeof value !== 'string') return <NotAvailable reason={reason} />
  return (
    <span title={value} className="font-mono text-xs">
      {shortChecksum(value)}<span className="sr-only"> (full checksum {value})</span>
    </span>
  )
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

const OUTCOME_STYLE = {
  COMPLETE: { Icon: CheckCircle2, className: 'border-success/50 bg-success/10 text-success' },
  PARTIAL: { Icon: AlertTriangle, className: 'border-warning/60 bg-warning/10 text-warning' },
  FAILED: { Icon: XCircle, className: 'border-destructive/50 bg-destructive/10 text-destructive' },
  REFUSED: { Icon: Ban, className: 'border-border bg-muted/30 text-foreground' },
}

/** The outcome as an icon plus a word in a bordered badge (colour is never the only signal). */
export function OutcomeBadge({ outcome }) {
  const { Icon, className } = OUTCOME_STYLE[outcome] ?? OUTCOME_STYLE.REFUSED
  return (
    <span data-testid="outcome-badge" data-outcome={outcome}
      className={`inline-flex items-center gap-1 rounded border px-1.5 py-0.5 text-xs font-medium ${className}`}>
      <Icon aria-hidden="true" className="size-3.5" />
      {outcomeLabel(outcome)}
    </span>
  )
}

/** Why a field of this transfer is null, in a few words. */
function reasons(transfer) {
  const local = transfer.status === 'LOCAL'
  const notAttempted = transfer.status === 'NOT_ATTEMPTED'
  const failed = transfer.status === 'FAILED'
  const lookupFailed = failed && transfer.failureStage === 'LOOKUP'
  const localText = 'The root\'s own part never crosses the network'
  const notAttemptedText = 'Not attempted: the deadline had passed'
  const pick = (otherwise) => (local ? localText : notAttempted ? notAttemptedText : otherwise)
  return {
    latency: pick(failed ? 'No latency: the transfer did not complete' : 'Not measured'),
    timeToFailure: 'The transfer did not fail',
    request: pick(lookupFailed ? 'Nothing was sent: the registry lookup failed' : 'Not recorded'),
    reply: pick(failed ? 'No reply came back' : 'Not recorded'),
    reported: pick(failed ? 'No reply came back' : 'Not reported'),
    send: pick(lookupFailed ? 'Nothing was sent: the registry lookup failed' : 'Not recorded'),
    replyLamport: pick(failed ? 'No reply came back' : 'Not recorded'),
  }
}

function verifiedText(verified) {
  if (verified === true) return 'verified'
  if (verified === false) return 'mismatch'
  return 'not checked'
}

function TransferRows({ transfer }) {
  const why = reasons(transfer)
  const failed = transfer.status === 'FAILED'
  const notAttempted = transfer.status === 'NOT_ATTEMPTED'
  const latency = transfer.status === 'DELIVERED' ? transfer.latencyNanos : null
  const timeToFailure = failed ? transfer.timeToFailureNanos : null
  return (
    <>
      <tr data-testid="transfer-row" data-status={transfer.status} className={failed ? 'bg-destructive/5' : undefined}>
        <td className="px-3 py-1.5 font-mono">{transfer.rank}</td>
        <td className="px-2 py-1.5">Node {peerOf(transfer)}</td>
        <td className="px-2 py-1.5">{directionLabel(transfer.direction)}</td>
        <td className="px-2 py-1.5">{transferStatusLabel(transfer.status)}</td>
        <td className="px-2 py-1.5 tabular-nums">{transfer.offset}, {transfer.elementCount}</td>
        <td className="px-2 py-1.5" data-testid="latency-cell">
          <Figure text={formatNanos(latency)} exact={exactNanos(latency)} reason={why.latency} />
        </td>
        <td className="px-2 py-1.5" data-testid="time-to-failure-cell">
          <Figure text={formatNanos(timeToFailure)} exact={exactNanos(timeToFailure)} reason={why.timeToFailure} />
        </td>
        <td className="px-2 py-1.5"><Figure text={formatBytes(transfer.requestBytes)} reason={why.request} /></td>
        <td className="px-2 py-1.5"><Figure text={formatBytes(transfer.replyBytes)} reason={why.reply} /></td>
        <td className="px-2 py-1.5">
          <span className="block">expected <Checksum value={transfer.expectedChecksum} reason="Not computed" /></span>
          <span className="block">reported <Checksum value={transfer.reportedChecksum} reason={why.reported} /></span>
          <span className="block text-xs text-muted-foreground" data-testid="verified">{verifiedText(transfer.verified)}</span>
        </td>
        <td className="px-3 py-1.5 text-xs">
          <span className="block">send <Figure text={transfer.senderLamportAtSend} reason={why.send} mono /></span>
          <span className="block">reply <Figure text={transfer.replyLamport} reason={why.replyLamport} mono /></span>
          <span className="block">after <Figure text={transfer.lamportAfterReply} reason={why.replyLamport} mono /></span>
        </td>
      </tr>
      {failed && (
        <tr data-testid="failure-detail" className="bg-destructive/5">
          <td colSpan={11} className="px-3 pb-2 text-xs">
            <p>
              Failed at the {stageLabel(transfer.failureStage)} with {failureKindLabel(transfer.failureKind).toLowerCase()}
              {transfer.failureDescription ? `: ${transfer.failureDescription}` : '.'}
            </p>
            <p className="break-all font-mono text-muted-foreground">
              {transfer.failureClass}{transfer.failureCauseClass ? ` caused by ${transfer.failureCauseClass}` : ''}
            </p>
            <p>
              Message:{' '}
              {transfer.failureMessage === null || transfer.failureMessage === undefined
                ? <NotAvailable reason="The exception had no message" />
                : <span className="whitespace-pre-wrap break-words">{transfer.failureMessage}</span>}
            </p>
          </td>
        </tr>
      )}
      {notAttempted && (
        <tr data-testid="not-attempted-detail">
          <td colSpan={11} className="px-3 pb-2 text-xs text-muted-foreground">
            Not attempted:{' '}
            {transfer.notAttemptedReason ? transfer.notAttemptedReason : <NotAvailable reason="No reason was sent" />}
          </td>
        </tr>
      )}
    </>
  )
}

function TransferTable({ collective }) {
  const transfers = collective.transfers ?? []
  if (transfers.length === 0) return null
  const hasLatency = transfers.some((t) => t.status === 'DELIVERED' && typeof t.latencyNanos === 'number')
  const hasSize = transfers.some((t) => typeof t.requestBytes === 'number' || typeof t.replyBytes === 'number')
  return (
    <div className="space-y-1">
      <h4 className="text-sm font-semibold">Transfers, in rank order</h4>
      <div className="overflow-x-auto rounded-lg border bg-card">
        <table data-testid="transfer-table" className="w-full min-w-[60rem] text-left text-sm">
          <thead className="border-b text-xs text-muted-foreground">
            <tr>
              <th scope="col" className="px-3 py-2 font-medium">Rank</th>
              <th scope="col" className="px-2 py-2 font-medium">Node</th>
              <th scope="col" className="px-2 py-2 font-medium">Direction</th>
              <th scope="col" className="px-2 py-2 font-medium">Status</th>
              <th scope="col" className="px-2 py-2 font-medium">Offset, elements</th>
              <th scope="col" className="px-2 py-2 font-medium">Latency</th>
              <th scope="col" className="px-2 py-2 font-medium">Time to failure</th>
              <th scope="col" className="px-2 py-2 font-medium">Request</th>
              <th scope="col" className="px-2 py-2 font-medium">Reply</th>
              <th scope="col" className="px-2 py-2 font-medium">Checksum</th>
              <th scope="col" className="px-3 py-2 font-medium">Lamport</th>
            </tr>
          </thead>
          <tbody className="divide-y">
            {transfers.map((transfer) => <TransferRows key={transfer.rank} transfer={transfer} />)}
          </tbody>
        </table>
      </div>
      <div className="space-y-1 text-xs text-muted-foreground">
        {hasLatency && collective.latencyBasis && (
          <p data-testid="latency-basis"><span className="font-medium text-foreground">Latency.</span> {collective.latencyBasis}</p>
        )}
        {hasSize && collective.payloadSizeBasis && (
          <p data-testid="size-basis"><span className="font-medium text-foreground">Sizes.</span> {collective.payloadSizeBasis}</p>
        )}
        <p>A time to failure is how long a failed transfer took to fail; it is not a latency.</p>
      </div>
    </div>
  )
}

const ROLE_TEXT = { root: 'root', participant: 'participant', skipped: 'skipped: crashed at the start' }

function RankTable({ collective }) {
  const rows = rankRows(collective)
  if (rows.length === 0) return null
  return (
    <div className="space-y-1">
      <h4 className="text-sm font-semibold">Ranks</h4>
      <div className="overflow-x-auto rounded-lg border bg-card">
        <table data-testid="rank-table" className="w-full text-left text-sm">
          <thead className="border-b text-xs text-muted-foreground">
            <tr>
              <th scope="col" className="px-3 py-2 font-medium">Rank</th>
              <th scope="col" className="px-2 py-2 font-medium">Node</th>
              <th scope="col" className="px-3 py-2 font-medium">Role</th>
            </tr>
          </thead>
          <tbody className="divide-y">
            {rows.map((row) => (
              <tr key={row.key} data-testid="rank-row" data-role={row.role}>
                <td className="px-3 py-1.5 font-mono">
                  {row.rank === null ? <NotAvailable reason="A skipped node has no rank" /> : row.rank}
                </td>
                <td className="px-2 py-1.5">Node {row.nodeId}</td>
                <td className="px-3 py-1.5">{ROLE_TEXT[row.role]}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function yesNo(value) {
  if (value === true) return 'yes'
  if (value === false) return 'no'
  return null
}

function Verification({ verification, outcome }) {
  return (
    <div className="space-y-1" data-testid="verification">
      <h4 className="text-sm font-semibold">Verification</h4>
      {verification ? (
        <dl className="divide-y divide-border/60 text-sm">
          <Row label="Passed">
            {verification.passed === null
              ? <NotAvailable reason="Unknown: a part could not be checked, or nothing was checked" />
              : verification.passed ? 'Passed' : 'Failed: a checksum did not match'}
          </Row>
          <Row label="Checked, matched">
            <span className="tabular-nums">
              {verification.checked} checked, {verification.matched} matched, {verification.mismatched} mismatched, {verification.unknown} unknown
            </span>
          </Row>
          <Row label="Whole vector matches">
            <Figure text={yesNo(verification.wholeVectorMatches)}
              reason="Not compared: a broadcast has no whole-vector check, and scatter or gather need every part" />
          </Row>
        </dl>
      ) : (
        <p className="text-sm text-muted-foreground">
          <NotAvailable reason={outcome === 'REFUSED' ? 'Refused: nothing was sent, so nothing was checked' : 'Not verified'} />
          {outcome === 'REFUSED' ? ' Nothing was sent, so nothing was checked.' : ''}
        </p>
      )}
    </div>
  )
}

function GatheredBlocks({ blocks }) {
  if (!Array.isArray(blocks) || blocks.length === 0) return null
  return (
    <div className="space-y-1">
      <h4 className="text-sm font-semibold">Gathered blocks</h4>
      <p className="text-xs text-muted-foreground">Only checksums travel back to the page; the values stay on the root.</p>
      <ul className="divide-y rounded-lg border bg-card text-sm">
        {blocks.map((block) => (
          <li key={block.rank} data-testid="gathered-block" data-hole={block.hole ? 'true' : 'false'}
            className="flex flex-wrap items-baseline gap-x-3 px-3 py-1.5">
            <span>Rank <span className="font-mono">{block.rank}</span>, node {block.nodeId}</span>
            <span className="tabular-nums text-muted-foreground">offset {block.offset}, {block.length} elements</span>
            {block.hole
              ? <span className="font-medium text-destructive">hole: nothing arrived</span>
              : <Checksum value={block.checksum} reason="No checksum" />}
          </li>
        ))}
      </ul>
    </div>
  )
}

/**
 * One collective (CollectiveDto) exactly as the backend reported it: the outcome (with the
 * refusal's reason and message), root, seed, input checksum, total time, the ranks, every
 * transfer with its figures (latency for DELIVERED only, time to failure for FAILED only), the
 * verification and, for a gather, the gathered blocks' checksums. Every null is "n/a" with its
 * reason, never 0.
 *
 * @param {object} props
 * @param {object} props.collective a CollectiveDto
 */
export function CollectiveResult({ collective }) {
  const refused = collective.outcome === 'REFUSED'
  return (
    <div data-testid="collective-result" className="space-y-4 text-sm">
      <p className="flex flex-wrap items-center gap-2 font-medium">
        <OutcomeBadge outcome={collective.outcome} />
        <span>{operationLabel(collective.operation)} of {collective.count} values from node {collective.rootNodeId}</span>
        <span className="font-normal text-muted-foreground">collective <span className="font-mono">{collective.id}</span></span>
      </p>
      {collective.refusal && (
        <p data-testid="refusal" className="rounded-md border-l-4 border-muted-foreground bg-background/60 px-3 py-2">
          <span className="font-medium">{refusalLabel(collective.refusal.reason)}.</span> {collective.refusal.message}
        </p>
      )}
      <dl className="divide-y divide-border/60">
        <Row label="Root">
          node {collective.rootNodeId}, chosen as {rootSourceLabel(collective.rootSource)}; rank{' '}
          <Figure text={collective.rootRank} reason="Refused before ranks were used" mono />
        </Row>
        <Row label="Seed"><span className="font-mono">{collective.seed}</span></Row>
        <Row label="Input checksum">
          <Checksum value={collective.inputChecksum} reason={refused ? 'Refused: no input was generated' : 'Not computed'} />
        </Row>
        <Row label="Total time" note={collective.totalNanos === null ? null : TOTAL_TIME_BASIS}>
          <span data-testid="total-time">
            <Figure text={formatNanos(collective.totalNanos)} exact={exactNanos(collective.totalNanos)}
              reason={refused ? 'Refused: nothing was sent' : 'No remote transfer was attempted'} />
          </span>
        </Row>
        <Row label="Deadline"><span className="tabular-nums">{collective.deadlineMillis} ms</span></Row>
      </dl>
      <RankTable collective={collective} />
      <TransferTable collective={collective} />
      <Verification verification={collective.verification} outcome={collective.outcome} />
      <GatheredBlocks blocks={collective.gathered} />
    </div>
  )
}
