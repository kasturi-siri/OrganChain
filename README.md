# OrganChain

A blockchain-based Organ Donation & Allocation System built on **Corda 4.12**, with AES-256-GCM encryption of all patient PII, a multi-party workflow engine, and a Spring Boot REST API serving a role-based SPA frontend.

---

## Architecture Overview

```
                         ┌─────────────────────┐
                         │   Notary : 10002/03  │
                         │  (Non-validating)    │
                         └──────────┬──────────┘
                                    │ notarisation
          ┌─────────────────────────┼──────────────────────────┐
          │                         │                           │
   ┌──────┴──────┐          ┌───────┴───────┐         ┌────────┴───────┐
   │  HospitalA  │          │  HospitalB    │         │  AdminNode     │
   │  10005/06   │          │  10008/07     │         │  10011/12      │
   │  Hyderabad  │          │  Mumbai       │         │  Chennai       │
   └──────┬──────┘          └───────┬───────┘         └────────┬───────┘
          │ RegisterDonorFlow        │                           │ MatchConfirmationFlow
          │ RegisterRecipientFlow    │                           │
          └──────────────┬──────────┘                           │
                         │ counterparty resolution               │
                  ┌──────▼──────────┐                           │
                  │ MatchingAuth    │◄──────────────────────────┘
                  │  10020/21       │  OrganMatchingFlow (decrypts
                  │  Chennai        │  PII in-memory, runs Algorithm 1)
                  └─────────────────┘
                         │
          ┌──────────────┼──────────────┐
          │              │              │
   ┌──────┴──────┐ ┌─────┴──────┐ ┌────┴──────────┐
   │  Government │ │ Transporter│ │  Spring Boot  │
   │  10014/15   │ │ 10017/18   │ │  :8080        │
   │  Delhi      │ │ Chennai    │ │  (REST + SPA) │
   └─────────────┘ └────────────┘ └───────────────┘
```

---

## Prerequisites

- **JDK 17** — required by Corda 4.12 + Quasar fibers. Java 21 is untested.
  Verify: `java -version` must show `17.x`
- **Gradle** — the included wrapper (`gradlew` / `gradlew.bat`) handles the correct version automatically. No separate Gradle installation needed.
- **RAM** — 16 GB recommended (7 Corda nodes + Spring Boot server run concurrently in development)
- **No Node.js** — the frontend is static HTML/CSS/JS served by Spring Boot from `src/main/resources/static/`

---

## Security Model

| Layer | Mechanism |
|---|---|
| **Data encryption** | AES-256-GCM — all donor/recipient PII (name, contact, blood type, organ type, age, weight, height, location, deceased flag) is encrypted before any ledger write |
| **Key derivation** | PBKDF2-SHA256 — symmetric keys managed by `KeyVaultService` on the MatchingAuthority node |
| **Two-layer validation** | Flow layer: validates plaintext before encryption (age > 0, weight > 0, etc.); Contract layer: validates only that encrypted fields are non-blank ciphertexts |
| **Notary** | Prevents double-assignment of a single donor state to multiple recipients |
| **Key isolation** | Only the MatchingAuthority node holds decryption keys — hospitals never see plaintext on the ledger |
| **RBAC** | 6 roles enforced in the Spring Boot controller layer and replicated in the frontend |

---

## Build Instructions

```bash
# 1 — Clone the repository
git clone <repo-url> && cd organchain

# 2 — Build all three modules (contracts, workflows, clients)
./gradlew build          # Linux / macOS
gradlew.bat build        # Windows

# 3 — Bootstrap the 7-node test network
./gradlew deployNodes
# Output: build/nodes/  (one directory per node + runnodes script)
```

---

## Starting the Network

Each node must be started in its own terminal. Navigate to the project root and run:

**Windows:**
```
build\nodes\runnodes.bat
```

**Linux / macOS:**
```
build/nodes/runnodes
```

If `runnodes` is not available, start each node individually:
```bash
java -jar build/nodes/Notary/corda.jar
java -jar build/nodes/HospitalA/corda.jar
java -jar build/nodes/HospitalB/corda.jar
java -jar build/nodes/AdminNode/corda.jar
java -jar build/nodes/Government/corda.jar
java -jar build/nodes/Transporter/corda.jar
java -jar build/nodes/MatchingAuthority/corda.jar
```

Wait for all nodes to print `Node for "O=..." started up and registered` before proceeding.

> **Important:** The MatchingAuthority node must be running before any
> `RegisterDonorFlow` or `RegisterRecipientFlow` is triggered, as Corda
> resolves it as a mandatory counterparty at flow startup.

---

## Starting the Web Server

The Spring Boot server connects to **one** Corda node via RPC. Start one instance per session, choosing the role you want to interact as. The server always listens on port **8080**.

| Role | Command |
|---|---|
| HospitalA | `gradlew :clients:bootRun --args="--config.rpc.host=localhost --config.rpc.port=10006 --config.rpc.username=hospitalA --config.rpc.password=HospA@2024"` |
| HospitalB | `gradlew :clients:bootRun --args="--config.rpc.host=localhost --config.rpc.port=10007 --config.rpc.username=hospitalB --config.rpc.password=HospB@2024"` |
| AdminNode | `gradlew :clients:bootRun --args="--config.rpc.host=localhost --config.rpc.port=10012 --config.rpc.username=admin --config.rpc.password=Admin@2024"` |
| Government | `gradlew :clients:bootRun --args="--config.rpc.host=localhost --config.rpc.port=10015 --config.rpc.username=govt --config.rpc.password=Govt@2024"` |
| Transporter | `gradlew :clients:bootRun --args="--config.rpc.host=localhost --config.rpc.port=10018 --config.rpc.username=transporter --config.rpc.password=Trans@2024"` |
| MatchingAuthority | `gradlew :clients:bootRun --args="--config.rpc.host=localhost --config.rpc.port=10021 --config.rpc.username=matchingAuth --config.rpc.password=Match@2024"` |

Then open **`http://localhost:8080`** in a browser.

Or use the convenience Gradle task (connects to HospitalA by default):
```
gradlew :clients:runOrganChainServer
```

---

## Network Nodes

| Node | Legal Name | P2P Port | RPC Port | Username | Password |
|---|---|---|---|---|---|
| Notary | O=Notary,L=Chennai,C=IN | 10002 | 10003 | — | — |
| HospitalA | O=HospitalA,L=Hyderabad,C=IN | 10005 | **10006** | hospitalA | HospA@2024 |
| HospitalB | O=HospitalB,L=Mumbai,C=IN | 10008 | **10007** | hospitalB | HospB@2024 |
| AdminNode | O=AdminNode,L=Chennai,C=IN | 10011 | **10012** | admin | Admin@2024 |
| Government | O=Government,L=Delhi,C=IN | 10014 | **10015** | govt | Govt@2024 |
| Transporter | O=Transporter,L=Chennai,C=IN | 10017 | **10018** | transporter | Trans@2024 |
| MatchingAuthority | O=MatchingAuthority,L=Chennai,C=IN | 10020 | **10021** | matchingAuth | Match@2024 |

> Note: HospitalB's RPC port (10007) is lower than its P2P port (10008). This is intentional in the network configuration.

---

## REST API Reference

Base URL: `http://localhost:8080`

| Method | Endpoint | Role Access | Description |
|---|---|---|---|
| POST | `/api/donor/register` | HospA, HospB | Register new donor (PII encrypted before ledger write) |
| GET | `/api/donor/list` | All except Transporter | All donor states |
| GET | `/api/donor/available` | All except Transporter | Donors with `AVAILABLE` status |
| GET | `/api/donor/{linearId}` | All except Transporter | Single donor by linearId |
| POST | `/api/recipient/register` | HospA, HospB | Register recipient on waitlist (PII encrypted) |
| GET | `/api/recipient/list` | All except Transporter | All recipient states |
| GET | `/api/recipient/waiting` | All except Transporter | Recipients with `WAITING` status |
| GET | `/api/recipient/{linearId}` | All except Transporter | Single recipient by linearId |
| POST | `/api/match/trigger/{donorLinearId}` | MatchingAuth, Admin | Run `OrganMatchingFlow` (Algorithm 1) |
| GET | `/api/match/list` | All except Transporter | All match states |
| GET | `/api/match/pending` | MatchingAuth, Admin | Matches with `PENDING_CONFIRMATION` status |
| GET | `/api/match/{linearId}` | All except Transporter | Single match by linearId |
| GET | `/api/match/summary/{matchLinearId}` | **MatchingAuth only** | Decrypted match summary with PII — only meaningful when server is connected to the MatchingAuthority node |
| POST | `/api/match/confirm/{matchLinearId}` | Admin | Confirm a pending match (triggers `NotifyPartiesFlow`) |
| POST | `/api/match/reject/{matchLinearId}` | Admin | Reject a pending match |
| POST | `/api/transport/dispatch/{matchLinearId}` | Transporter | Create `TransportState` (status: DISPATCHED) |
| GET | `/api/transport/list` | All | All transport states |
| POST | `/api/transport/update/{transportId}` | Transporter | Update status to IN_TRANSIT / DELIVERED / FAILED |

---

## RBAC Matrix

| Permission | HospA | HospB | Admin | Govt | Transporter | MatchingAuth |
|---|:---:|:---:|:---:|:---:|:---:|:---:|
| register_donor | ✓ | ✓ | | | | |
| register_recipient | ✓ | ✓ | | | | |
| view_donors | ✓ | ✓ | ✓ | ✓ | | ✓ |
| view_recipients | ✓ | ✓ | ✓ | ✓ | | ✓ |
| trigger_match | | | ✓ | | | ✓ |
| view_match_summary | | | | | | ✓ |
| confirm_match | | | ✓ | | | |
| reject_match | | | ✓ | | | |
| view_matches | ✓ | ✓ | ✓ | ✓ | | ✓ |
| view_transport | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| dispatch_transport | | | | | ✓ | |
| update_transport | | | | | ✓ | |
| audit | | | ✓ | ✓ | | |
| my_records | ✓ | ✓ | | | | |
| settings | ✓ | ✓ | ✓ | | ✓ | |

---

## Organ Viability Windows

| Organ | Viability | UI Urgency |
|---|---|---|
| Heart | 4 hours | Red after 2h |
| Lung | 6 hours | Red after 3h |
| Small Intestine | 8 hours | Red after 4h |
| Liver | 24 hours | Red after 12h |
| Pancreas | 24 hours | Red after 12h |
| Kidney | 36 hours | Red after 18h |
| Cornea | 336 hours (14 days) | Red after 168h |

---

## Corda Project Structure

```
OrganChain/
├── build.gradle                 (root: Cordformation, 7-node deployNodes task)
├── constants.properties         (version pins: Corda 4.12, Kotlin 1.9.22, Spring Boot 3.2.5)
├── settings.gradle              (includes: contracts, workflows, clients)
├── repositories.gradle          (Maven repos: mavenCentral, Corda releases)
│
├── contracts/src/main/kotlin/com/odat/
│   ├── enums/       BloodType, OrganType, DonorStatus, RecipientStatus,
│   │                MatchStatus, TransportStatus, CrossMatchResult
│   ├── states/      DonorState (AES-256-GCM encrypted PII), RecipientState,
│   │                MatchState, TransportState, DecryptedData (in-memory only)
│   └── contracts/   DonorContract, RecipientContract, OrganMatchContract
│
├── workflows/src/main/kotlin/com/odat/
│   ├── services/    AESUtils (AES-256-GCM), KeyVaultService (MatchingAuth only),
│   │                MatchingEngine (Algorithm 1 — pure Kotlin, no Corda deps)
│   └── flows/       RegisterDonorFlow, RegisterRecipientFlow,
│                    OrganMatchingFlow (decrypts in-memory, runs Algorithm 1),
│                    MatchConfirmationFlow, NotifyPartiesFlow,
│                    TransportFlow, FlowUtils
│
└── clients/src/main/kotlin/com/odat/webserver/
    ├── config/      NodeRPCConnection, CorsConfig, JacksonConfig
    ├── models/      ApiModels (request/response DTOs)
    └── controllers/ DonorController, RecipientController, MatchingController
```

---

## Frontend — 3 Files in `clients/src/main/resources/static/`

| File | Purpose |
|---|---|
| `index.html` | HTML structure: 8 pages (Dashboard, My Records, Donors, Recipients, Matching, Transport, Audit Trail, Settings) + 6 modals |
| `style.css` | Clinical light theme (`#f0f4f8` bg, `#ffffff` cards) with dark-mode toggle support |
| `app.js` | RBAC configuration for 6 roles, all API calls (fetch), page renderers, viability timers, score breakdown modal |

**Frontend features:**
1. **Light theme** — white/slate clinical UI
2. **Credentials modal** — clicking a node card prompts username + password; validated before login
3. **Role-filtered dashboard** — stat cards and nav items shown only if the role has access
4. **My Records** — hospitals see only their own registered donors & recipients (filtered by `registeredBy`)
5. **Organ viability countdown timers** — colour-coded urgency (green → amber → red)
6. **Match score breakdown modal** — visual breakdown of Algorithm 1 scoring criteria
7. **Match summary modal** — MatchingAuthority sees decrypted PII for any confirmed match
8. **Blood type compatibility reference grid** — on the dashboard
9. **Transport progress tracker** — live viability timer per transport card
10. **Blockchain audit trail** — chronological timeline reconstructed from all on-chain states
11. **Dark mode toggle** — persisted to localStorage

---

## Technology Stack

| Layer | Technology | Version |
|---|---|---|
| Blockchain | Corda | 4.12 |
| Smart Contracts / Flows | Kotlin | 1.9.22 |
| Encryption | AES-256-GCM | JDK built-in (`javax.crypto`) |
| Key Derivation | PBKDF2-SHA256 | JDK built-in |
| REST API | Spring Boot | 3.2.5 |
| JSON | Jackson Kotlin module | 2.15.2 |
| Logging | Log4j2 + SLF4J | 2.23.1 / 2.0.12 |
| Frontend | Vanilla HTML5 / CSS3 / ES6+ JS | — |
| Build System | Gradle (wrapper) | 8+ |
| JVM | OpenJDK | 17 |
