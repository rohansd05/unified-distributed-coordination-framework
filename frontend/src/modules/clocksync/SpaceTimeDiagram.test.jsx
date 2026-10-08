import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { SpaceTimeDiagram } from './SpaceTimeDiagram'

describe('SpaceTimeDiagram', () => {
  afterEach(() => {
    cleanup()
  })
  const sampleNodes = [
    { nodeId: 1, status: 'UP' },
    { nodeId: 2, status: 'UP' },
    { nodeId: 3, status: 'CRASHED' },
  ]

  const sampleEvents = [
    { nodeId: 1, type: 'SEND', lamportTime: 1, messageId: 10, peerId: 2, description: 'Message to node 2' },
    { nodeId: 2, type: 'RECV', lamportTime: 2, messageId: 10, peerId: 1, description: 'Receive from node 1' },
    { nodeId: 1, type: 'LOCAL', lamportTime: 3, messageId: null, description: 'Local tick' },
  ]

  it('renders SVG lanes and retention note', () => {
    render(
      <SpaceTimeDiagram
        nodes={sampleNodes}
        events={sampleEvents}
        retainedEventsCount={10}
        droppedEventsCount={2}
      />
    )

    expect(screen.getByTestId('space-time-diagram')).toBeTruthy()
    expect(screen.getByTestId('lane-1')).toBeTruthy()
    expect(screen.getByTestId('lane-2')).toBeTruthy()
    expect(screen.getByTestId('lane-3')).toBeTruthy()
    expect(screen.getByTestId('retention-note').textContent).toContain('Showing the latest 3 of 10 retained events')
    expect(screen.getByTestId('retention-note').textContent).toContain('2 earlier events were dropped')
  })

  it('renders message arrows between send and receive events', () => {
    render(<SpaceTimeDiagram nodes={sampleNodes} events={sampleEvents} />)

    const arrow = screen.getByTestId('arrow-10')
    expect(arrow).toBeTruthy()
    expect(arrow.textContent).toContain('m#10')
  })

  it('marks causal violations by both shape and text', () => {
    const violationEvents = [
      { nodeId: 1, type: 'SEND', lamportTime: 5, messageId: 20, peerId: 2 },
      { nodeId: 2, type: 'RECV', lamportTime: 4, messageId: 20, peerId: 1 }, // recv <= send
    ]
    const violations = [
      { nodeId: 2, actualLamportTime: 4, expectedRelationTime: 5, peerId: 1, message: 'Receive 4 <= send 5' },
    ]

    const { container } = render(
      <SpaceTimeDiagram
        nodes={sampleNodes}
        events={violationEvents}
        violations={violations}
      />
    )

    // Check triangle polygon shape
    const polygons = container.querySelectorAll('polygon')
    expect(polygons.length).toBeGreaterThan(0)

    // Check explicit violation text
    expect(screen.getAllByText('Violation').length).toBeGreaterThan(0)
  })

  it('a receive whose send was dropped is drawn without an arrow and is never a violation', () => {
    const eventsWithDroppedSend = [
      { nodeId: 2, type: 'RECV', lamportTime: 4, messageId: 99, peerId: 1 },
    ]
    const violations = [
      { nodeId: 2, actualLamportTime: 4, expectedRelationTime: 5, peerId: 1, message: 'Receive 4 <= send 5' },
    ]

    const { container } = render(
      <SpaceTimeDiagram
        nodes={sampleNodes}
        events={eventsWithDroppedSend}
        violations={violations}
      />
    )

    // No arrow should be drawn
    expect(screen.queryByTestId('arrow-99')).toBeNull()
    // Not drawn as a violation
    const polygons = container.querySelectorAll('polygon')
    expect(polygons).toHaveLength(0)
    expect(screen.queryByText('Violation')).toBeNull()
  })

  it('toggles the keyboard-accessible text equivalent table', () => {
    render(<SpaceTimeDiagram nodes={sampleNodes} events={sampleEvents} />)

    expect(screen.queryByTestId('diagram-table')).toBeNull()

    const toggleButton = screen.getByRole('button', { name: /Show text equivalent/i })
    fireEvent.click(toggleButton)

    expect(screen.getByTestId('diagram-table')).toBeTruthy()
    expect(screen.getByRole('table')).toBeTruthy()
    expect(screen.getByText('Event log in total order (Lamport time, node id)')).toBeTruthy()

    // Clicking again hides the table
    fireEvent.click(screen.getByRole('button', { name: /Hide text equivalent/i }))
    expect(screen.queryByTestId('diagram-table')).toBeNull()
  })

  it('renders empty states gracefully', () => {
    const { rerender } = render(<SpaceTimeDiagram nodes={[]} events={[]} />)
    expect(screen.getByText('No cluster nodes available.')).toBeTruthy()

    rerender(<SpaceTimeDiagram nodes={sampleNodes} events={[]} />)
    expect(screen.getByText('No logical clock events recorded yet.')).toBeTruthy()
  })
})
