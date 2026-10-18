/**
 * The six integration links (docs/HANDOFF.md, Phase 9A, R19) that make the ten experiments one
 * system. Each link names the experiments it joins, the backend test that proves it (9A.1, 9A.3
 * and the 9A.2 audit), and a Timeline deep link built from literals only (routes.jsx rule).
 */

/** Short names for the map's experiment nodes, by lab number. */
export const SHORT_NAMES = {
  1: 'RMI',
  2: 'Multithreading',
  3: 'Clock sync',
  4: 'Election',
  5: 'Replication',
  6: 'Load balancing',
  7: 'MapReduce',
  8: 'Fault tolerance',
  9: 'MPI collectives',
  10: 'Matrix multiply',
}

/** Endpoint of an edge: an experiment by lab number, or the shared cluster bus. */
export const BUS = 'bus'

export const LINKS = [
  {
    id: 'L1',
    meaning: 'Roles follow the leader that Experiment 4 elects (Experiments 3, 5, 7, 8, 9 and 10)',
    detail:
      'Only Experiment 4’s election decides the leader. The Berkeley time daemon, the replication ' +
      'primary, the MapReduce coordinator, Experiment 8’s primary and the MPI and matrix roots all take ' +
      'it from the shared role provider; no module picks a node of its own. When the leader crashes, ' +
      'Experiment 8 promotes the new leader by itself, and every other role moves at its module’s next ' +
      'action, marked by a role-change event.',
    edges: [[4, 3], [4, 5], [4, 7], [4, 8], [4, 9], [4, 10]],
    labs: [4, 3, 5, 7, 8, 9, 10],
    tests: ['LeaderRolesIntegrationTest', 'ClockSyncLeaderIntegrationTest', 'MapReduceLeaderIntegrationTest'],
    timeline: '/timeline?modules=cluster,election,clocksync,replication,mapreduce,faulttolerance,mpi,matrix&order=arrival',
  },
  {
    id: 'L2',
    meaning: 'Experiments 4 and 8 act on the same failure-detector suspicion',
    detail:
      'Each node runs one failure detector: it sends heartbeats, and a peer that stays silent past the ' +
      'timeout is suspected. That one suspicion makes Experiment 4 start an election and is what ' +
      'Experiment 8 times as the detection of a primary crash, so the two never disagree about who is down.',
    edges: [[4, 8]],
    labs: [4, 8],
    tests: ['LeaderFailoverIntegrationTest'],
    timeline: '/timeline?modules=election,faulttolerance&order=arrival',
  },
  {
    id: 'L3',
    meaning: 'Experiment 6 dispatches into Experiment 2 executors',
    detail:
      'The load balancer has no thread pool of its own. Each request goes over TCP to the chosen node’s ' +
      'requests port and runs on that node’s Experiment 2 executor, so the strategies compete for the ' +
      'real worker threads of each node (4, 2 or 1).',
    edges: [[6, 2]],
    labs: [6, 2],
    tests: ['L3LoadBalancingIntoExecutorsIntegrationTest'],
    timeline: '/timeline?modules=loadbalancing,multithreading',
  },
  {
    id: 'L4',
    meaning: 'Lamport time on every inter-node message',
    detail:
      'Every message between nodes carries the sender’s Lamport time and the receiver merges it: RMI ' +
      'calls, the UDP clock and election messages, and the TCP requests, replication and MapReduce ' +
      'traffic. That is why the events of all ten experiments fit one causal order on the Timeline. ' +
      'Failure-detector heartbeats are the one deliberate exception.',
    edges: [],
    labs: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
    tests: ['L4LamportOnMessagesIntegrationTest'],
    timeline: '/timeline',
  },
  {
    id: 'L5',
    meaning: 'The live event log feeds MapReduce (Experiment 7)',
    detail:
      'The cluster’s own event log, every event of every experiment, is exported as text and becomes ' +
      'Experiment 7’s input: a MapReduce job then counts the system’s own behaviour, for example the ' +
      'events of each type.',
    edges: [[BUS, 7]],
    labs: [7],
    tests: ['L5EventExportToMapReduceIntegrationTest'],
    timeline: '/timeline?modules=mapreduce,cluster',
  },
  {
    id: 'L6',
    meaning: 'Experiment 10 uses Experiment 9 collectives over Experiment 1 RMI',
    detail:
      'A parallel multiply scatters the rows of A and broadcasts B with Experiment 9’s collectives, ' +
      'which travel as Experiment 1 RMI calls. Each node multiplies its rows, and the root then fetches ' +
      'each block of C by RMI (not with Experiment 9’s gather).',
    edges: [[10, 9], [9, 1]],
    labs: [10, 9, 1],
    tests: ['L6MatrixOverCollectivesOverRmiIntegrationTest'],
    timeline: '/timeline?modules=matrix,mpi,rmi',
  },
]

/** The link with this id, or undefined. */
export function findLink(id) {
  return LINKS.find((link) => link.id === id)
}
