import { Link } from 'react-router-dom'
import { LabBadge } from '@/components/LabBadge'
import { PageHeader } from '@/components/PageHeader'
import { SimulatedBadge } from '@/components/experiment/SimulatedBadge'
import { useCluster } from '@/hooks/useCluster'
import { EXPERIMENTS } from '@/lib/experiments'

const linkClass = 'font-medium text-primary underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring'

/** What the system is, the ten labs as pages, and what is real or simulated (14.1). */
export function AboutPage() {
  const { cluster } = useCluster()
  const nodes = cluster?.size ? `${cluster.size} nodes` : 'nodes'
  return (
    <>
      <PageHeader
        title="About"
        description="Architecture, the mapping to the lab list, and what is real or simulated."
      />

      <section aria-labelledby="about-architecture" className="space-y-2">
        <h2 id="about-architecture" className="text-lg font-semibold">How it is built</h2>
        <p className="text-muted-foreground">
          One Spring Boot backend hosts a cluster of {nodes}. Each node owns real sockets on the loopback address:
          Java RMI for Experiments 1, 9 and 10, UDP for Experiments 3 and 4, and TCP for the others. All ten
          experiments share this one cluster, so crashing a node affects every experiment on it. This page talks to
          the backend over REST and receives live events over STOMP on a WebSocket. There is no database: all state
          is in memory, and a cluster reset starts everything from a clean slate.
        </p>
      </section>

      <section aria-labelledby="about-labs" className="space-y-2">
        <h2 id="about-labs" className="text-lg font-semibold">The ten experiments</h2>
        <ol className="grid gap-2 sm:grid-cols-2">
          {EXPERIMENTS.map((experiment) => (
            <li key={experiment.id} className="flex items-center gap-3">
              <LabBadge lab={experiment.lab} />
              <Link to={`/experiments/${experiment.slug}`} className={linkClass}>
                Experiment {experiment.lab}, {experiment.title}
              </Link>
            </li>
          ))}
        </ol>
      </section>

      <section aria-labelledby="about-real" className="space-y-2">
        <h2 id="about-real" className="text-lg font-semibold">What is real and what is simulated</h2>
        <p className="text-muted-foreground">
          Real: every message crosses a real socket, every inter-node message carries a Lamport time, and every timing
          and count on the pages is measured by the backend. Simulated, and labelled on its page with a badge that gives
          the reason: the {nodes} share one process on one machine, and the clock offsets and drift rates in
          Experiment 3. <SimulatedBadge reason="Shown wherever a value is simulated, with the reason." />
        </p>
      </section>
    </>
  )
}
