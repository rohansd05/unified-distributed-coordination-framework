import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router-dom'
import '@fontsource-variable/inter'
import '@fontsource-variable/jetbrains-mono'
import './index.css'
import { PROVIDER_FUTURE, ROUTER_FUTURE, routes } from './routes'

const router = createBrowserRouter(routes, { future: ROUTER_FUTURE })

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <RouterProvider router={router} future={PROVIDER_FUTURE} />
  </StrictMode>,
)
