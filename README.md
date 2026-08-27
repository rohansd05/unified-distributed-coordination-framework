# Unified Distributed Coordination Framework

A locally deployable, non-cloud distributed systems platform that integrates ten
distributed-systems mechanisms into one coherent application, with a professional
React dashboard and real Prometheus/Grafana monitoring.

---

## Overview

Distributed-systems laboratory experiments are usually written as unrelated standalone
programs. This project instead builds **one functioning distributed system** in which
multiple independent nodes communicate and coordinate, and treats each experiment as a
module of that single system.

Four JVM instances run from one codebase — a gateway and three nodes — differentiated
only by Spring profile. They elect leaders, replicate data, balance load, detect
failures and recover, while exposing live metrics to Prometheus and Grafana.

---

## Problem Statement

Existing distributed systems primarily address individual challenges such as clock
synchronization, leader election, load balancing, multithreading, data replication or
fault tolerance independently. This fragmented approach limits the ability to observe
how these mechanisms interact within a common distributed environment. There is
therefore a need for a lightweight, integrated distributed framework that combines
synchronized communication, dynamic leader election, adaptive load balancing,
multithreaded request handling, data replication and fault tolerance into a single
non-cloud distributed system, and provides measurable performance and recovery
behaviour.

---

## What This Project Does

Through the web interface a user can:

- see distributed nodes and their live roles and health
- generate concurrent client requests and observe multithreaded processing
- observe Lamport logical clock synchronization across nodes
- trigger Bully and Ring leader elections, and kill the current leader
- observe round-robin and least-connections load balancing
- inspect primary-backup data replication and its latency
- simulate node failures and observe automatic failover and recovery
- run a MapReduce job on an uploaded text file
- demonstrate MPI-style broadcast, scatter and gather
- run parallel matrix multiplication and compare speedup
- monitor everything through Prometheus and Grafana

---

## Features

- **Real distributed nodes.** Separate JVM processes communicating over HTTP; no simulation.
- **Live metrics only.** Every displayed value is read from actual system state at request time.
- **Configurable deployment.** Runs on one machine via ports, or across a LAN via configured node addresses.
- **Live updates.** STOMP WebSocket pushes events to the dashboard as they happen.
- **Full observability.** Micrometer → Prometheus → Grafana, every metric tagged by node.
- **Tested.** Unit, integration, distributed, frontend and E2E test layers.

---

## Experiment Coverage

| # | Experiment | Implementation |
|---|---|---|
| 2 | Multithreading | `ThreadPoolExecutor`, bounded queue, backpressure, live pool metrics |
| 3 | Clock Synchronization | Lamport logical clock, piggybacked via HTTP interceptors |
| 4 | Leader Election | Bully and Ring algorithms with heartbeat-based failure detection |
| 5 | Data Consistency & Replication | Primary-backup with ACK and Lamport-based conflict resolution |
| 6 | Load Balancing | Round Robin and Least Connections over real node workload |
| 7 | MapReduce | Lightweight in-framework Map → Shuffle → Reduce pipeline |
| 8 | Fault Tolerance | Failure detection, backup promotion, service restoration |
| 9 | MPI Collectives | Broadcast, Scatter, Gather over Java RMI |
| 10 | Parallel Matrix Multiplication | Row-block partitioning across nodes via RMI, with speedup analysis |

---

## Architecture
                    CLIENT (browser)
                          |
                  React Dashboard :5173
                          |
                 Spring Boot Gateway :8080
                          |
                    Load Balancer
                          |
    +---------------------+---------------------+
    |                     |                     |
Node 1 :8081          Node 2 :8082          Node 3 :8083
 LEADER                 WORKER                BACKUP
    |                     |                     |
    +---------------------+---------------------+
                          |
     Threads · Clock Sync · Replication · Election
          Fault Detection · Failover · MapReduce
                 MPI Collectives · Matrix
                          |
                Micrometer / Actuator
                          |
                   Prometheus :9090
                          |
                    Grafana :3000
                    
---

## Technology Stack

**Backend** Java 21 · Spring Boot 3.3 · Spring Web · Spring Data JPA · Actuator ·
Micrometer · WebSocket (STOMP) · Java RMI · Maven

**Frontend** React 18 · Vite · Tailwind CSS · shadcn/ui · Recharts · Lucide React ·
STOMP.js · axios

**Data & Monitoring** PostgreSQL 16 · Prometheus · Grafana · Docker

**Testing** JUnit 5 · Mockito · AssertJ · MockMvc · Awaitility · Vitest ·
React Testing Library · Playwright

---

## Repository Structure

Unified-Distributed-Coordination-Framework/
├── backend/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/udcf/
│   │   │   │   ├── UdcfBackendApplication.java
│   │   │   │   ├── config/
│   │   │   │   ├── controller/
│   │   │   │   ├── dto/
│   │   │   │   ├── model/
│   │   │   │   ├── service/
│   │   │   │   ├── threadpool/      # Exp 2
│   │   │   │   ├── sync/            # Exp 3
│   │   │   │   ├── election/        # Exp 4
│   │   │   │   ├── replication/     # Exp 5
│   │   │   │   ├── loadbalancer/    # Exp 6
│   │   │   │   ├── mapreduce/       # Exp 7
│   │   │   │   ├── fault/           # Exp 8
│   │   │   │   ├── mpi/             # Exp 9  (RMI)
│   │   │   │   ├── matrix/          # Exp 10 (RMI)
│   │   │   │   ├── monitoring/
│   │   │   │   └── demo/            # standalone console demos
│   │   │   └── resources/
│   │   │       ├── application.yml
│   │   │       ├── application-gateway.yml
│   │   │       ├── application-node1.yml
│   │   │       ├── application-node2.yml
│   │   │       └── application-node3.yml
│   │   └── test/java/com/udcf/      # mirrors main/
│   └── pom.xml
│
├── frontend/
│   ├── src/
│   │   ├── components/
│   │   │   ├── layout/
│   │   │   ├── ui/                  # shadcn
│   │   │   ├── nodes/
│   │   │   ├── charts/
│   │   │   └── onboarding/
│   │   ├── pages/
│   │   ├── services/
│   │   ├── hooks/
│   │   ├── lib/
│   │   ├── App.jsx
│   │   └── main.jsx
│   ├── e2e/
│   ├── public/
│   ├── index.html
│   ├── package.json
│   ├── vite.config.js
│   ├── tailwind.config.js
│   ├── postcss.config.js
│   └── playwright.config.js
│
├── infra/
│   ├── docker-compose.yml
│   ├── prometheus/
│   │   └── prometheus.yml
│   └── grafana/
│       ├── dashboards/
│       │   └── udcf-overview.json
│       └── provisioning/
│           ├── datasources/datasource.yml
│           └── dashboards/dashboards.yml
│
├── database/
│   ├── schema.sql
│   └── seed.sql
│
├── scripts/
│   ├── start-all.ps1
│   ├── start-nodes.ps1
│   ├── stop-nodes.ps1
│   ├── start-monitoring.ps1
│   └── run-tests.ps1
│
├── docs/
│   ├── architecture/
│   ├── experiments/
│   ├── research/
│   └── testing/
│
├── .env.example
├── .env                  # gitignored
├── .gitignore
├── CLAUDE.md
├── README.md
├── CONTRIBUTING.md
├── LICENSE
└── requirements.txt

---

## System Requirements

- JDK 21+
- Maven 3.9+
- Node.js 20+ LTS and npm 10+
- PostgreSQL 16+
- Docker Desktop (for Prometheus and Grafana)
- Git
- Visual Studio Code

See `requirements.txt` for the full list, including recommended VS Code extensions.

---

## Installation

### 1. Clone

```bash
git clone <repository-url>
cd Unified-Distributed-Coordination-Framework
```

### 2. Configure environment

```bash
cp .env.example .env
```

Edit `.env` if your PostgreSQL password or ports differ from the defaults.

### 3. Create the database

```bash
psql -U postgres -f database/schema.sql
```

### 4. Start monitoring

```powershell
docker compose -f infra/docker-compose.yml up -d
```

Prometheus at http://localhost:9090 · Grafana at http://localhost:3000

### 5. Build and start the backend

```powershell
cd backend
mvn clean install
```

Then start four instances (four terminals, or use the script):

```powershell
..\scripts\start-all.ps1
```

| Instance | Port |
|---|---|
| Gateway | 8080 |
| Node 1 | 8081 |
| Node 2 | 8082 |
| Node 3 | 8083 |

### 6. Start the frontend

```powershell
cd frontend
npm install
npm run dev
```

Open http://localhost:5173

---

## First-Time Onboarding

On first visit the application runs a five-step guided setup rather than dropping you
into the dashboard:

1. **Welcome** — what the framework is
2. **System Health Check** — verifies backend, database, all three nodes, Prometheus and Grafana
3. **Node Roles** — explains Leader, Worker and Backup
4. **First Demonstration** — generates ten concurrent requests and shows them processed across threads
5. **Dashboard** — hands control over

Onboarding can be skipped at any point and restarted later from Settings.

---

## Quick Start

```powershell
docker compose -f infra/docker-compose.yml up -d   # monitoring
.\scripts\start-all.ps1                            # gateway + 3 nodes
cd frontend; npm run dev                           # dashboard
```

---

## Recommended First Demo

Open the website

-> complete onboarding
-> check node health
-> generate 100 concurrent requests
-> observe multithreading and logical clocks
-> kill the current leader
-> run a Bully election, then a Ring election
-> update replicated data and verify the backup
-> increase load and observe load balancing
-> kill the primary and observe failover
-> run MapReduce on a text file
-> run MPI broadcast, scatter and gather
-> run parallel matrix multiplication
-> open Grafana and analyse the metrics


---

## Monitoring

Prometheus scrapes all four instances at `/actuator/prometheus`. Every metric carries
`node_id` and `role` labels so all instances can be compared on one panel.

Key series:

distributed_requests_total distributed_active_threads
distributed_node_status distributed_node_load
distributed_leader_elections_total distributed_election_duration
distributed_clock_value distributed_replication_latency
distributed_replication_failures_total
distributed_failures_total distributed_recovery_duration
distributed_map_tasks_total distributed_reduce_tasks_total
distributed_mpi_messages_total distributed_matrix_execution_duration


Grafana is provisioned automatically with its datasource and the project dashboard.
Default credentials are set in `.env`.

---

## Testing

```powershell
cd backend;  mvn test          # unit and integration
cd backend;  mvn verify        # full verification
cd frontend; npm run test      # component and service tests
cd frontend; npm run test:coverage
cd frontend; npx playwright test   # end-to-end
```

Or run everything:

```powershell
.\scripts\run-tests.ps1
```

Detailed testing strategy is documented in `docs/testing/`.

---

## Documentation

| Location | Contents |
|---|---|
| `docs/architecture/` | System design and architectural decision records |
| `docs/experiments/` | Per-experiment implementation notes |
| `docs/research/` | Research gap and reviewed literature |
| `docs/testing/` | Test strategy and coverage |

---

## Contributors

- **Nidhi Dhyani** — `2024300050`
- **Rohan Dhumal** — `2024300049`
- **Swanand Dixit** — `2024300052`
- **Jai Desai** — `2024300041`
