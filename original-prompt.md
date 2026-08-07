# PROMPT SPÉCIFICATION D'INGÉNIERIE : APPLICATION ANDROID "DTN-Android BPv7 MESSENGER"

## 1. VISION GLOBALE & CONTEXTE
Tu dois développer une application Android native en **Kotlin** qui agit comme un **nœud DTN (Delay-Tolerant Networking)** basé sur le protocole **BPv7 (RFC 9171)**.
L'application ne fait pas de routage dynamique complexe en V1, mais agit comme un nœud de messagerie contextuelle et de transfert autonome (Store-and-Forward).
Elle est conçue autour d'une architecture orientée **Service local (EID Endpoint)**, incluant la sécurité **BPSec HMAC (RFC 9103)**, des couches de convergence (CLA) multiples (TCPCLv4, Bluetooth), une interface moderne Jetpack Compose, et un support pour **Android Auto**.

---

## 2. ARCHITECTURE TECHNIQUE & DÉPENDANCES
* **Langage & Framework :** Kotlin (100% Android Native), Coroutines & Flow.
* **UI Framework :** Jetpack Compose, Material3, Navigation-Compose.
* **Couche Réseau / I/O :** Ktor Network (`io.ktor:ktor-network`), Okio (`com.squareup.okio:okio`).
* **Sérialisation CBOR :** `com.upokecenter:cbor` (Support strict de la canonicalisation RFC 8949) ou `kotlinx.serialization-cbor`.
* **Base de données / Stockage :** Room Database (métadonnées, routes, clés) + Système de fichiers interne (`filesDir/payloads/`).
* **Cryptographie :** `javax.crypto.Mac` (Standard Android SDK pour HMAC-SHA256).
* **Tâches de fond :** Android Foreground Service (Moteur DTN) + WorkManager (connexions périodiques).
* **Android Auto :** `androidx.car.app:app` + `RemoteInput` / `MessagingStyle` notifications.

---

## 3. MODÈLE DE DONNÉES & BASE DE DONNÉES (ROOM)

Crée les entités et DAOs pour le schéma suivant :

- **LocalService :**
  - `serviceEid`: String (PK, ex: "dtn://my-node/chat")
  - `displayName`: String (ex: "Messagerie Rapide")
  - `viewerType`: Enum (CHAT, BUNDLE_LIST, MINIAPP, SENML_GRAPH)
  - `notificationSoundUri`: String?
  - `vibrationPatternJson`: String?

- **BundleRecord :**
  - `bundleId`: String (PK, Hash ou UUID du Primary Block)
  - `destinationEid`: String
  - `sourceEid`: String
  - `creationTimestamp`: Long
  - `sequenceNumber`: Long
  - `lifetimeMs`: Long
  - `payloadFilePath`: String (Chemin du fichier payload brut)
  - `state`: Enum (RECEIVED, OUTBOX, TRANSIT, DELIVERED)
  - `isRead`: Boolean
  - `bpsecStatus`: Enum (VALID, INVALID, UNVERIFIED)
  - `hopCount`: Int

- **RoutingRule (V2 Next-Hop) :**
  - `destinationEidPattern`: String (PK, ex: "dtn://node-e/*")
  - `nextHopEid`: String (ex: "dtn://node-b")

- **ConvergenceProfile :**
  - `profileId`: String (PK)
  - `name`: String
  - `triggerType`: Enum (WIFI_SSID, PERIODIC_INTERNET, BLUETOOTH_ALWAYS)
  - `targetAddress`: String (IP/Port ou Adresse MAC Bluetooth)
  - `triggerCondition`: String? (ex: SSID Wifi ou Intervalle en minutes)

- **BpsecKey :**
  - `nodeEid`: String (PK, Nœud distant associé)
  - `secretKey`: ByteArray (Clé HMAC partagée)
  - `algorithm`: String (default: "HmacSHA256")

- **SystemLog :**
  - `id`: Long (PK autoGenerate)
  - `timestamp`: Long
  - `level`: String (INFO, WARN, ERROR)
  - `message`: String

---

## 4. MOTEUR DTN & SÉCURITÉ (SERVICE & BPv7)

### A. Modélisation BPv7 (RFC 9171)
Implémente le parser/encodeur CBOR pour :
1. **Primary Bundle Block :** Version=7, Control Flags, CRC, Source EID, Destination EID, Creation Timestamp, Lifetime, Sequence Number.
2. **Payload Block :** Type=1, Data Bytes.
3. **Hop Count Block (Extension Block) :** Type limitant le nombre de sauts.
4. **BPSec Block Integrity Block - BIB (RFC 9103) :** Type=11.
   * Doit contenir la cible du bloc (Payload + Primary), le Security Context (HMAC-SHA256), Key ID, et la signature HMAC.
   * Utilise la canonicalisation CBOR stricte avant le calcul de `javax.crypto.Mac`.

### B. Le Service de Fond (`DtnEngineService`)
Crée un **Foreground Service** qui maintient l'état du réseau :
* Reçoit les Bundles des couches de convergence (CLA).
* Calcule la validité BPSec. Si valide, enregistre dans `BundleRecord`.
* **Politique d'éviction (Cleanup Task) :** Supprime automatiquement les payloads et records dont `CreationTimestamp + LifetimeMs < CurrentTime` ou si l'espace disque sature.

---

## 5. COUCHES DE CONVERGENCE (CLA - CONVERGENCE LAYER ADAPTERS)

Implémente l'interface `ConvergenceLayerAdapter` :

1. **TCPCLv4 Adapter (RFC 9174) :**
   * Mode Client/Serveur asynchrone avec Ktor Network Sockets.
   * Gestion du framing (Header, Length, DRAIN/ACK, Keep-Alive).
2. **Bluetooth Classic Adapter (RFCOMM) :**
   * Utilise `BluetoothSocket` Android.
   * Protocole de framing minimal : `[4 Octets de taille (Int32 Big Endian)][Payload Bundle BPv7]`.
3. **Manager Réseau (`ConvergenceManager`) :**
   * Écoute `ConnectivityManager.NetworkCallback` et les événements Bluetooth.
   * Déclenche automatiquement la connexion selon les `ConvergenceProfile` enregistrés en base.

---

## 6. INTERFACE UTILISATEUR (JETPACK COMPOSE)

L'UI doit respecter la navigation suivante :

### Écran 1 : Accueil ("EID Service Registry")
* Affiche la liste des `LocalService` configurés sur le nœud (`dtn://my-node/*`).
* Badge indiquant les bundles non lus.
* Bouton pour ouvrir l'écran de **Configuration** et un bouton pour ouvrir les **Logs Système**.

### Écran 2 : Vue Dédiée par EID (Routing UI)
Au clic sur un Service, redirection selon `viewerType` :
* **Si CHAT :** Interface type WhatsApp/Signal. Bulle de message. Permet d'écrire du texte et d'envoyer un Bundle réponse.
* **Si BUNDLE_LIST :** Vue type Email (Master-Detail).
  * *Liste :* Cartes montrant Source EID, Date, Taille, Statut BPSec.
  * *Détail :* En-têtes BPv7 complets + **Viewer Dédié** (Texte court, Image via Coil, Lecteur Markdown, ou brut).
* **Si SENML_GRAPH (Placeholder V2) :** Graphique ou cartes brutes JSON.
* **Si MINIAPP (Placeholder V3) :** Conteneur WebView.

### Écran 3 : Envoi de Bundle Opportuniste
Depuis n'importe quelle vue, possibilité de créer un Bundle :
* Saisie de la destination EID (Manuelle ou sélection parmi les EID sources déjà reçus).
* Saisie du contenu (Texte, Sélection de fichier, ou Capture/Sélection d'image).

### Écran 4 : Configuration & Paramètres
1. **Profils CLA :** Ajouter une règle (ex: *Si WiFi "Maison" -> Connecter en TCPCL sur 192.168.1.42:5051* ; *Si Internet -> Périodique 1h sur IP X.X.X.X*).
2. **Table de Routage :** Éditeur simple de `RoutingRule` (Pour le multi-hop V2).
3. **Keystore BPSec :** Formulaire pour saisir la clé secret-shared associée à un EID.
4. **Local Services :** Association pour chaque `LocalService` d'une description, d'un type de viewer, d'un son (URI), et d'une vibration. Ajout/Suppression de service

---

## 7. INTÉGRATION ANDROID AUTO

1. **`CarAppLibrary` & Services :**
   * Déclare un `MessagingService` compatible Android Auto.
2. **Lecture Vocale & Notifications :**
   * Lorsqu'un bundle texte arrive sur un EID de type `CHAT`, émettre une notification avec `NotificationCompat.MessagingStyle`.
   * Permettre à Android Auto de lire le message (Text-to-Speech).
3. **Saisie Vocale (Voice-to-Bundle) :**
   * Utiliser `RemoteInput` sur l'action de réponse de la notification.
   * Récupérer le texte dicté, générer le Bundle BPv7 (avec BPSec HMAC), et l'ajouter à la file d'attente `OUTBOX` du `DtnEngineService`.

---

## 8. CONSIGNES DE DÉVELOPPEMENT & LIVRABLES
* Code modulaire et propre (MVVM / Clean Architecture).
* Utiliser la réinjection de dépendances (Hilt ou Koin).
* Gérer correctement les permissions Android à la volée (Bluetooth, Notification, Storage, Foreground Service).
* Fournir une implémentation fonctionnelle complète pour la V1 (Nœud local, Chat/Fichiers, TCPCL/BT, BPSec, UI Compose, Android Auto).
* Tests (au moins 70% de code coverage)
* Documentation

---

## Appendix A - References
In case you need a reference code, you can read following source code:
* picod3tn: /home/loic/projets/picod3tn
* Hardy: /home/loic/projets/hardy
* DTN utilities for Hardy: /home/loic/projets/dtn-hdy-utils