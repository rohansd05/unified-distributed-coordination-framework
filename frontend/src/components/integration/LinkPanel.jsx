import { Link } from 'react-router-dom'
import { Check, Clock3, FlaskConical, Minus, TriangleAlert, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { EXPERIMENTS } from '@/lib/experiments'
import { evidenceHeadline } from './evidenceModel'
import { LINKS, SHORT_NAMES } from './integrationLinks'

const STATE_WORDS = {
  match: { word: 'Matches', Icon: Check, tone: 'text-success' },
  mismatch: { word: 'Differs', Icon: TriangleAlert, tone: 'text-warning' },
  idle: { word: 'Not taken yet', Icon: Minus, tone: 'text-muted-foreground' },
  'no-leader': { word: 'No leader', Icon: Minus, tone: 'text-muted-foreground' },
  unavailable: { word: 'Unreadable', Icon: TriangleAlert, tone: 'text-warning' },
  loading: { word: 'Reading…', Icon: Clock3, tone: 'text-muted-foreground' },
}

function Chip({ id, active = false }) {
  return (
    <span
      className={`inline-flex h-6 min-w-9 shrink-0 items-center justify-center rounded-full border border-primary px-2 text-xs font-semibold ${
        active ? 'bg-primary text-primary-foreground' : 'bg-secondary text-foreground'
      }`}
    >
      {id}
    </span>
  )
}

function EvidenceKind({ kind }) {
  return kind === 'live' ? (
    <span className="inline-flex items-center gap-1.5 text-xs font-medium text-success">
      <span className="h-1.5 w-1.5 rounded-full bg-success" aria-hidden="true" />
      Live
    </span>
  ) : (
    <span className="inline-flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
      <FlaskConical className="size-3.5" aria-hidden="true" />
      Verified by test
    </span>
  )
}

/** The six links as a list; each opens its panel. Shown while no link is selected. */
export function LinkList({ evidence, onSelect }) {
  return (
    <div className="space-y-3">
      <p className="text-sm text-muted-foreground">
        Six links make the ten experiments one system. Select a link here or on the map to see its live
        evidence.
      </p>
      <ul className="space-y-2">
        {LINKS.map((link) => (
          <li key={link.id}>
            <button
              type="button"
              onClick={() => onSelect(link.id)}
              className="flex w-full items-start gap-3 rounded-md border border-border bg-background/40 p-3 text-left hover:border-primary/60 hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <Chip id={link.id} />
              <span className="min-w-0 space-y-1">
                <span className="block text-sm font-medium text-foreground">{link.meaning}</span>
                <span className="block break-words text-xs text-muted-foreground">
                  {evidenceHeadline(evidence[link.id])}
                </span>
              </span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}

function RoleTable({ rows }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-xs">
        <caption className="sr-only">Who holds each role, and whether it is the leader</caption>
        <thead className="text-muted-foreground">
          <tr>
            <th scope="col" className="py-1 pr-2 font-medium">Role</th>
            <th scope="col" className="py-1 pr-2 font-medium">Holder</th>
            <th scope="col" className="py-1 font-medium">Matches leader</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const state = STATE_WORDS[row.state] || STATE_WORDS.loading
            return (
              <tr key={row.lab} className="border-t border-border/60 align-top" data-role-state={row.state}>
                <td className="py-1.5 pr-2">
                  <span className="block text-foreground">{row.role}</span>
                  <span className="text-muted-foreground">Exp {row.lab}</span>
                </td>
                <td className="py-1.5 pr-2 text-foreground">
                  {Number.isInteger(row.holder) ? `Node ${row.holder}` : '—'}
                </td>
                <td className="py-1.5">
                  <span className={`inline-flex items-center gap-1 font-medium ${state.tone}`}>
                    <state.Icon className="size-3.5" aria-hidden="true" />
                    {state.word}
                  </span>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
      <ul className="mt-2 space-y-1 text-xs text-muted-foreground">
        {rows
          .filter((row) => row.state !== 'match' && row.state !== 'loading')
          .map((row) => (
            <li key={row.lab}>
              <span className="text-foreground">Exp {row.lab}:</span> {row.note}
            </li>
          ))}
        {rows.filter((row) => row.extra).map((row) => (
          <li key={`extra-${row.lab}`}>
            <span className="text-foreground">Exp {row.lab}:</span> {row.extra}
          </li>
        ))}
      </ul>
    </div>
  )
}

/**
 * The side panel for one link: what it means, the current evidence (live, or the test that proves
 * it), the experiments it joins and "See in Timeline" with the link's module filters.
 */
export function LinkPanel({ link, evidence, onClose }) {
  const experiments = link.labs
    .map((lab) => EXPERIMENTS.find((e) => e.lab === lab))
    .filter(Boolean)
  const headingId = `link-panel-${link.id}`
  return (
    <section aria-labelledby={headingId} className="space-y-4" data-testid="link-panel">
      <div className="flex items-start gap-3">
        <Chip id={link.id} active />
        <h3 id={headingId} className="min-w-0 flex-1 text-base font-semibold leading-snug text-foreground">
          {link.meaning}
        </h3>
        <Button variant="ghost" size="icon" className="-mr-2 -mt-1 shrink-0" aria-label="Close link details" onClick={onClose}>
          <X aria-hidden="true" />
        </Button>
      </div>

      <div className="space-y-1">
        <h4 className="text-xs font-medium text-muted-foreground">What it means</h4>
        <p className="text-sm text-foreground">{link.detail}</p>
      </div>

      <div className="space-y-2" aria-live="polite">
        <div className="flex items-center justify-between gap-2">
          <h4 className="text-xs font-medium text-muted-foreground">Current evidence</h4>
          <EvidenceKind kind={evidence.kind} />
        </div>
        {evidence.kind === 'test' && (
          <p className="text-sm text-foreground">
            Wired, verified by <span className="break-all font-mono text-xs">{evidence.test}</span>.
          </p>
        )}
        {evidence.lines.map((line) => (
          <p key={line} className={evidence.kind === 'live' ? 'text-sm text-foreground' : 'text-xs text-muted-foreground'}>
            {line}
          </p>
        ))}
        {evidence.rows && <RoleTable rows={evidence.rows} />}
      </div>

      <div className="space-y-1">
        <h4 className="text-xs font-medium text-muted-foreground">Proven by</h4>
        <ul className="space-y-0.5">
          {link.tests.map((test) => (
            <li key={test} className="break-all font-mono text-xs text-foreground">{test}</li>
          ))}
        </ul>
      </div>

      <div className="flex flex-wrap gap-2">
        <Link
          to={link.timeline}
          className="inline-flex h-9 items-center justify-center rounded-md bg-primary px-3 text-sm font-medium text-primary-foreground hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          See in Timeline
        </Link>
      </div>
      <div className="flex flex-wrap gap-x-3 gap-y-1 text-xs">
        {experiments.map((e) => (
          <Link key={e.id} to={`/experiments/${e.slug}`} className="text-muted-foreground underline-offset-2 hover:text-foreground hover:underline">
            Exp {e.lab} {SHORT_NAMES[e.lab]}
          </Link>
        ))}
      </div>
    </section>
  )
}
