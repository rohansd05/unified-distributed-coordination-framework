import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import replicasDiverged from '@/test/fixtures/replication/replicas-diverged.json'
import replicasConverged from '@/test/fixtures/replication/replicas-converged.json'
import { ApiError } from '@/services/api'
import { ReplicaGrid } from './ReplicaGrid'
import { CELL_STATES, cellStateLabel } from './labels'
import { gridModel } from './replicaModel'

const copy = (value) => JSON.parse(JSON.stringify(value))

afterEach(() => {
  cleanup()
})

function renderGrid(replicas, props = {}) {
  return render(<ReplicaGrid model={gridModel(replicas)} loading={false} error={null} onRetry={vi.fn()} {...props} />)
}

/**
 * The real converged view with one key's cells changed to every state, including the three
 * the captured fixtures do not contain (ABSENT, AHEAD, CONFLICT): derived, only state and item changed.
 */
function everyStateView() {
  const derived = copy(replicasConverged)
  const row = derived.rows[0]
  const template = row.cells[0]
  derived.replicas = CELL_STATES.map((state, index) => ({ ...derived.replicas[0], nodeId: index + 1, reference: index === 0 }))
  row.cells = CELL_STATES.map((state, index) => ({
    nodeId: index + 1,
    state,
    item: ['MISSING', 'UNREACHABLE', 'ABSENT'].includes(state) ? null : { ...template.item, value: `v-${state.toLowerCase()}` },
  }))
  derived.rows = [row]
  return derived
}

describe('ReplicaGrid', () => {
  it('shows every key on every replica, primary first, from the real diverged view', () => {
    renderGrid(replicasDiverged)

    const rows = screen.getAllByTestId('replica-row')
    expect(rows.map((row) => row.querySelector('p').textContent)).toEqual(replicasDiverged.rows.map((r) => r.key))
    const firstCells = within(rows[0]).getAllByTestId('replica-cell')
    expect(firstCells[0].getAttribute('data-node')).toBe('1')
    expect(firstCells[0].getAttribute('aria-label')).toContain('the primary')
    expect(screen.getByTestId('grid-summary').textContent).toBe('1 difference from the primary (node 1).')
  })

  it('a missing cell says so in words and in shape (real fixture)', () => {
    renderGrid(replicasDiverged)
    const missing = screen.getAllByTestId('replica-cell').find((cell) => cell.getAttribute('data-state') === 'MISSING')

    expect(missing.getAttribute('aria-label')).toBe('Node 3, missing: holds nothing')
    expect(missing.textContent).toContain('Missing')
    expect(missing.querySelector('[data-glyph="missing"]')).not.toBeNull()
  })

  it('every state has its own word and its own shape, never colour alone (derived: ABSENT, AHEAD, CONFLICT added)', () => {
    renderGrid(everyStateView())

    const cells = screen.getAllByTestId('replica-cell')
    expect(cells.map((cell) => cell.getAttribute('data-state'))).toEqual(CELL_STATES)
    const glyphs = cells.map((cell) => cell.querySelector('svg').getAttribute('data-glyph'))
    expect(new Set(glyphs).size).toBe(CELL_STATES.length)
    for (const [index, state] of CELL_STATES.entries()) {
      expect(cells[index].textContent).toContain(cellStateLabel(state))
    }
    expect(cells[CELL_STATES.indexOf('UNREACHABLE')].textContent).toContain('No answer')
    expect(cells[CELL_STATES.indexOf('ABSENT')].textContent).toContain('Nothing held')
    expect(cells[CELL_STATES.indexOf('CONFLICT')].getAttribute('aria-label')).toContain('conflict')
  })

  it('the legend explains every state with the same shapes', () => {
    renderGrid(replicasConverged)
    const legend = screen.getByTestId('state-legend')
    expect(within(legend).getAllByRole('listitem')).toHaveLength(CELL_STATES.length)
    expect(legend.textContent).toContain('Stale: holds an older version than the primary.')
  })

  it('an unreachable replica is marked in the column list (derived: node 5 unreachable)', () => {
    const derived = copy(replicasConverged)
    derived.replicas[4] = { ...derived.replicas[4], nodeStatus: 'CRASHED', reachable: false, epoch: null, itemCount: null, error: 'ConnectException: x' }
    renderGrid(derived)

    const columns = screen.getAllByTestId('replica-column')
    expect(columns[4].textContent).toBe('Node 5, crashed, not readable')
    expect(screen.getByTestId('grid-summary').textContent).toContain('1 replica is unreachable.')
  })

  it('empty: guidance instead of rows', () => {
    renderGrid({ ...copy(replicasConverged), rows: [] })
    expect(screen.queryAllByTestId('replica-row')).toHaveLength(0)
    expect(screen.getByText(/No keys yet/)).toBeTruthy()
  })

  it('loading: a busy skeleton; error: the backend message and a retry', () => {
    const { rerender } = render(<ReplicaGrid model={gridModel(null)} loading error={null} onRetry={vi.fn()} />)
    expect(screen.getByText('Reading every replica…')).toBeTruthy()

    const onRetry = vi.fn()
    rerender(<ReplicaGrid model={gridModel(null)} loading={false}
      error={new ApiError({ status: 0, title: 'Backend unreachable', detail: 'Network Error' })} onRetry={onRetry} />)
    expect(screen.getByRole('alert').textContent).toContain('Network Error')
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(onRetry).toHaveBeenCalled()
  })

  it('announces the comparison politely, inside the positioned figure', () => {
    renderGrid(replicasConverged)
    const live = document.querySelector('[aria-live="polite"]')
    expect(live.textContent).toBe('Every reachable replica matches the primary (node 1).')
    expect(live.closest('figure').className).toContain('relative')
  })
})
