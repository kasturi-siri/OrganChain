
# OrganChain

A permissioned blockchain CorDapp for secure, transparent, and tamper-proof
organ donation and transplantation management, built on **R3 Corda 4.9**.
OrganChain is a Corda CorDapp template adaptation for organ donation, matching, and logistics workflows.

## Architecture at a Glance

## Modules

- `contracts`: OrganChain states and contracts (`com.odat.*`).
- `workflows`: OrganChain initiating and responder flows plus helper services.
- `clients`: RPC/web integration skeleton for external access.

## Notes from the Main Template migration

The `Main Template of OrganChain` commit migrated most runtime code from `com.template.*` to `com.odat.*`.
Some legacy template references still exist in test sources under `contracts/src/test` and `workflows/src/test` and can be migrated in a follow-up cleanup.

## Common commands

```bash
./gradlew clean assemble -x test
./gradlew deployNodes
./build/nodes/runnodes
```

## Network roles (configured in `build.gradle`)

- Notary
- HospitalA
- HospitalB
- AdminNode
- Government
- Transporter

## Architecture at a Glance

```
odat-cordapp/
├── contracts/      ← States + Contracts  (shared CorDapp JAR)
├── workflows/      ← Flows + Services    (CorDapp logic JAR)
└── clients/        ← Spring Boot REST API over Corda RPC
```

### Network Nodes
| Node | Legal Name | Role |
|------|-----------|------|
| HospitalA | `O=HospitalA,L=Chennai,C=IN`  | Registers donors & recipients |
| HospitalB | `O=HospitalB,L=Mumbai,C=IN`   | Registers donors & recipients |
| AdminNode | `O=AdminNode,L=Chennai,C=IN`  | Endorses registrations, confirms matches |
| Government | `O=Government,L=Delhi,C=IN`  | Regulatory observer |
| Transporter | `O=Transporter,L=Chennai,C=IN` | Organ transport dispatch |
| Notary    | `O=Notary,L=Chennai,C=IN`     | Prevents double-assignment |

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java (JDK) | **8u382** | Corda 4.x requires Java 8 only |
| Kotlin | 1.6.x | Included via Gradle |
| Gradle | 7.x | Use `./gradlew` wrapper |
| Git | Any | For cloning |

> ⚠️  Corda 4.x does **NOT** support Java 11 or 17. Use exactly **Java 8**.

---

## Quick Start

### 1. Clone and Build

```bash
git clone https://github.com/your-org/odat-cordapp.git
cd odat-cordapp

# Build all CorDapp JARs and generate node directories
./gradlew clean deployNodes
```

### 2. Start the Network

Open six terminal windows — one per node:

```bash
# Terminal 1 — Notary
cd build/nodes/Notary && java -jar corda.jar

# Terminal 2 — HospitalA
cd build/nodes/HospitalA && java -jar corda.jar

# Terminal 3 — HospitalB
cd build/nodes/HospitalB && java -jar corda.jar

# Terminal 4 — AdminNode
cd build/nodes/AdminNode && java -jar corda.jar

# Terminal 5 — Government
cd build/nodes/Government && java -jar corda.jar

# Terminal 6 — Transporter
cd build/nodes/Transporter && java -jar corda.jar
```

Wait until all nodes print `Node for [name] started up and registered`.

### 3. Start the REST API Server

```bash
cd clients
../gradlew bootRun \
  --args='--config.rpc.host=localhost
          --config.rpc.port=10003
          --config.rpc.username=hospitalA
          --config.rpc.password=HospA@2024'
```

The API is now available at `http://localhost:8080`.

---

## REST API Reference

### Donor Endpoints

#### Register a Donor
```http
POST /api/donor/register
Content-Type: application/json

{
  "name": "John Doe",
  "contact": "john.doe@example.com",
  "bloodType": "O_POS",
  "organType": "KIDNEY",
  "age": 35,
  "weightKg": 70.0,
  "heightCm": 175.0,
  "isDeceased": false,
  "location": "Chennai"
}
```
**Response 201:**
```json
{
  "success": true,
  "message": "Donor registered successfully. Encrypted and stored on Corda ledger.",
  "data": {
    "linearId": "a1b2c3d4-...",
    "bloodType": "O_POS",
    "organType": "KIDNEY",
    "age": 35,
    "location": "Chennai",
    "isDeceased": false,
    "status": "AVAILABLE",
    "registeredBy": "HospitalA",
    "registrationTime": "2026-04-21T10:30:00Z"
  }
}
```

#### List All Donors
```http
GET /api/donor/list
GET /api/donor/available
GET /api/donor/{linearId}
```

---

### Recipient (Patient) Endpoints

#### Register a Patient
```http
POST /api/recipient/register
Content-Type: application/json

{
  "name": "Jane Smith",
  "contact": "jane.smith@example.com",
  "bloodType": "O_POS",
  "organNeeded": "KIDNEY",
  "age": 32,
  "weightKg": 62.0,
  "heightCm": 165.0,
  "conditionScore": 8,
  "serialNumber": 42,
  "hasPairedDonor": false,
  "location": "Chennai"
}
```

#### List Patients
```http
GET /api/recipient/list
GET /api/recipient/waiting
GET /api/recipient/{linearId}
```

---

### Matching Endpoints

#### Trigger Matching for a Donor
```http
POST /api/match/trigger/{donorLinearId}
```
Runs Algorithm 1 against all WAITING recipients. Returns the best match or
a "no match found" message.

#### Confirm a Match (AdminNode only)
```http
POST /api/match/confirm/{matchLinearId}
```

#### Reject a Match (AdminNode only)
```http
POST /api/match/reject/{matchLinearId}
Content-Type: application/json

{ "reason": "Cross-match lab result was negative" }
```

#### Query Matches
```http
GET /api/match/list
GET /api/match/pending
GET /api/match/{linearId}
```

---

### Transport Endpoints

#### Dispatch Transport (after match confirmed)
```http
POST /api/transport/dispatch/{matchLinearId}
```

#### Update Transport Status
```http
POST /api/transport/update/{transportLinearId}
Content-Type: application/json

{ "newStatus": "IN_TRANSIT" }
```
Valid values: `DISPATCHED` → `IN_TRANSIT` → `DELIVERED` or `FAILED`

#### Query Transport
```http
GET /api/transport/list
GET /api/transport/{linearId}
```

---

## Matching Algorithm (Algorithm 1)

The weighted scoring algorithm runs inside `OrganMatchingFlow`:

| Criterion | Condition | Score |
|-----------|-----------|-------|
| Blood type | Hard filter — incompatible pairs excluded | Required gate |
| Location (deceased) | `donor.location == recipient.location` | +15 pts |
| Paired donor | `recipient.hasPairedDonor == true` | +20 pts |
| Size compatibility | `|donorBMI − recipientBMI| ≤ 5.0` | +15 pts |
| Age compatibility | `|donorAge − recipientAge| ≤ 15 yrs` | +10 pts |
| Clinical urgency | `conditionScore × 5` (score 1–10) | +5 to +50 pts |
| Waitlist order | `− serialNumber × 0.001` | tie-break |
| Cross-match | Positive required (simulated; replace with lab API) | Required gate |

---

## Security

| Layer | Mechanism |
|-------|-----------|
| PII encryption | **AES-256-GCM** — applied in flow before any State is created |
| Key management | `KeyVaultService` (@CordaService) — plug in HSM for production |
| Network identity | X.509 certificates via Doorman CA |
| Double-assignment | Corda **Notary** prevents double-spend of organ states |
| Data visibility | Corda need-to-know — states only in participant Vaults |
| Audit trail | Immutable Vault history — every state transition recorded |

---

## Running Tests

```bash
# Pure algorithm tests (no Corda node required, fast)
./gradlew :workflows:test --tests "com.odat.services.MatchingEngineTest"

# Full flow integration tests (MockNetwork)
./gradlew :workflows:test --tests "com.odat.flows.ODaTFlowTests"

# All tests
./gradlew test
```

---

## Blood Type Compatibility Reference

| Donor \ Recipient | O− | O+ | A− | A+ | B− | B+ | AB− | AB+ |
|---|---|---|---|---|---|---|---|---|
| **O−** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **O+** | ❌ | ✅ | ❌ | ✅ | ❌ | ✅ | ❌ | ✅ |
| **A−** | ❌ | ❌ | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ |
| **A+** | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ |
| **B−** | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ |
| **B+** | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ |
| **AB−** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| **AB+** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |

---

## Organ Viability Windows

| Organ | Max cold ischaemia time |
|-------|------------------------|
| Heart | 4–6 hours |
| Lung | 4–6 hours |
| Liver | 12–24 hours |
| Kidney | 24–36 hours |
| Pancreas | 12–24 hours |
| Cornea | up to 7 days |
| Small Intestine | 6–12 hours |

---

## Enums Quick Reference

**BloodType:** `A_POS`, `A_NEG`, `B_POS`, `B_NEG`, `O_POS`, `O_NEG`, `AB_POS`, `AB_NEG`

**OrganType:** `KIDNEY`, `LIVER`, `HEART`, `LUNG`, `PANCREAS`, `CORNEA`, `SMALL_INTESTINE`

**DonorStatus:** `AVAILABLE` → `ASSIGNED` → `EXPIRED`

**RecipientStatus:** `WAITING` → `MATCHED` → `TRANSPLANTED` | `REMOVED`

**MatchStatus:** `PENDING_CONFIRMATION` → `CONFIRMED` | `REJECTED`

**TransportStatus:** `DISPATCHED` → `IN_TRANSIT` → `DELIVERED` | `FAILED`
