import { useId, useState } from 'react'
import { Link } from 'react-router-dom'
import { AlertTriangle, CircleX } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { moduleStatusLabel } from './labels'
import { crashChoices, overviewModel } from './runModel'
import { checkUpload, formatBytes } from './uploadFile'

const UPLOAD_FIELDS = ['upload', 'upload.fileName', 'upload.contentType', 'upload.contentBase64']

/** A group of radio cards (jobs or input types) from the overview. */
function ChoiceGroup({ legend, name, options, value, onChange, error }) {
  const errorId = `${name}-error`
  return (
    <fieldset className="space-y-2" aria-describedby={error ? errorId : undefined}>
      <legend className="text-sm font-medium">{legend}</legend>
      {options.map((option) => (
        <label
          key={option.id}
          className={cn(
            'flex cursor-pointer items-start gap-3 rounded-lg border bg-card p-3 text-sm focus-within:ring-2 focus-within:ring-ring',
            value === option.id && 'border-primary bg-primary/10',
          )}
        >
          <input
            type="radio"
            name={name}
            value={option.id}
            checked={value === option.id}
            onChange={() => onChange(option.id)}
            className="mt-1 accent-primary"
          />
          <span className="space-y-0.5">
            <span className="block font-medium">{option.title}</span>
            <span className="block text-xs text-muted-foreground">{option.description}</span>
          </span>
        </label>
      ))}
      {error && <p id={errorId} className="text-xs text-destructive">{error}</p>}
    </fieldset>
  )
}

/**
 * Experiment 7's controls: job and input (from the overview), the .txt file for an upload (checked
 * against the overview's cap before anything is sent), the optional worker to crash during the
 * run, and Run. Shows the module status, the backend's error for a refused request (field
 * messages next to their field), and every crashed node with a Recover button and a link to the
 * Cluster page. Nothing here keeps the file beyond the run request.
 *
 * @param {object} props
 * @param {object|null} props.overview GET /api/modules/mapreduce
 * @param {number[]} props.downNodeIds crashed nodes (GET /api/cluster)
 * @param {boolean} props.busy a run is active
 * @param {string|null} props.pending 'run', 'recover-<id>' or null
 * @param {object} props.fieldErrors the backend's errors map for the last refused request
 * @param {{title: string, detail: string}|null} props.actionError
 * @param {(command: {jobId: string, inputType: string, file: File|null, crashWorkerId: number|null}) => void} props.onRun
 * @param {(nodeId: number) => void} props.onRecover
 */
export function RunControls({ overview, downNodeIds, busy, pending, fieldErrors = {}, actionError, onRun, onRecover }) {
  const model = overviewModel(overview)
  const ids = useId()
  const [jobId, setJobId] = useState(null)
  const [inputType, setInputType] = useState(null)
  const [file, setFile] = useState(null)
  const [fileError, setFileError] = useState(null)
  const [crashEnabled, setCrashEnabled] = useState(false)
  const [crashWorkerId, setCrashWorkerId] = useState(null)

  const job = jobId ?? model.jobs[0]?.id ?? null
  const input = inputType ?? model.inputTypes[0]?.id ?? null
  const maxBytes = model.limits?.uploadMaxBytes ?? null
  const { candidates, reason: crashBlocked } = crashChoices(overview)
  const crashTarget = candidates.includes(crashWorkerId) ? crashWorkerId : candidates[0] ?? null
  const crashOn = crashEnabled && crashTarget !== null
  const uploadError = fileError ?? UPLOAD_FIELDS.map((field) => fieldErrors[field]).find(Boolean) ?? null
  const canRun = Boolean(overview) && job !== null && input !== null && !busy && pending === null

  const chooseFile = (event) => {
    const chosen = event.target.files?.[0] ?? null
    setFile(chosen)
    setFileError(chosen ? checkUpload(chosen, maxBytes) : null)
  }

  const submit = (event) => {
    event.preventDefault()
    if (input === 'UPLOAD') {
      const problem = checkUpload(file, maxBytes)
      if (problem) {
        setFileError(problem)
        return
      }
    }
    onRun({ jobId: job, inputType: input, file: input === 'UPLOAD' ? file : null, crashWorkerId: crashOn ? crashTarget : null })
  }

  return (
    <form onSubmit={submit} noValidate aria-label="Run a MapReduce job" className="space-y-6">
      {!overview && <div className="h-40 animate-pulse rounded-lg bg-card/60 motion-reduce:animate-none" aria-busy="true" />}

      {overview && (
        <div className="grid gap-6 lg:grid-cols-2">
          <ChoiceGroup legend="Job" name={`${ids}-job`} options={model.jobs} value={job} onChange={setJobId} error={fieldErrors.jobId} />
          <div className="space-y-4">
            <ChoiceGroup legend="Input" name={`${ids}-input`} options={model.inputTypes} value={input} onChange={setInputType} error={fieldErrors.inputType} />

            {input === 'UPLOAD' && (
              <div className="space-y-1">
                <label htmlFor={`${ids}-file`} className="text-sm font-medium">Text file (.txt, UTF-8)</label>
                <input
                  id={`${ids}-file`}
                  type="file"
                  accept=".txt,text/plain"
                  onChange={chooseFile}
                  aria-invalid={uploadError ? 'true' : undefined}
                  aria-describedby={`${ids}-file-help`}
                  className="block w-full text-sm file:mr-3 file:rounded-md file:border file:bg-secondary file:px-3 file:py-1.5 file:text-sm file:text-foreground"
                />
                <p id={`${ids}-file-help`} className={cn('text-xs', uploadError ? 'text-destructive' : 'text-muted-foreground')}>
                  {uploadError ?? `Up to ${formatBytes(maxBytes)}. The file is read in your browser, sent once with the run, and not stored.`}
                </p>
              </div>
            )}

            <fieldset className="space-y-3 rounded-lg border bg-card p-3">
              <legend className="px-1 text-sm font-medium">Crash a worker during the run</legend>
              <label className="flex items-start gap-3 text-sm">
                <input
                  type="checkbox"
                  checked={crashOn}
                  disabled={candidates.length === 0}
                  onChange={(event) => setCrashEnabled(event.target.checked)}
                  className="mt-1 accent-primary"
                />
                <span>Crash a worker right after the first task is sent to it</span>
              </label>
              {crashBlocked && <p className="text-xs text-muted-foreground">{crashBlocked}</p>}
              {crashOn && (
                <div className="space-y-1">
                  <label htmlFor={`${ids}-crash`} className="text-sm font-medium">Worker to crash</label>
                  <select
                    id={`${ids}-crash`}
                    value={crashTarget ?? ''}
                    onChange={(event) => setCrashWorkerId(Number(event.target.value))}
                    aria-describedby={fieldErrors.crashWorkerId ? `${ids}-crash-error` : undefined}
                    className="h-9 w-full rounded-md border bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  >
                    {candidates.map((id) => <option key={id} value={id}>Node {id}</option>)}
                  </select>
                  {fieldErrors.crashWorkerId && (
                    <p id={`${ids}-crash-error`} className="text-xs text-destructive">{fieldErrors.crashWorkerId}</p>
                  )}
                </div>
              )}
              <p className="text-xs leading-relaxed text-muted-foreground">
                The stage is not chosen: the crash happens right after the first task of any kind reaches that worker, and
                the run shows which stage was hit. A worker that only receives reduce tasks (a small input, fewer lines than
                workers) gives a reduce-stage crash. The node stays down, for every experiment, until you recover it.
              </p>
            </fieldset>
          </div>
        </div>
      )}

      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" disabled={!canRun}>{pending === 'run' ? 'Starting…' : 'Run'}</Button>
        <p className="text-sm" data-testid="module-status">
          Module status: <span className="font-medium">{overview ? moduleStatusLabel(model.status) : '—'}</span>
          {model.currentAction && <span className="text-muted-foreground"> ({model.currentAction})</span>}
        </p>
      </div>

      {actionError && (
        <div role="alert" data-testid="action-error" className="flex items-start gap-2 rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-sm">
          <AlertTriangle aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-destructive" />
          <span>
            <span className="font-semibold">{actionError.title}.</span> {actionError.detail}
            {actionError.status === 409 && actionError.title === 'No live worker' && (
              <> <Link to="/cluster" className="text-primary underline underline-offset-2">Open the Cluster page</Link>.</>
            )}
          </span>
        </div>
      )}

      {downNodeIds.length > 0 && (
        <div data-testid="down-nodes" className="space-y-2 rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-sm">
          {downNodeIds.map((nodeId) => (
            <div key={nodeId} className="flex flex-wrap items-center justify-between gap-2">
              <span className="inline-flex items-center gap-2">
                <CircleX aria-hidden="true" className="size-4 text-destructive" />
                Node {nodeId} is down for every experiment.
              </span>
              <Button type="button" variant="outline" size="sm" disabled={pending !== null} onClick={() => onRecover(nodeId)}>
                {pending === `recover-${nodeId}` ? 'Recovering…' : `Recover node ${nodeId}`}
              </Button>
            </div>
          ))}
          <p className="text-xs text-muted-foreground">
            Recovering brings the node back for every experiment. <Link to="/cluster" className="text-primary underline underline-offset-2">Cluster page</Link>
          </p>
        </div>
      )}
    </form>
  )
}
