import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import runAccepted from '@/test/fixtures/mapreduce/run-accepted.json'
import sampleRun from '@/test/fixtures/mapreduce/run-sample-word-count.json'
import crashRun from '@/test/fixtures/mapreduce/run-crash-retry.json'
import { PipelineFlow } from './PipelineFlow'

const animated = () => document.querySelectorAll('[data-testid^="stage-"].animate-in')

afterEach(() => {
  cleanup()
  delete window.matchMedia
  vi.restoreAllMocks()
})

describe('PipelineFlow', () => {
  it('without a run: guidance, eight stages of "—", and no state badge', () => {
    render(<PipelineFlow run={null} />)
    expect(screen.getByTestId('pipeline-status').textContent).toBe('No run yet. Choose a job and an input, then select Run.')
    expect(screen.getAllByRole('listitem', { name: /^(Input|Split|Map|Combine|Shuffle|Partition|Reduce|Result):/ })).toHaveLength(8)
  })

  it('RUNNING: the state as an icon and the word, every count "—", announced politely', () => {
    render(<PipelineFlow run={runAccepted} />)
    const caption = screen.getByTestId('pipeline-flow').querySelector('figcaption')
    expect(caption.textContent).toContain('Running')
    expect(screen.getByTestId('pipeline-live').getAttribute('aria-live')).toBe('polite')
    expect(screen.getByTestId('pipeline-live').textContent).toBe('Word count on Bundled sample text: running.')
    expect(screen.getByTestId('stage-result').getAttribute('aria-label')).toBe('Result: — result keys')
    expect(screen.getByTestId('stage-map').getAttribute('aria-label')).toBe('Map: — map tasks')
  })

  it('COMPLETED: each stage shows the measured count and time', () => {
    render(<PipelineFlow run={sampleRun} />)
    expect(screen.getByTestId('stage-split').getAttribute('aria-label')).toBe('Split: 5 splits, one map task each')
    expect(screen.getByTestId('stage-result').textContent).toContain('116')
    expect(screen.getByTestId('pipeline-flow').querySelector('figcaption').textContent).toContain('Completed')
    expect(screen.queryByTestId('pipeline-retries')).toBeNull()
    expect(screen.queryByTestId('pipeline-crash')).toBeNull()
  })

  it('a crash run: the crashed node is marked with an icon and the word, the stage is named, and each retry is drawn and written', () => {
    render(<PipelineFlow run={crashRun} />)
    expect(screen.getByTestId('pipeline-crash').textContent).toBe('Node 3 was crashed right after its first map task was sent (the map stage).')
    const mapLane = within(screen.getByTestId('stage-map')).getByRole('list', { name: 'Map tasks per worker' })
    expect(within(mapLane).getByRole('listitem', { name: 'Node 3: 0 tasks, crashed during this run' }).textContent).toContain('crashed')
    const retries = within(screen.getByTestId('pipeline-retries')).getAllByRole('listitem')
    expect(retries.map((item) => item.textContent)).toEqual([
      expect.stringContaining('Map task 3: the attempt on node 3 failed, node 4 re-ran it.'),
      expect.stringContaining('Reduce task 3: the attempt on node 3 failed, node 4 re-ran it.'),
    ])
  })

  it('a FAILED run shows the backend\'s error as an alert', () => {
    render(<PipelineFlow run={{ ...runAccepted, state: 'FAILED', error: 'All workers exhausted for MAP task' }} />)
    expect(screen.getByRole('alert').textContent).toBe('Failed. All workers exhausted for MAP task')
  })

  it('animates the stages only when a run this page saw RUNNING finishes, never on first load', () => {
    const { rerender } = render(<PipelineFlow run={sampleRun} />)
    expect(animated()).toHaveLength(0)   // first load of a finished run: no motion

    rerender(<PipelineFlow run={runAccepted} />)
    rerender(<PipelineFlow run={sampleRun} />)
    expect(animated()).toHaveLength(8)
    expect(screen.getByTestId('stage-split').style.animationDelay).toBe('90ms')
  })

  it('under prefers-reduced-motion: no animation and no delays', () => {
    window.matchMedia = vi.fn(() => ({ matches: true }))
    const { rerender } = render(<PipelineFlow run={runAccepted} />)
    rerender(<PipelineFlow run={sampleRun} />)
    expect(animated()).toHaveLength(0)
    expect(screen.getByTestId('stage-split').style.animationDelay).toBe('')
    expect(window.matchMedia).toHaveBeenCalledWith('(prefers-reduced-motion: reduce)')
  })
})
