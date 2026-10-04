import { Button } from '@/components/ui/button'
import { TOKENS } from '@/lib/tokens'

/** Shell placeholder — replaced in Step 2.2. Shows the theme, fonts and base components. */
export default function App() {
  return (
    <main className="mx-auto max-w-5xl space-y-10 px-6 py-10">
      <p className="text-xs uppercase tracking-wider text-muted-foreground">
        Shell placeholder — replaced in Step 2.2
      </p>

      <header className="space-y-2">
        <h1 className="text-3xl font-semibold tracking-tight">
          Unified Distributed Coordination Framework
        </h1>
        <p className="text-muted-foreground">
          Ten distributed-systems experiments running as modules of one shared cluster.
        </p>
      </header>

      <section aria-labelledby="tokens-heading" className="space-y-4">
        <h2 id="tokens-heading" className="text-xl font-semibold">Design tokens</h2>
        <ul className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5">
          {TOKENS.map((token) => (
            <li key={token.name} className="overflow-hidden rounded-lg border bg-card">
              <div className={`h-14 border-b ${token.swatch}`} aria-hidden="true" />
              <div className="space-y-0.5 p-3 text-sm">
                <p className="font-medium">{token.name}</p>
                <p className="font-mono text-xs text-muted-foreground">{token.hex}</p>
                <p className="text-xs text-muted-foreground">{token.use}</p>
              </div>
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="type-heading" className="space-y-3">
        <h2 id="type-heading" className="text-xl font-semibold">Typography</h2>
        <p>Inter for the interface: every node shares one Lamport clock per node, so the global timeline is causally ordered.</p>
        <p className="font-mono text-sm text-success">L=42  (lamportTime, nodeId)</p>
      </section>

      <section aria-labelledby="components-heading" className="space-y-3">
        <h2 id="components-heading" className="text-xl font-semibold">Components</h2>
        <div className="flex flex-wrap items-center gap-3">
          <Button>Primary action</Button>
          <Button variant="outline">Outline action</Button>
          <span className="inline-flex items-center rounded-full bg-warning px-2.5 py-0.5 text-xs font-semibold text-warning-foreground">
            Simulated
          </span>
        </div>
      </section>
    </main>
  )
}
