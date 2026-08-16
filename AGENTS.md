# Agent Guidance System - DTN Android Messenger

### Mission
Provide a reliable, delay-tolerant messaging client on Android using BPv7, with seamless background synchronization over TCP and Bluetooth Classic, fully compatible back to Android 6.0 (API 23).

### Critical Commands
- **Compile & Check**: `./gradlew compileDebugKotlin`
- **Run Unit Tests**: `./gradlew test`
- **Lint Check**: Lint rules are checked during compilation. Always check compiler warnings.
- **Deploy App**: `./gradlew installDebug` (Installs on all attached adb devices).

### Directory Map
- `app/src/main/AndroidManifest.xml`: Application declarations and permissions.
- `app/src/main/java/com/dtn/messenger/`:
  - `car/`: Android Auto integration and CarAppService.
  - `cla/`: Convergence Layer Adapters (TCPCLv4, Bluetooth Classic).
  - `data/`: Room DB entities, DAOs, and database configuration.
  - `protocol/`: BPv7 serialization, parser, and blocks.
  - `receiver/`: Background notification receivers.
  - `service/`: Long-running foreground DtnEngineService.
  - `ui/`: Compose UI Screens and Navigation.
- `app/src/test/`: Unit test suite.

### Core DTN Rules & Principles
- **Source EID vs. CLA Node ID**: The **Bundle Source EID** (`primaryBlock.source`) is a BPv7 application-layer endpoint identifier (e.g. `LocalService.serviceEid`, aliases, or inner encapsulated EIDs). It **MUST NEVER** be assumed to be identical to, or forcibly rewritten with, the **CLA Node ID** announced during transport handshakes (e.g. TCPCLv4 `SESS_INIT`). With services like `dtnbib` (BIBE), routing aliases, and multi-service nodes, bundles can validly originate from service EIDs distinct from the CLA transport node identifier.
- **Responsibility Transfer**: `XFER_ACK` (TCPCLv4) and RFCOMM ACKs **MUST ONLY** be transmitted after an incoming bundle is verified, its payload is written to persistent disk storage, and its record is securely committed to Room DB.

### Documentation Index
Refer to these detailed guides in `agent_docs/` for specific tasks:
- [Architecture Guide](agent_docs/architecture.md): Read this before making changes to data flows, service cycles, or network adapters.
- [Testing Guidelines](agent_docs/testing_guidelines.md): Read this when writing new tests or mocking data interfaces.
- [Conventions Guide](agent_docs/conventions.md): Read this before making logic alterations, creating new components, or modifying Database schemas (contains the critical Database Migration protocol).

### Verification
Always verify your changes by compiling (`./gradlew compileDebugKotlin`) and running the test suite (`./gradlew test`) before finishing a task.
