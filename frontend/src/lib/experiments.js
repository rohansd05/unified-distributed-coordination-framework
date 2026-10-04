/**
 * The ten lab experiments (docs/HANDOFF.md Sections 3 and 7), sorted by lab number.
 * slug is "{lab}-{id}" and forms the page URL /experiments/{slug}; arrivesIn is the phase
 * in which the module is built (Section 11).
 *
 * Static catalog; Step 2.3 merges live status from GET /api/modules.
 */
export const EXPERIMENTS = [
  { lab: 1, id: 'rmi', title: 'Client-Server via Java RMI', arrivesIn: 10 },
  { lab: 2, id: 'multithreading', title: 'Multithreading', arrivesIn: 3 },
  { lab: 3, id: 'clocksync', title: 'Clock Synchronization', arrivesIn: 4 },
  { lab: 4, id: 'election', title: 'Bully and Ring Election', arrivesIn: 5 },
  { lab: 5, id: 'replication', title: 'Consistency and Replication', arrivesIn: 6 },
  { lab: 6, id: 'loadbalancing', title: 'Load Balancing', arrivesIn: 8 },
  { lab: 7, id: 'mapreduce', title: 'MapReduce', arrivesIn: 9 },
  { lab: 8, id: 'faulttolerance', title: 'Fault Tolerance', arrivesIn: 7 },
  { lab: 9, id: 'mpi', title: 'MPI Collectives', arrivesIn: 11 },
  { lab: 10, id: 'matrix', title: 'Parallel Matrix Multiplication', arrivesIn: 12 },
].map((experiment) => ({ ...experiment, slug: `${experiment.lab}-${experiment.id}` }))

/** The experiment with this slug, or undefined. */
export function findBySlug(slug) {
  return EXPERIMENTS.find((experiment) => experiment.slug === slug)
}
