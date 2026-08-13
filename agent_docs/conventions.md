# Development Conventions & Rules

This document outlines the coding standards, patterns, and critical lifecycle rules for the DTN Android Messenger project.

---

## 1. Database Schema & Migration Protocol (CRITICAL)

Room database schemas are strictly validated by Android. Modifying database schemas without providing a migration path or fallback will crash the application on startup with an `IllegalStateException`.

### 1.1 Modifying Entities
- Any schema changes (adding columns, modifying tables) in [`Entities.kt`](../app/src/main/java/com/dtn/messenger/data/model/Entities.kt) **MUST** be accompanied by a database version increment in [`AppDatabase.kt`](../app/src/main/java/com/dtn/messenger/data/db/AppDatabase.kt#L41).

### 1.2 Migration Path or Destructive Fallback
* **Development/Testing**: During early feature iteration, you can temporarily enable `.fallbackToDestructiveMigration()` in the database builder inside [`Modules.kt`](../app/src/main/java/com/dtn/messenger/di/Modules.kt#L15). This forces Room to recreate the tables automatically when schemas change, avoiding manual uninstalls.
* **Production/Stable Builds**: You **MUST** define an explicit `Migration` object in [`AppDatabase.kt`](../app/src/main/java/com/dtn/messenger/data/db/AppDatabase.kt) and attach it to the builder using `.addMigrations()`.

---

## 2. Background Execution & Foreground Services (Android 12+)

Android 12+ (API 31+) and Android 14 (API 34+) impose strict rules on background execution.

### 2.1 Starting Foreground Services
* **Exempt Actions**: Starting a foreground service from the background is only allowed during specific exemptions (such as direct user notification interactions, i.e., `RemoteInput`).
* **Correct Call**: Always start the service using `androidx.core.content.ContextCompat.startForegroundService()`. Do not call `context.startService()` directly for a foreground service, as it throws `IllegalStateException` on Android 8.0+.

### 2.2 Defensive Programming
- Inside [`DtnEngineService.onCreate()`](../app/src/main/java/com/dtn/messenger/service/DtnEngineService.kt#L95), wrap `startForeground()` in a `try-catch` block capturing `Exception`.
- **CRITICAL**: If `startForeground()` fails and throws `ForegroundServiceStartNotAllowedException`, you **MUST** call `stopSelf()` immediately inside the catch block to prevent the Android OS from throwing a `RemoteServiceException` (ANR/crash) 5 seconds later.

---

## 3. Bluetooth and Location Permissions

To respect user privacy and optimize approval rates:
* **No Unnecessary Scanning**: The application establishes direct socket connections via remote MAC addresses and listens on RFCOMM sockets. It does not perform active discovery scanning.
* **Minimal Permissions**: 
  - On Android 12+ (API 31+), only request `BLUETOOTH_CONNECT` to establish connections. Do not request `BLUETOOTH_SCAN` or `BLUETOOTH_ADVERTISE` unless scanner/advertiser logic is explicitly added.
  - Avoid requesting `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION` for Bluetooth operations on API 31+, since no location scanning is performed.
