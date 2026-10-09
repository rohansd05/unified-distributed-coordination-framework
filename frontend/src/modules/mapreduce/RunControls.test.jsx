import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import overviewIdle from '@/test/fixtures/mapreduce/overview-idle.json'
import uploadFileNameError from '@/test/fixtures/mapreduce/error-400-upload-file-name.json'
import crashWorkerError from '@/test/fixtures/mapreduce/error-400-crash-worker.json'
import { RunControls } from './RunControls'

afterEach(cleanup)

function renderControls(props = {}) {
  const onRun = vi.fn()
  const onRecover = vi.fn()
  render(
    <MemoryRouter>
      <RunControls
        overview={overviewIdle}
        downNodeIds={[]}
        busy={false}
        pending={null}
        fieldErrors={{}}
        actionError={null}
        onRun={onRun}
        onRecover={onRecover}
        {...props}
      />
    </MemoryRouter>,
  )
  return { onRun, onRecover }
}

const run = () => fireEvent.click(screen.getByRole('button', { name: 'Run' }))

describe('RunControls', () => {
  it('offers the jobs and inputs from the overview and runs the first of each by default', () => {
    const { onRun } = renderControls()
    expect(within(screen.getByRole('group', { name: 'Job' })).getAllByRole('radio')).toHaveLength(3)
    expect(within(screen.getByRole('group', { name: 'Input' })).getAllByRole('radio')).toHaveLength(3)
    run()
    expect(onRun).toHaveBeenCalledWith({ jobId: 'word-count', inputType: 'SAMPLE', file: null, crashWorkerId: null })
  })

  it('upload: a file over the overview\'s cap is refused before anything is sent', () => {
    const { onRun } = renderControls({ overview: { ...overviewIdle, limits: { ...overviewIdle.limits, uploadMaxBytes: 4 } } })
    fireEvent.click(screen.getByRole('radio', { name: /Your own \.txt file/ }))
    const input = screen.getByLabelText('Text file (.txt, UTF-8)')
    fireEvent.change(input, { target: { files: [new File(['too long'], 'notes.txt', { type: 'text/plain' })] } })

    expect(screen.getByText('The file is 8 bytes, larger than the 4 bytes limit.')).toBeTruthy()
    expect(input.getAttribute('aria-invalid')).toBe('true')
    run()
    expect(onRun).not.toHaveBeenCalled()
  })

  it('upload: without a file nothing is sent; a file within the cap is passed on', () => {
    const { onRun } = renderControls()
    fireEvent.click(screen.getByRole('radio', { name: /Your own \.txt file/ }))
    run()
    expect(screen.getByText('Choose a .txt file first.')).toBeTruthy()
    expect(onRun).not.toHaveBeenCalled()

    const file = new File(['hello'], 'notes.txt', { type: 'text/plain' })
    fireEvent.change(screen.getByLabelText('Text file (.txt, UTF-8)'), { target: { files: [file] } })
    run()
    expect(onRun).toHaveBeenCalledWith({ jobId: 'word-count', inputType: 'UPLOAD', file, crashWorkerId: null })
    expect(screen.getByText(/Up to 1 MiB/)).toBeTruthy()
  })

  it('shows the backend\'s 400 field message for the file under the file picker', () => {
    renderControls({ fieldErrors: uploadFileNameError.errors })
    fireEvent.click(screen.getByRole('radio', { name: /Your own \.txt file/ }))
    expect(screen.getByText('must be a .txt file')).toBeTruthy()
  })

  it('crash: every live worker but the coordinator; the chosen worker is sent', () => {
    const { onRun } = renderControls()
    fireEvent.click(screen.getByLabelText('Crash a worker right after the first task is sent to it'))
    const select = screen.getByLabelText('Worker to crash')
    expect(within(select).getAllByRole('option').map((option) => option.textContent)).toEqual(['Node 2', 'Node 3', 'Node 4', 'Node 5'])
    fireEvent.change(select, { target: { value: '3' } })
    run()
    expect(onRun).toHaveBeenCalledWith({ jobId: 'word-count', inputType: 'SAMPLE', file: null, crashWorkerId: 3 })
    expect(screen.getByText(/A worker that only receives reduce tasks/)).toBeTruthy()
  })

  it('crash: impossible with fewer than two live workers, and says why', () => {
    renderControls({ overview: { ...overviewIdle, workerIds: [1] } })
    expect(screen.getByLabelText('Crash a worker right after the first task is sent to it').disabled).toBe(true)
    expect(screen.getByText('A crash needs at least two live workers, so the task can be retried on another one.')).toBeTruthy()
  })

  it('shows the backend\'s field message for a refused crash worker', () => {
    renderControls({ fieldErrors: crashWorkerError.errors })
    fireEvent.click(screen.getByLabelText('Crash a worker right after the first task is sent to it'))
    expect(screen.getByText(crashWorkerError.errors.crashWorkerId)).toBeTruthy()
  })

  it('a run cannot start while one is active or a request is pending', () => {
    const { onRun } = renderControls({ busy: true })
    expect(screen.getByRole('button', { name: 'Run' }).disabled).toBe(true)
    run()
    expect(onRun).not.toHaveBeenCalled()
  })

  it('every down node gets a Recover button and a link to the Cluster page', () => {
    const { onRecover } = renderControls({ downNodeIds: [3] })
    const panel = screen.getByTestId('down-nodes')
    expect(panel.textContent).toContain('Node 3 is down for every experiment.')
    fireEvent.click(within(panel).getByRole('button', { name: 'Recover node 3' }))
    expect(onRecover).toHaveBeenCalledWith(3)
    expect(within(panel).getByRole('link', { name: 'Cluster page' }).getAttribute('href')).toBe('/cluster')
  })

  it('while loading: a skeleton, and Run is disabled', () => {
    renderControls({ overview: null })
    expect(document.querySelector('[aria-busy="true"]')).not.toBeNull()
    expect(screen.getByRole('button', { name: 'Run' }).disabled).toBe(true)
    expect(screen.getByTestId('module-status').textContent).toBe('Module status: —')
  })
})
