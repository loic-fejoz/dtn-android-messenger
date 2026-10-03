# Bundle Block Type


Voici l'état des lieux vérifié et exhaustif des **Bundle Block Types** applicables strictement au **Bundle Protocol version 7 (BPv7)**, incluant le registre officiel IANA, les spécifications CCSDS et les brouillons IETF actifs.

---

### 1. Registre officiel IANA (*Bundle Block Types* pour BPv7)

Dans le registre IANA partagé avec BPv6 mais partitionné par version de protocole, les types ci-dessous sont formellement enregistrés pour le protocole version 7 :

| Code (Type) | Nom du bloc | Spécification de référence | Rôle technique |
| --- | --- | --- | --- |
| **0** | *Reserved* | RFC 6255 / RFC 9171 | Valeur réservée. |
| **1** | **Bundle Payload Block** | RFC 9171 | Charge utile applicative du bundle. |
| **6** | **Previous Node Insertion Block** | RFC 9171 | EID du nœud amont immédiat (*proximate sender*). |
| **7** | **Bundle Age Block** | RFC 9171 | Âge relatif du bundle en millisecondes (pour réseaux sans temps synchronisé). |
| **10** | **Hop Count Block** | RFC 9171 | Compteur de sauts / limite de sauts pour rupture de boucle. |
| **11** | **Block Integrity Block (BIB)** | RFC 9172 (BPSec) | Signatures cryptographiques / MAC pour blocs cibles. |
| **12** | **Block Confidentiality Block (BCB)** | RFC 9172 (BPSec) | Chiffrement d'un ou plusieurs blocs cibles. |
| **13** | **Custody Transfer Extension Block (CTEB)** | **CCSDS 734.6-O-1** | Gestion du transfert de garde et retransmissions intermédiaires pour BPv7. |
| **14** | **Compressed Status Reporting Extension Block (CSREB)** | **CCSDS 734.6-O-1** | Rapports d'état compressés pour économie de bande passante. |
| **15** | **Quality of Service Extension Block (QoSEB)** | **CCSDS 734.4-O-1** | Paramètres et exigences de QoS spécifiques au CCSDS. |
| **16 – 191** | *Unassigned* | IETF Review / Specification Required | Plage disponible pour affectation future. |
| **192 – 255** | *Reserved for Private / Experimental Use* | RFC 9171 | Utilisation privée ou tests expérimentaux. |

*(Note : les codes 2, 3, 4, 5, 8 et 9 sont strictement réservés à BPv6 et ne sont pas valides en BPv7. Le Primary Block est le premier conteneur canonique CBOR du bundle et n'a pas d'identifiant dans cette table).*

---

### 2. Spécifications CCSDS (Orange Books / Blue Books)

Le CCSDS base ses profils opérationnels BPv7 sur le standard **CCSDS 734.2-B-1** (*Blue Book*, reprenant la RFC 9171). Les extensions spatiales assignées dans le registre IANA comprennent :

* **Type 13 — CTEB (*Custody Transfer Extension Block*) :**
Défini dans le **CCSDS 734.6-O-1** (*Custody Transfer and Compressed Bundle Status Reporting*, Orange Book). Il porte la demande de transfert de garde avec :
* `Bundle Sequence Number`
* `Bundle Sequence ID`
* `Block Source Administrative Endpoint ID` (le gardien émetteur)


* **Type 14 — CSREB (*Compressed Status Reporting Extension Block*) :**
Défini dans le **CCSDS 734.6-O-1**. Optimise l'émission groupée des acquittements et statuts.
* **Type 15 — QoSEB (*Quality of Service Extension Block*) :**
Défini dans le **CCSDS 734.4-O-1**. Transporte les métadonnées de classe de service spatiales (priorité, criticité, ordre de transmission).

---

### 3. Brouillons actifs (IETF Internet-Drafts) pour BPv7

Ces extensions sont en phase de spécification active auprès du groupe de travail DTN de l'IETF. Elles utilisent des blocs d'extension assignés temporairement dans la plage expérimentale (`192–255`) ou en attente d'attribution dans la plage `16–191` :

| Nom du bloc | Brouillon IETF (*Internet-Draft*) | Statut d'attribution / Rôle |
| --- | --- | --- |
| **Traceroute Extension Block (TREB)** | `draft-koo-dtn-traceroute-eb` | Enregistre le trajet hop-par-hop, les temps de transit et les causes de suppression éventuelles. En attente d'assignation IANA. |
| **Manifest Block** | `draft-sipos-dtn-manifest-block` | Fournit un manifeste d'inventaire cryptographique et structurel des blocs du bundle. |
| **Bundle-in-Bundle Encapsulation (BIBE) Block** | `draft-ietf-dtn-bibe` | Tunnellisation et encapsulation complète d'un bundle BPv7 dans la charge utile ou extension d'un autre bundle. |
| **Target Bundle Block (TBB)** | `draft-ietf-dtn-target-bundle-block` | Permet de corréler un bundle administratif ou de contrôle avec un bundle distant spécifique. |
A
A
| **Session / Group Extension Block** | Travaux du WG DTN (Multicast/Group) | Métadonnées pour la diffusion groupée ou la gestion de session éphémère. |
