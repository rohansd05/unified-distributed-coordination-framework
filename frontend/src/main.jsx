import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router-dom'
import '@fontsource-variable/inter'
import '@fontsource-variable/jetbrains-mono'
import './index.css'
import { PROVIDER_FUTURE, ROUTER_FUTURE, routes } from './routes'
import { getConfig } from './lib/config'
import { StompProvider } from './services/stomp/StompProvider'
import { ClusterProvider } from './services/cluster/ClusterProvider'
import { ConfigurationError } from './components/ConfigurationError'

const config = getConfig()
const router = createBrowserRouter(routes, { future: ROUTER_FUTURE })

createRoot(document.getElementById('root')).render(
  <StrictMode>
    {config.error ? (
      <ConfigurationError error={config.error} errors={config.errors} />
    ) : (
      <StompProvider wsUrl={config.wsUrl}>
        <ClusterProvider>
          <RouterProvider router={router} future={PROVIDER_FUTURE} />
        </ClusterProvider>
      </StompProvider>
    )}
  </StrictMode>,
)
