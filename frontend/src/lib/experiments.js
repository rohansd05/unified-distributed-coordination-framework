/**
 * The ten lab experiments (docs/HANDOFF.md Sections 3 and 7), sorted by lab number.
 * slug is "{lab}-{id}" and forms the page URL /experiments/{slug}; arrivesIn is the phase
 * in which the module is built (Section 11). concept is optional: a one-line idea that the
 * module's page shows under its title (ExperimentLayout), added when the page is built.
 *
 * Static catalog; Step 2.3 merges live status from GET /api/modules.
 */
export const EXPERIMENTS = [
  { lab: 1, id: 'rmi', title: 'Client-Server via Java RMI', arrivesIn: 10 },
  {
    lab: 2,
    id: 'multithreading',
    title: 'Multithreading',
    arrivesIn: 3,
    concept: 'Many requests, a few worker threads: how one node shares out work and pushes back when it is full.',
  },
  { lab: 3, id: 'clocksync', title: 'Clock Synchronization', arrivesIn: 4 },
  { lab: 4, id: 'election', title: 'Bully and Ring Election', arrivesIn: 5 },
  { lab: 5, id: 'replication', title: 'Consistency and Replication', arrivesIn: 6 },
  {
    lab: 6,
    id: 'loadbalancing',
    title: 'Load Balancing',
    arrivesIn: 8,
    concept: 'One gateway, several unequal workers: which worker should get the next request?',
  },
  { lab: 7, id: 'mapreduce', title: 'MapReduce', arrivesIn: 9 },
  { lab: 8, id: 'faulttolerance', title: 'Fault Tolerance', arrivesIn: 7 },
  { lab: 9, id: 'mpi', title: 'MPI Collectives', arrivesIn: 11 },
  { lab: 10, id: 'matrix', title: 'Parallel Matrix Multiplication', arrivesIn: 12 },
].map((experiment) => ({ ...experiment, slug: `${experiment.lab}-${experiment.id}` }))

/** The experiment with this slug, or undefined. */
export function findBySlug(slug) {
  return EXPERIMENTS.find((experiment) => experiment.slug === slug)
}

/**
 * Merges live module status from /api/modules into the catalog.
 * When modules is null (e.g. fetch failed), returns the catalog without statuses.
 * When modules is an array, each experiment gets the backend status (IDLE, RUNNING, BUSY, ERROR)
 * if reported, or 'PLANNED' otherwise.
 *
 * @param {Array} catalog
 * @param {Array | null} modules
 * @returns {Array}
 */
export function mergeModuleStatus(catalog = EXPERIMENTS, modules) {
  if (!Array.isArray(modules)) {
    return catalog.map((experiment) => ({ ...experiment, status: null }))
  }

  const statusMap = new Map(modules.map((m) => [m.id, m.status]))

  return catalog.map((experiment) => ({
    ...experiment,
    status: statusMap.has(experiment.id) ? statusMap.get(experiment.id) : 'PLANNED',
  }))
}

/**
 * Formats a status string for UI display (e.g. 'PLANNED' -> 'Planned').
 */
export function formatStatus(status) {
  if (!status) return ''
  return status.charAt(0).toUpperCase() + status.slice(1).toLowerCase()
}
