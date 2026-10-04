/** A page's title (the only h1 on the page) and an optional one-line description. */
export function PageHeader({ title, description, children }) {
  return (
    <header className="space-y-2">
      <div className="flex flex-wrap items-center gap-3">
        {children}
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
      </div>
      {description && <p className="text-muted-foreground">{description}</p>}
    </header>
  )
}
