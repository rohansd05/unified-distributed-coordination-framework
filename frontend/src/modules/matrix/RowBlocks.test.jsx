import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import overviewAfter from '@/test/fixtures/matrix/overview-after-start.json'
import overviewCrashed from '@/test/fixtures/matrix/overview-node-crashed.json'
import complete316 from '@/test/fixtures/matrix/run-complete-316.json'
import partial from '@/test/fixtures/matrix/run-partial.json'
import skipped from '@/test/fixtures/matrix/run-node-crashed-skipped.json'
import refused from '@/test/fixtures/matrix/run-refused.json'
import { RowBlocks } from './RowBlocks'

afterEach(cleanup)

describe('RowBlocks', () => {
  it('draws one band per rank, as tall as its rows, with the rows, the place and the node compute time', () => {
    render(<RowBlocks overview={overviewAfter} run={complete316} />)
    const bands = screen.getAllByTestId('row-band')
    expect(bands).toHaveLength(complete316.computes.length)
    const [first] = complete316.computes
    expect(bands[0].style.flexGrow).toBe(String(first.rowCount))
    expect(bands[0].textContent).toContain(`Rank 0, node ${first.nodeId} (root)`)
    expect(bands[0].textContent).toContain(`Rows 0 to ${first.rowCount - 1} (${first.rowCount} rows)`)
    expect(bands[0].textContent).toContain('Computed on the root: done')
    expect(bands[0].textContent).toContain('node compute')
    expect(bands.every((band) => within(band).queryByText('Arrived'))).toBe(true)
  })

  it('shows a missing block as a hole with its reason and a word, never filled in', () => {
    render(<RowBlocks overview={overviewAfter} run={partial} />)
    const hole = screen.getAllByTestId('row-band').find((band) => band.dataset.hole === 'true')
    expect(hole.dataset.rank).toBe('4')
    expect(within(hole).getByText('Hole')).toBeTruthy()
    expect(hole.textContent).toContain(partial.computes[4].notAttemptedReason)
    expect(screen.getByTestId('row-blocks-sentence').textContent).toContain(`4 of 5 blocks of C arrived`)
  })

  it('lists the skipped nodes', () => {
    render(<RowBlocks overview={overviewCrashed} run={skipped} />)
    expect(screen.getByTestId('skipped-nodes').textContent).toContain('node 3')
  })

  it('lists the nodes before any run, and says a refused run split nothing', () => {
    const { rerender } = render(<RowBlocks overview={overviewCrashed} run={null} />)
    expect(screen.getByTestId('row-blocks-sentence').textContent).toContain('No run yet')
    expect(screen.getByText('Node 3: down')).toBeTruthy()
    rerender(<RowBlocks overview={overviewCrashed} run={refused} />)
    expect(screen.getByTestId('row-blocks-sentence').textContent).toBe(`Run ${refused.runId} was refused, so no rows were split.`)
    expect(screen.queryAllByTestId('row-band')).toHaveLength(0)
  })

  it('carries the co-hosting Simulated badge before and after a run, and none without coHosting', () => {
    const { rerender } = render(<RowBlocks overview={overviewAfter} run={null} />)
    expect(screen.getAllByTestId('simulated-badge')).toHaveLength(1)
    expect(screen.getByRole('tooltip', { hidden: true }).textContent).toBe(overviewAfter.coHosting.reason)
    rerender(<RowBlocks overview={overviewAfter} run={complete316} />)
    expect(screen.getAllByTestId('simulated-badge')).toHaveLength(1)
    rerender(<RowBlocks overview={{ ...overviewAfter, coHosting: null }} run={complete316} />)
    expect(screen.queryAllByTestId('simulated-badge')).toHaveLength(0)
  })
})
