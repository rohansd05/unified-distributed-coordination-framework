import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { BerkeleyView } from './BerkeleyView'

describe('BerkeleyView', () => {
  afterEach(() => {
    cleanup()
  })
  const sampleNodes = [
    {
      nodeId: 1,
      status: 'UP',
      simulatedDrift: { offsetMillis: 0, driftRateMsPerSec: 0, simulated: true, simulatedReason: 'Simulated math' },
    },
    {
      nodeId: 2,
      status: 'UP',
      simulatedDrift: { offsetMillis: 242, driftRateMsPerSec: 1.5, simulated: true, simulatedReason: 'Simulated math' },
    },
    {
      nodeId: 3,
      status: 'CRASHED',
      simulatedDrift: { offsetMillis: -165, driftRateMsPerSec: -1.0, simulated: true, simulatedReason: 'Simulated math' },
    },
  ]

  it('renders initial state before any Berkeley round', () => {
    render(<BerkeleyView nodes={sampleNodes} timeDaemonNodeId={1} latestRound={null} />)

    expect(screen.getByTestId('berkeley-view')).toBeTruthy()
    expect(screen.getByText(/Current simulated physical drift offsets before synchronization/i)).toBeTruthy()
    expect(screen.getByTestId('node-drift-1').textContent).toContain('0 ms')
    expect(screen.getByTestId('node-drift-2').textContent).toContain('+242 ms')
    // Node 3 is crashed, so its offset shows as not available
    expect(screen.getByTestId('node-drift-3').textContent).toContain('crashed')
  })

  it('renders converged round with spread before and spread after', () => {
    const round = {
      roundId: 1,
      daemonNodeId: 1,
      outlierThresholdMillis: 400,
      averageOffsetMillis: 26,
      spreadBeforeMillis: 407,
      spreadAfterMillis: 0,
      participatingNodes: [1, 2],
      outlierNodes: [],
      adjustments: [
        { nodeId: 1, beforeOffsetMillis: 0, adjustmentMillis: 26, afterOffsetMillis: 26, outlier: false, rttMillis: 0 },
        { nodeId: 2, beforeOffsetMillis: 242, adjustmentMillis: -218, afterOffsetMillis: 26, outlier: false, rttMillis: 4.4 },
      ],
      simulated: true,
      simulatedReason: 'Offsets and drift rates are simulated',
    }

    render(<BerkeleyView nodes={sampleNodes} timeDaemonNodeId={1} latestRound={round} />)

    expect(screen.getByText('407 ms')).toBeTruthy()
    expect(screen.getAllByText('0 ms').length).toBeGreaterThan(0)
    expect(screen.getAllByText('+26 ms').length).toBeGreaterThan(0)

    // Node 1 and Node 2 participated
    expect(screen.getByTestId('node-round-1').textContent).toContain('Before: 0 ms')
    expect(screen.getByTestId('node-round-2').textContent).toContain('Before: +242 ms')

    // Node 3 was not participating / crashed: must show Not reached, NOT 0 offset
    const node3Row = screen.getByTestId('node-round-3')
    expect(node3Row.textContent).toContain('Not reached')
    expect(node3Row.textContent).not.toContain('Before: 0 ms')
  })

  it('displays outlier tag when a node is marked outlier', () => {
    const roundWithOutlier = {
      roundId: 2,
      daemonNodeId: 1,
      outlierThresholdMillis: 200,
      averageOffsetMillis: 10,
      spreadBeforeMillis: 500,
      spreadAfterMillis: 20,
      participatingNodes: [1, 2],
      outlierNodes: [2],
      adjustments: [
        { nodeId: 1, beforeOffsetMillis: 0, adjustmentMillis: 10, afterOffsetMillis: 10, outlier: false, rttMillis: 0 },
        { nodeId: 2, beforeOffsetMillis: 450, adjustmentMillis: 0, afterOffsetMillis: 450, outlier: true, rttMillis: 3.2 },
      ],
      simulated: true,
    }

    render(<BerkeleyView nodes={sampleNodes} timeDaemonNodeId={1} latestRound={roundWithOutlier} />)

    expect(screen.getByText('Outlier')).toBeTruthy()
  })

  it('shows "Not reached" for an unreached node in a round, never zero offset, and does not draw its bar at zero', () => {
    const roundWithUnreached = {
      roundId: 3,
      daemonNodeId: 1,
      outlierThresholdMillis: 400,
      averageOffsetMillis: 15,
      spreadBeforeMillis: 300,
      spreadAfterMillis: 10,
      participatingNodes: [1, 2],
      outlierNodes: [],
      adjustments: [
        { nodeId: 1, beforeOffsetMillis: 0, adjustmentMillis: 15, afterOffsetMillis: 15, outlier: false, rttMillis: 0 },
        { nodeId: 2, beforeOffsetMillis: 100, adjustmentMillis: -85, afterOffsetMillis: 15, outlier: false, rttMillis: 3.0 },
      ],
      simulated: true,
    }

    render(<BerkeleyView nodes={sampleNodes} timeDaemonNodeId={1} latestRound={roundWithUnreached} />)

    const unreachedRow = screen.getByTestId('node-round-3')
    expect(unreachedRow.textContent).toContain('Not reached')
    expect(unreachedRow.textContent).toContain('Not reached (no adjustment)')
    expect(unreachedRow.textContent).not.toContain('0 ms')
    expect(unreachedRow.textContent).not.toContain('NaN')
    expect(unreachedRow.textContent).not.toContain('undefined')
    // The bar is not rendered for unreached node (not drawn at zero)
    expect(unreachedRow.querySelectorAll('[title^="Before"], [title^="After"]').length).toBe(0)
  })

  it('renders the Simulated badge with the backend reason text from the fixture', async () => {
    const overviewFixture = await import('../../test/fixtures/clocksync/overview-after-sync.json')
    const latestRound = overviewFixture.default.latestRound
    const nodes = overviewFixture.default.nodes

    render(
      <BerkeleyView
        nodes={nodes}
        timeDaemonNodeId={overviewFixture.default.timeDaemonNodeId}
        latestRound={latestRound}
      />
    )

    const badge = screen.getByTestId('simulated-badge')
    expect(badge).toBeTruthy()
    // Simulated reason from fixture
    expect(screen.getByRole('tooltip').textContent).toBe(latestRound.simulatedReason)
  })

  it('renders a dash with an accessible label for null spread, never 0', () => {
    const roundNullSpread = {
      roundId: 4,
      daemonNodeId: 1,
      outlierThresholdMillis: 400,
      averageOffsetMillis: 0,
      spreadBeforeMillis: null,
      spreadAfterMillis: null,
      participatingNodes: [1, 2],
      outlierNodes: [],
      adjustments: [
        { nodeId: 1, beforeOffsetMillis: 0, adjustmentMillis: 0, afterOffsetMillis: 0, outlier: false, rttMillis: 0 },
        { nodeId: 2, beforeOffsetMillis: 0, adjustmentMillis: 0, afterOffsetMillis: 0, outlier: false, rttMillis: 2.0 },
      ],
      simulated: true,
    }

    render(<BerkeleyView nodes={sampleNodes} timeDaemonNodeId={1} latestRound={roundNullSpread} />)

    const spreadBeforeBox = screen.getByText('Spread before:').parentElement
    const spreadAfterBox = screen.getByText('Spread after:').parentElement

    expect(within(spreadBeforeBox).queryByText(/^0\s*ms$/)).toBeNull()
    expect(within(spreadAfterBox).queryByText(/^0\s*ms$/)).toBeNull()

    const spreadBeforeDash = within(spreadBeforeBox).getByLabelText('Not available')
    expect(spreadBeforeDash.textContent).toBe('—')

    const spreadAfterDash = within(spreadAfterBox).getByLabelText('Not available')
    expect(spreadAfterDash.textContent).toBe('—')
  })
})

