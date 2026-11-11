# Décisions

Chaque décision indique son état : **en place** (code écrit et testé sur JVM), **squelette** (écrit, jamais compilé) ou **prévu** (pas encore écrit). Les écarts au cahier des charges sont repris dans `ECARTS.md`.

## Architecture

**D-01 — Deux modules.** *En place.*
`:core` est du Kotlin pur sans dépendance Android : cryptographie, TOTP, délais, session, modèle, échéances, archive. `:app` contiendra l'interface Compose, Room, Keystore et les services Android. La frontière passe par trois interfaces de `:core` : `Clock` (horloge), `DeviceKey` (clé Keystore) et `GateStore` (état d'accès persistant). C'est ce qui rend toute la logique de sécurité testable hors appareil.

```
:app   interface (Compose)  →  cas d'usage  →  persistance (Room, fichiers, Keystore)
                                    │
:core  access/   SessionGate, Totp, TotpVerifier, Backoff
       crypto/   Aead, StreamCipher, PassphraseKdf, CryptoContext
       model/    Record, Field, AttachmentMeta, Codec, Templates, Limits
       docs/     Deadlines, ReminderPlanner, LockTimers, SearchIndex, FormatChecks,
                 FileSignature, RecoveryPhrase, PassphraseStrength
       archive/  ArchiveWriter, ArchiveReader, RecoveryVerifier
```

## Versions

**D-02 — Versions retenues.**

| Élément | Version | Vérification |
| --- | --- | --- |
| Kotlin (compilation et tests du noyau ici) | 2.0.21 | Exécuté |
| Bouncy Castle `bcprov-jdk18on` | 1.78.1 | Exécuté (tests du noyau) |
| JUnit | 4.13.2 | Exécuté |
| JDK | 21 ici, 17 visé par le build | 21 exécuté |
| Android Gradle Plugin | 9.5.1 | Relevé sur developer.android.com le 11/11/2026, non résolu |
| Gradle (wrapper) | 9.6.0 | Minimum exigé par AGP 9.5, non téléchargé |
| compileSdk / targetSdk | 37 | Maximum pris en charge par AGP 9.5, non compilé |
| Kotlin (build Gradle) | 2.4.10 | Non résolu |
| Compose BOM | 2026.09.00 | Non résolu |
| activity-compose, core-ktx, lifecycle | 1.13.0, 1.18.0, 2.10.0 | **Supposées**, à confirmer à la première synchronisation |

Bouncy Castle est figé sur la version réellement testée ; toute montée de version repasse par `:core:test`. Room, KSP et les bibliothèques de test instrumenté seront ajoutés au catalogue quand le code qui les utilise sera écrit, pour ne pas afficher de versions jamais essayées.

**D-03 — minSdk 26.** *Squelette.*
Android 8.0 apporte `java.time` (dates civiles sans bibliothèque de substitution), les canaux de notification et les icônes adaptatives ; Keystore AES-GCM (API 23) et le compteur de démarrages (API 24) sont déjà là. Comportement dégradé selon la version :

| API | Fonction | Avant cette API |
| --- | --- | --- |
| 28 | StrongBox | Clé dans le TEE |
| 31 | `dataExtractionRules` (cloud et transfert d'appareil) | `allowBackup="false"` et `fullBackupContent` |
| 31 | Masquage des fenêtres superposées (`setHideOverlayWindows`) | `filterTouchesWhenObscured` seul |
| 33 | Presse-papiers marqué sensible | Effacement après 60 s seulement |
| 33 | Permission `POST_NOTIFICATIONS` à l'exécution | Notifications autorisées par défaut |
| 30 | Rendu PDF depuis la mémoire (`memfd_create`) | Fichier temporaire privé, effacé à la fermeture, au verrouillage et au démarrage |

## Accès

**D-04 — Argon2id par Bouncy Castle, m = 32 Mio, t calibré, p = 1.** *En place.*
Bouncy Castle est maintenue et en pur JVM : la même implémentation tourne dans les tests et sur le téléphone, ce qu'une bibliothèque native (JNI) ne permet pas. Elle est plus lente qu'une implémentation native ; la mémoire est donc fixée à 32 Mio (au lieu de 64) pour tenir dans le tas d'un téléphone à 2 Go de RAM, et `PassphraseKdf.calibrate` ajuste le nombre de passes pour viser 0,5 à 1 s, sans jamais descendre sous 19 Mio et 2 passes (recommandation OWASP). Repli : 19 Mio, puis PBKDF2-HMAC-SHA256 à 600 000 itérations si l'allocation échoue. Les paramètres sont versionnés et stockés avec le sel. **Le calibrage n'a pas été mesuré sur un téléphone.**

**D-05 — La phrase est normalisée en NFC avant dérivation.** *En place.*
Une même phrase accentuée saisie avec deux claviers doit ouvrir le coffre. Contrepartie : la normalisation crée une chaîne transitoire que la JVM ne permet pas d'effacer.

**D-06 — Hiérarchie de clés.** *En place côté noyau, clé Keystore en squelette.*

```
phrase ──Argon2id──▶ maître ──HKDF──▶ kSeed, kData
graine TOTP    = Keystore[graine] ( AES-GCM(kSeed, graine) )
clé de données = Keystore[données]( AES-GCM(kData, DK) )
DK ──▶ enregistrements (AES-GCM, contexte = table + identifiant)
DK ──HKDF(sel par fichier)──▶ clé de chaque pièce jointe
phrase de récupération ──Argon2id──▶ clé d'archive (indépendante, aucune clé du téléphone)
```

La phrase est vérifiée en déchiffrant la graine, ce qui exige aussi la clé Keystore : il n'existe aucun « vérificateur de phrase » exploitable sur des fichiers extraits. La clé de données n'est désenveloppée qu'après un TOTP valide ; entre les deux étapes, seuls la graine et `kData` sont en mémoire, trois minutes au plus.

**D-07 — Délai progressif.** *En place.*
Barème par défaut du cahier des charges. Mesuré en temps monotone (`elapsedRealtime`) et rattaché au compteur de démarrages. Deux choix protecteurs :
- après un redémarrage du téléphone, le délai en cours **repart entier** (le temps monotone ayant été remis à zéro, on ne peut pas savoir ce qui a été purgé) ;
- une étape 1 réussie est **comptée comme un échec tout de suite**, de façon durable, et seul un TOTP valide remet le compteur à zéro. Tuer l'application au milieu de l'étape 2 ne fait donc pas disparaître la tentative.

Conséquence assumée : quelqu'un qui saisit la bonne phrase puis abandonne cinq fois de suite subit le délai.

**D-08 — TOTP.** *En place.*
Tolérance d'un pas. Vingt pas supplémentaires de chaque côté sont examinés uniquement pour choisir le message (« code expiré, vérifie l'heure » plutôt que « code erroné ») ; ils ne sont jamais acceptés. Le dernier pas accepté est avancé par une opération atomique du stockage avant l'ouverture. Abandonner l'étape 2 (retour, extinction de l'écran) efface la graine de la mémoire ; passer par l'application d'authentification ne l'annule pas tant que les trois minutes courent.

**D-09 — Biométrie non livrée.** Le parcours ne peut pas être validé sur appareil ici ; conformément au cahier des charges, seul le parcours par phrase est prévu. `USE_BIOMETRIC` n'est pas déclarée.

## Données

**D-10 — Chiffrement par AES-256-GCM de la plateforme, pas par Tink.** *En place.* **Écart à une recommandation**, voir `ECARTS.md` E-03.
Tink n'a pas pu être téléchargé dans l'environnement de développement. Plutôt que livrer du code Tink jamais exécuté, le noyau utilise `AES/GCM/NoPadding` du fournisseur de la plateforme (Conscrypt sur Android), avec nonce aléatoire de 96 bits pour les valeurs courtes et une construction en segments pour les fichiers (D-11). Toutes les propriétés exigées sont testées : authentification, liaison au contexte, non-réutilisation de nonce, détection de troncature et de permutation.

**D-11 — Chiffrement en flux.** *En place.*
Segments de 64 Kio, chacun en AES-256-GCM. Nonce = préfixe aléatoire (7 octets) + compteur (4) + drapeau « dernier segment » (1). Clé propre à chaque fichier, dérivée par HKDF avec un sel aléatoire de 16 octets. L'en-tête et le contexte (identifiant de la pièce) sont authentifiés avec chaque segment. C'est la construction « STREAM », la même famille que le chiffrement en flux de Tink. Mémoire constante quelle que soit la taille du fichier.

**D-12 — Une fiche = un enregistrement chiffré en bloc.** *Sérialisation en place, Room prévu.*
Room stockera `(identifiant, blob chiffré)`. Titre, étiquettes, champs, catégorie, favori et échéance sont tous dans le blob : aucune colonne en clair, donc rien à craindre du journal SQLite ni des index. La recherche et les filtres utilisent un index en mémoire reconstruit au déverrouillage et vidé au verrouillage ; à 500 fiches, c'est immédiat.

**D-13 — Identifiants.** *En place.* UUID aléatoires en minuscules, validés par expression régulière à chaque décodage : un identifiant venu d'une archive ne peut pas former un chemin.

**D-14 — Appareil photo du système, pas de capture intégrée.** *Prévu.*
Pas de permission `CAMERA`, moins de code sensible. La photo est écrite par l'appareil photo dans un fichier privé exposé temporairement par `FileProvider`, puis chiffrée et effacée ; ce répertoire est vidé à chaque démarrage.

**D-15 — Rappels par `AlarmManager` inexact, pas par WorkManager.** *Calcul en place, planification prévue.*
WorkManager ajoute au manifeste fusionné `WAKE_LOCK`, `ACCESS_NETWORK_STATE` et `FOREGROUND_SERVICE`, hors liste blanche. Des alarmes inexactes (`setWindow`) suffisent et ne demandent aucune permission. Les rappels dont l'instant est déjà passé ne sont pas planifiés ; l'état reste visible dans l'application.

**D-16 — Échéances.** *En place.* Un document est valable toute la journée de sa date d'expiration et « expiré » à partir du lendemain. « Bientôt » : 30 jours ou moins.

## Sauvegarde

**D-17 — Phrase de récupération : 28 caractères, 140 bits.** *En place.*
Alphabet de 32 symboles sans I, L, O ni U, sept groupes de quatre. À la saisie, O est lu comme 0, I et L comme 1. Une empreinte (HKDF avec sel) est conservée **dans le coffre chiffré** pour vérifier à chaque export que la phrase ressaisie est la bonne ; avec 140 bits d'entropie elle ne permet pas de retrouver la phrase.

**D-18 — Format d'archive `MPARCH` version 1.** *En place.* Décrit en tête de `core/.../archive/Archive.kt`.
En-tête en clair (magie, version, paramètres de dérivation, contrôle de clé) authentifié avec tout le contenu ; contenu chiffré en flux ; manifeste en tête du contenu (nombre de fiches et de pièces, taille de chacune) ; marqueur de fin. Erreurs distinguées : format étranger, version inconnue, mauvaise phrase, archive abîmée, limite dépassée, problème de stockage. À la restauration, les limites sont contrôlées sur le manifeste avant d'écrire un seul octet de contenu.

**D-19 — Limites.** *En place.* 20 Mo par fichier et 10 pièces par fiche (défauts). À la restauration : 5 000 fiches et 4 Go au total, volontairement au-dessus de la volumétrie visée (500 fiches, 2 Go) pour ne pas refuser une archive légitime.

## Variantes

**D-20 — `demo` sans raccourci.** *Squelette.* La double validation y est identique. Les éléments de démonstration vivent dans `app/src/demo` et ne sont pas compilés dans `prod` ; `tools/check-apk.sh` le contrôle sur l'APK.

## Points ouverts à valider sur appareil

- `setUnlockedDeviceRequired(true)` sur les clés Keystore : plus protecteur, mais son comportement varie selon les versions et les constructeurs. Non activé tant qu'il n'est pas essayé.
- Rendu PDF depuis la mémoire (D-03).
- Temps réel d'Argon2id sur un téléphone à 2 Go de RAM (D-04).
