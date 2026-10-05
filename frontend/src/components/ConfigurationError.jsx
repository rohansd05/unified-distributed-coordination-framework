/**
 * Error display rendered when environment configuration is missing or invalid.
 */
export function ConfigurationError({ error, errors }) {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4 text-foreground">
      <div className="max-w-md space-y-4 rounded-lg border border-destructive/50 bg-destructive/10 p-6 text-center">
        <h1 className="text-xl font-bold text-destructive">Configuration error</h1>
        <p className="text-sm text-muted-foreground">{error}</p>
        {errors && errors.length > 0 && (
          <ul className="space-y-1 text-left text-xs font-mono text-destructive">
            {errors.map((err, i) => (
              <li key={i}>• {err}</li>
            ))}
          </ul>
        )}
        <p className="text-xs text-muted-foreground">
          Please check your <code className="rounded bg-muted px-1.5 py-0.5 font-mono">.env</code> configuration file.
        </p>
      </div>
    </div>
  )
}
