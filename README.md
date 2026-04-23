<p align="center">
  <img src="https://www.corda.net/wp-content/uploads/2016/11/fg005_corda_b.png" alt="Corda" width="500">
</p>

# OrganChain

OrganChain is a Corda CorDapp template adaptation for organ donation, matching, and logistics workflows.

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
```

## Network roles (configured in `build.gradle`)

- Notary
- HospitalA
- HospitalB
- AdminNode
- Government
- Transporter
