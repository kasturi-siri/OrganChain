<p align="center">
  <img src="https://www.corda.net/wp-content/uploads/2016/11/fg005_corda_b.png" alt="Corda" width="500">
</p>

# OrganChain

OrganChain is a Corda CorDapp for organ donation, matching, confirmation, and transport workflows.

## Prerequisites

- JDK 17
- Docker is **not** required
- Unix-like shell (Linux/macOS) or Git Bash/PowerShell on Windows

## Build

```bash
./gradlew clean assemble -x test
```

> `-x test` is included because you asked to skip tests.

## Run the Corda network

1. Generate node directories and CorDapp jars:

```bash
./gradlew deployNodes
```

2. Start all nodes:

```bash
./build/nodes/runnodes
```

3. Open node shells from the spawned terminals (for example HospitalA at localhost:10006 RPC).

## Example flow startup (from node shell)

You can start RPC-enabled flows from a node shell using `start`. Available flow classes include:

- `com.odat.flows.RegisterDonorFlow`
- `com.odat.flows.RegisterRecipientFlow`
- `com.odat.flows.OrganMatchingFlow`
- `com.odat.flows.ConfirmMatchFlow` / `com.odat.flows.RejectMatchFlow`
- `com.odat.flows.DispatchTransportFlow` / `com.odat.flows.UpdateTransportStatusFlow`

Use `flow list` in the shell to inspect signatures before invoking.

## Current client/web status

`clients/` currently contains scaffolding only (controllers/models/config classes) and does **not** yet expose a runnable Spring Boot entry point.

## Modules

- `contracts` — states, contracts, and enums (`com.odat.*`)
- `workflows` — initiating/responder flows and helper services
- `clients` — RPC/web integration scaffolding
