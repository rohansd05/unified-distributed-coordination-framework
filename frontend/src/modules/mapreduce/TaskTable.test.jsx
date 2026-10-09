import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import sampleRun from '@/test/fixtures/mapreduce/run-sample-word-count.json'
import crashRun from '@/test/fixtures/mapreduce/run-crash-retry.json'
import { TaskTable } from './TaskTable'

afterEach(cleanup)

describe('TaskTable', () => {
  it('one row per task when nothing was retried', () => {
    render(<TaskTable run={sampleRun} />)
    expect(screen.getAllByTestId('task-row')).toHaveLength(sampleRun.report.tasks.length)
    expect(screen.queryByText('Failed')).toBeNull()
  })

  it('a retried task has a row per attempt: node, type, outcome as icon and word, and the backend\'s reason', () => {
    render(<TaskTable run={crashRun} />)
    const rows = screen.getAllByTestId('task-row').filter((row) => row.textContent.startsWith('Map task 3'))
    expect(rows.map((row) => within(row).getAllByRole('cell').map((cell) => cell.textContent))).toEqual([
      ['Map task 3retried', 'Map', '1', 'node 3', 'Failed(Connection refused: connect)'],
      ['Map task 3retried', 'Map', '2', 'node 4', 'Completed'],
    ])
    expect(rows[0].querySelector('svg')).not.toBeNull()
  })

  it('lists both retried tasks of the crash run', () => {
    render(<TaskTable run={crashRun} />)
    expect(screen.getAllByText('Failed')).toHaveLength(2)
    expect(screen.getAllByTestId('task-row')).toHaveLength(crashRun.report.tasks.length + 2)
  })

  it('without a report: guidance, no table', () => {
    render(<TaskTable run={null} />)
    expect(screen.queryByTestId('task-table')).toBeNull()
    expect(screen.getByText('Tasks and their attempts appear here when a run ends.')).toBeTruthy()
  })
})
