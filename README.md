## OrganChain 

---

### What Was Built
A complete **Organ Donation & Transplantation (ODaT) CorDapp** on **Corda 4.12** with a **Spring Boot REST API** and a **full frontend** (HTML + CSS + JS, light theme, RBAC).

---

### Corda Project Structure
```
cordapp-template-kotlin/
├── contracts/src/main/kotlin/com/odat/
│   ├── enums/       BloodType, OrganType, DonorStatus, RecipientStatus,
│   │                MatchStatus, TransportStatus, CrossMatchResult
│   ├── states/      DonorState, RecipientState, MatchState, TransportState
│   └── contracts/   DonorContract, RecipientContract,
│                    OrganMatchContract, TransportContract
├── workflows/src/main/kotlin/com/odat/
│   ├── services/    AESUtils (AES-256-GCM), KeyVaultService,
│   │                MatchingEngine (Algorithm 1)
│   └── flows/       RegisterDonorFlow, RegisterRecipientFlow,
│                    OrganMatchingFlow, MatchConfirmationFlow,
│                    ConfirmMatchFlow, RejectMatchFlow,
│                    TransportFlow, DispatchTransportFlow,
│                    UpdateTransportStatusFlow
└── clients/src/main/kotlin/com/odat/webserver/
    ├── config/      NodeRPCConnection, CorsConfig
    ├── models/      ApiModels (request/response DTOs)
    └── controllers/ DonorController, RecipientController,
                     MatchingController, TransportController
```

---

### Network Nodes
| Node | Legal Name | RPC Port | Username | Password |
|---|---|---|---|---|
| HospitalA | O=HospitalA,L=Hyderabad,C=IN | 10006 | hospitalA | HospA@2024 |
| HospitalB | O=HospitalB,L=Mumbai,C=IN | 10008 | hospitalB | HospB@2024 |
| AdminNode | O=AdminNode,L=Chennai,C=IN | 10010 | admin | Admin@2024 |
| Government | O=Government,L=Delhi,C=IN | 10012 | govt | Govt@2024 |
| Transporter | O=Transporter,L=Chennai,C=IN | 10014 | transporter | Trans@2024 |

---

### REST API Endpoints (base: `http://localhost:8080`)
| Method | Endpoint | Action |
|---|---|---|
| POST | /api/donor/register | Register donor |
| GET | /api/donor/list | All donors |
| GET | /api/donor/available | Available donors only |
| POST | /api/recipient/register | Register recipient |
| GET | /api/recipient/list | All recipients |
| GET | /api/recipient/waiting | Waiting recipients only |
| POST | /api/match/trigger/{donorId} | Run Algorithm 1 |
| GET | /api/match/list | All matches |
| POST | /api/match/confirm/{matchId} | Confirm match |
| POST | /api/match/reject/{matchId} | Reject match |
| POST | /api/transport/dispatch/{matchId} | Dispatch transport |
| GET | /api/transport/list | All transports |
| POST | /api/transport/update/{transportId} | Update transport status |

---

### Frontend — 3 Files in `clients/src/main/resources/static/`
| File | Lines | Purpose |
|---|---|---|
| `index.html` | 585 | HTML structure, all modals, pages |
| `style.css` | 390 | Light clinical theme, full component styles |
| `app.js` | 1132 | RBAC, all API calls, all page logic |

**Start Spring Boot for HospitalA:**
```
gradlew.bat :clients:bootRun --args="--config.rpc.host=localhost --config.rpc.port=10006 --config.rpc.username=hospitalA --config.rpc.password=HospA@2024"
```
Then open `http://localhost:8080` in a browser.

---

### RBAC — 5 Roles & Permissions
| Role | Permissions |
|---|---|
| **HospitalA / B** | Register donors & recipients, view donors/recipients/matches/transport, **My Records** |
| **Admin** | Trigger matching, confirm/reject matches, view all, audit trail |
| **Government** | Read-only: all donors, recipients, matches, transport, audit trail |
| **Transporter** | Dispatch transport, update status, view transport |

---

### Key Features Implemented
1. **Light theme** — white/slate clinical UI (`#f0f4f8` bg, `#ffffff` cards)
2. **Credentials modal** — clicking a node prompts username + password; validated before login
3. **Role-filtered dashboard** — stat cards, panels, and sections only shown if the role has access
4. **My Records page** — hospitals see only their own registered donors & recipients (filtered by `registeredBy`)
5. **OrganChain branding** — tab title, logo name, no technical content (no ports/localhost) in the UI
6. **Organ viability countdown timers** (Heart 4h, Lung 6h, Liver 24h, Kidney 36h, etc.) with colour-coded urgency
7. **Match score breakdown modal** — visual bar chart of Algorithm 1 scoring criteria
8. **Blood type compatibility reference grid** on dashboard
9. **Transport progress tracker** with live viability timer per card
10. **Blockchain audit trail** — chronological timeline reconstructed from all on-chain states
11. **In-app notifications** — bell icon with event log