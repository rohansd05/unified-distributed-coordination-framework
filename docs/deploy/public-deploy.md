# UDCF public lite deployment (Render + Vercel)

Step 16.1, 2026-10-19. The backend runs as a Docker web service on Render's free tier with the
`public` Spring profile; the frontend is a static Vite build on Vercel Hobby. The browser talks to
Render directly for REST and WebSocket; Vercel only serves files (HANDOFF 10.2). The laptop
(`docs/demo/udcf-demo.md`) is the fallback for the 2026-10-23 demo.

---

## 1. Platform limits (checked 2026-10-19)

| Item | Current value | Source |
|---|---|---|
| Render free web service: RAM / CPU | 512 MB, 0.1 CPU | [render.com/pricing](https://render.com/pricing) (via search; the table is rendered client side) |
| Render: spin-down | after 15 minutes without inbound traffic | [render.com/docs/free](https://render.com/docs/free) |
| Render: what counts as traffic | "HTTP requests and WebSocket messages from existing connections" (inbound only) | [render.com/docs/free](https://render.com/docs/free); [changelog 2026-02-24](https://render.com/changelog/free-web-services-now-remain-active-while-receiving-websocket-messages) |
| Render: spin-up (cold start) | "about one minute"; triggered by an HTTP request or a new WebSocket connection | [render.com/docs/free](https://render.com/docs/free) |
| Render: free hours | 750 instance hours per workspace per calendar month | [render.com/docs/free](https://render.com/docs/free) |
| Render: other | may restart a free service at any time; filesystem is ephemeral (fine: R3, all state in memory); bandwidth and build minutes count against monthly included amounts | [render.com/docs/free](https://render.com/docs/free) |
| Vercel Hobby: use | personal, non-commercial only | [vercel.com/docs/plans/hobby](https://vercel.com/docs/plans/hobby) (updated 2026-09-14) |
| Vercel Hobby: transfer | first 100 GB Fast Data Transfer, first 1,000,000 CDN requests per month | same |
| Vercel Hobby: builds | 100 deployments per day, 1 concurrent build, 45 min per build, 2 build vCPUs / 8 GB | [vercel.com/docs/limits](https://vercel.com/docs/limits) (updated 2026-09-16); hobby page |
| Vercel Hobby: Git | cannot connect a Hobby project to a repository owned by a Git **organization** (ours is under a personal account) | [vercel.com/docs/limits](https://vercel.com/docs/limits) |

**What that means for UDCF**
- One JVM hosting 3 nodes in 512 MB at 0.1 CPU: everything is about 10 times slower than on the
  laptop. The `public` profile caps sizes for that (section 3).
- The WebSocket rule makes an open page keep the service awake: the STOMP client sends a heartbeat
  every 10 s (`frontend/src/services/stomp/StompProvider.jsx:28`, `heartbeatOutgoing: 10000`; the
  broker agrees 10 s in `backend/src/main/java/com/udcf/web/WebSocketConfig.java:61`). That is an
  inbound WebSocket message every 10 s. This is inferred from Render's documented rule; confirm it
  with smoke check 4 in the track file.

---

## 2. Backend on Render

The service `udcf-backend` exists since step 2.5 (`render.yaml`). Settings, by their dashboard names
(service > **Settings**):

| Setting | Value |
|---|---|
| Source / Repository | this repository, **Branch** `main` |
| Root Directory | `backend` |
| Language | Docker |
| Dockerfile Path | `Dockerfile` (relative to the root directory; `render.yaml`: `dockerfilePath: Dockerfile`) |
| Docker Build Context Directory | `.` (that is, `backend/`) |
| Instance Type | Free |
| Region | Singapore |
| Health Check Path | `/actuator/health` |
| Auto-Deploy | On Commit |
| Build Filters | Included paths `backend/**` (a frontend-only merge does not rebuild the backend) |

**Environment** (service > **Environment** > Environment Variables). Names only; never paste values
into the repository.

| Variable | Value | What it is for |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `public` | loads `application-public.yml` (3 nodes, smaller limits) |
| `UDCF_ALLOWED_ORIGINS` | the Vercel production origin, `https://<project>.vercel.app` (no trailing slash; comma-separated if more than one) | CORS for `/api/**` and the allowed origins of `/ws`. Preview URLs are refused on purpose. |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k` (recommended) | JVM memory for 512 MB: heap at most about 358 MB, leaving room for metaspace, thread stacks and the socket buffers of 3 nodes; Serial GC has no extra GC threads and the smallest footprint on 0.1 CPU; 512 KB thread stacks. The JVM prints "Picked up JAVA_TOOL_OPTIONS: ..." as the first log line, which proves it was read. |
| `PORT` | do not set | Render sets it; the backend listens on `${PORT:8080}` |
| `UDCF_CLUSTER_SIZE` | do not set | not referenced by name, but Spring's relaxed binding maps it onto `udcf.cluster.size` and it would override the profile's 3 |
| `LOGGING_LEVEL_COM_UDCF` | unset; `DEBUG` only while troubleshooting | log level of `com.udcf` (relaxed binding of `logging.level.com.udcf`). The `LOG_LEVEL_UDCF` name in `.env.example` is not read by the backend. |

The Dockerfile (16.1) no longer sets `JAVA_TOOL_OPTIONS`; it builds with `maven:3.9.9-eclipse-temurin-21`
(`mvn -B -DskipTests package`, because the wrapper's checksum check fails inside Docker, as in
9A.1) and runs on `eclipse-temurin:21-jre` as user 10001.

**Redeploy:** a merge into `main` that touches `backend/**` deploys automatically. By hand: service >
**Manual Deploy** > **Deploy latest commit**; after a Dockerfile or dependency change use **Clear
build cache & deploy**. Changing an environment variable triggers a redeploy when you save it.

**Logs:** service > **Logs** (live tail; filter box at the top). Deploy and spin-down history:
service > **Events**. A healthy start ends with Spring's "Started UdcfBackendApplication in n seconds".

---

## 3. The `public` profile (`backend/src/main/resources/application-public.yml`)

| Setting | Public | Default (`application.yml`) | Owner |
|---|---|---|---|
| `udcf.system.mode` | `public` | `local` | core |
| `udcf.cluster.size` | **3** (FAST, MEDIUM, SLOW) | 5 | core |
| `udcf.events.buffer-size` | 1500 | 5000 | core |
| `udcf.rmi.max-hash-rounds` | 100000 | 1000000 | Jai |
| `udcf.rmi.max-failure-message-length` | 300 | 300 | Jai |
| `udcf.multithreading.queue-capacity` | 100 | 200 | Nidhi |
| `udcf.clocksync.default-traffic-seconds` / `max-traffic-seconds` | 5 / 15 | 5 / 30 | Rohan |
| `udcf.clocksync.default-messages-per-second` / `max-messages-per-second` | 4 / 10 | 4 / 20 | Rohan |
| `udcf.clocksync.retained-events-capacity` | 1000 | 2000 | Rohan |
| `udcf.loadbalancing.limits.max-request-count` / `max-work-units` / `max-concurrency` / `max-total-work` | 200 / 500 / 24 / 120000 | see `application.yml` | Nidhi |
| `udcf.mapreduce.max-request-bytes` / `upload-max-bytes` | 1048576 / 262144 | see `application.yml` | Rohan |
| `udcf.faulttolerance.stream.max-rate-per-second` / `max-updates` | 5 / 200 | see `application.yml` | Swanand |
| `udcf.mpi.max-payload-elements` / `max-failure-message-length` | 10000 / 300 | see `application.yml` | Jai |
| `udcf.matrix.series-sizes` | [25, 50, 100] (max size 100 = sqrt of 10000) | see `application.yml` | Jai |
| `udcf.scenario.slow-node.work-units` | 400 | 900 | Swanand |
| `management.endpoints.web.exposure.include` | `health,info` | see `application.yml` | core |
| `management.endpoint.health.show-details` | `never` | see `application.yml` | core |

Startup checks pass with these values: `ClusterProperties` allows size 2–9; the load-balancing check
5 x 60 x 400 = 120000 <= `max-total-work` 120000 is met exactly; the matrix maximum 100 >= its series.

**Three nodes instead of five (16.1 sweep).** Nothing breaks: modules and scenarios read node ids,
live nodes, the leader and capacities at run time, and no code needs a quorum or five nodes. What
differs from the laptop demo:
- The first election makes **node 3** leader (Bully: highest live id), not node 5; after crashing it,
  **node 2** takes over. Node 3 is also the SLOW node.
- "A slow node under load" finds the SLOW node by capacity; with 3 nodes round robin sends a third
  of the batch to it (instead of a fifth). 400 work units were not calibrated on 0.1 CPU; the
  result is measured and can fail honestly.
- "The leader dies during a matrix multiply" uses the largest size, 100, on 0.1 CPU: the slowest
  scenario here.
- Overview says "the same five nodes" (gap for Nidhi; see the track file).

---

## 4. Frontend on Vercel

Project > **Settings** > **Build and Deployment** (and **General**):

| Setting | Value |
|---|---|
| Framework Preset | Vite |
| Root Directory | `frontend` |
| Build Command | `npm run build` |
| Output Directory | `dist` |
| Install Command | default (`npm install`) |
| Node.js Version | 22.x |
| Production Branch | `main` |

`frontend/vercel.json` rewrites every path to `/index.html`, so a refresh on `/scenarios` works.

**Environment Variables** (Project > **Settings** > **Environment Variables**, environment
**Production**). Vite bakes them into the build: after changing one, redeploy.

| Variable | Value | What it is for |
|---|---|---|
| `VITE_API_BASE_URL` | `https://<render-service>.onrender.com` | REST base URL. Must start with `http(s)://` (`frontend/src/lib/config.js`), otherwise the app shows the configuration error page. |
| `VITE_WS_URL` | `wss://<render-service>.onrender.com/ws` | STOMP WebSocket. Must start with `ws(s)://`. |
| `VITE_GRAFANA_URL` | do not set | without it the Monitoring page and its sidebar entry are hidden (no Grafana in public mode) |

**Redeploy:** a merge into `main` deploys automatically. By hand: **Deployments** > the latest
production deployment > **...** > **Redeploy**.
**Logs:** **Deployments** > a deployment > **Build Logs**. There are no runtime logs to read: the site
is static. Browser problems (CORS, WebSocket) show in the browser's developer tools console.

---

## 5. Before a demo on the public site

1. **T-10:** open the Vercel URL. The first request wakes Render (about a minute; the top bar shows
   the backend as unreachable or reconnecting until it is up). Keep that tab open: its WebSocket
   heartbeat keeps the service awake (section 1).
2. **T-5:** Overview shows 3 nodes up. Do the warm-up from `docs/demo/udcf-demo.md` section b
   (Exp 3 round, Exp 7 run, Exp 9 collective, Start experiment 8); expect "leader: node 3".
3. If anything is wrong, switch to the laptop (`scripts\start-all.ps1`); do not debug on stage.

---

## 6. Smoke run and measured numbers

Script: `docs/deploy/smoke-public.ps1` (Windows PowerShell 5.1; read-only except for cluster
resets and the actions below).

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\docs\deploy\smoke-public.ps1 -Api https://<render-service>.onrender.com
```

It times `GET /api/system/health` from cold (retrying up to 240 s), checks `/actuator/health`,
`/api/system/info`, `/api/cluster` and `/api/modules`, resets the cluster (waits until no module is
BUSY, retries 409 up to 20 times), runs one cheap action per experiment (RMI ping, an Exp 2 batch,
a Bully election, a Berkeley round, a synchronous write, a round-robin run, a word count, Start
experiment 8, an MPI broadcast, a 25 x 25 matrix run), then starts every scenario from
`GET /api/scenarios` and polls it to the end. It ends with the verdicts and a table: check, HTTP
code, seconds, OK.

**Measured** (to be filled from Rohan's run; nothing below is measured yet):

| Check | Result |
|---|---|
| Cold start (health from cold) | _pending_ |
| Warm health | _pending_ |
| Ten experiment actions OK | _pending_ / 13 checks |
| Scenarios passed | _pending_ / 5 |
| Slowest scenario | _pending_ |
| Stayed awake with a page open for 20 min | _pending_ |
