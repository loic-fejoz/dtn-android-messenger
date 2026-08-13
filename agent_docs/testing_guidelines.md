# Testing Guidelines

This document details how to write, mock, and run tests for the DTN Android Messenger project.

## 1. Test Locations
- **Unit Tests**: Located under [`app/src/test/java/com/dtn/messenger/`](../app/src/test/java/com/dtn/messenger).
- **Instrumentation (Android) Tests**: Located under `app/src/androidTest/`.

---

## 2. Testing Framework & Tools
The project utilizes:
- **JUnit 4** for unit test structures.
- **KSP** for compiling Room components during builds.

---

## 3. Writing and Running Tests

### 3.1 Running the Suite
Always verify your work before declaring a task finished by running:
```bash
./gradlew test
```
This compiles the code and runs all unit tests.

### 3.2 Protocol Testing (BPv7)
- Core protocol serialization and security blocks validation are tested in [`Bpv7Test.kt`](../app/src/test/java/com/dtn/messenger/Bpv7Test.kt).
- When writing tests for custom bundle blocks or parsers, replicate the byte-level tests defined in [`Bpv7Test.kt`](../app/src/test/java/com/dtn/messenger/Bpv7Test.kt#L10) to verify correctness against RFC 9171 (BPv7).

---

## 4. Best Practices for Mocking

### 4.1 Database Mocking (Room)
For repository or DAO testing:
- Use Room's in-memory database helper in your test classes:
  ```kotlin
  Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
  ```
- This ensures test database files do not touch disk and are destroyed when the test suite terminates.

### 4.2 Network & Bluetooth Mocking
- **Never** instantiate or connect to real sockets (`TcpSocket` / `BluetoothSocket`) in unit tests.
- Instead, mock the [`ConvergenceLayerAdapter`](../app/src/main/java/com/dtn/messenger/cla/ConvergenceLayer.kt#L10) interface.
- Provide fake/mock implementations that simulate successful or failed network transmissions to test routing or service recovery scenarios.
