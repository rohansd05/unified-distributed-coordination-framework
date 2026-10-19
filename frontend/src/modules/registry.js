import { lazy } from 'react'

// Registers every experiment module page automatically. Each module folder's
// index.jsx default-exports { id, Page }, and the folder is named after the id.
// Pages load on demand, one chunk per module (14.1); render them inside <Suspense>.
const loaded = import.meta.glob('./*/index.jsx');
export const pageLoaders = Object.fromEntries(
  Object.entries(loaded).map(([path, load]) => [path.split('/')[1], () => load().then((m) => m.default)]),
);
export const modulePages = Object.fromEntries(
  Object.entries(pageLoaders).map(([id, load]) => [id, lazy(() => load().then((m) => ({ default: m.Page })))]),
);
