// Registers every experiment module page automatically. Each module folder's
// index.jsx default-exports { id, Page }.
const loaded = import.meta.glob('./*/index.jsx', { eager: true });
export const modulePages = Object.fromEntries(
  Object.values(loaded).map((m) => [m.default.id, m.default.Page]),
);
