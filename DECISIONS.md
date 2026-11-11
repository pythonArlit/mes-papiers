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

**D-02 — Versions retenues.** Toutes résolues, compilées et exécutées par la CI le 11/11/2026.

| Élément | Version |
| --- | --- |
| Android Gradle Plugin | 9.5.1 |
| Gradle (wrapper) | 9.6.0 |
| Kotlin | 2.4.10 (Kotlin intégré à AGP 9, plugin Compose 2.4.10) |
| KSP | 2.3.4 |
| JDK | 17 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |
| Compose BOM | 2026.09.00 |
| activity-compose, core-ktx, lifecycle | 1.13.0, 1.18.0, 2.10.0 |
| Room | 2.8.4 |
| ZXing core (QR hors ligne) | 3.5.3 |
| ML Kit text-recognition (modèle latin embarqué) | 16.0.1 |
| Compose material-icons-extended | version du BOM |
| androidx.exifinterface (orientation des photos) | 1.3.7 |
| Bouncy Castle `bcprov-jdk18on` | 1.78.1 |
| JUnit, androidx.test runner, ext-junit | 4.13.2, 1.6.2, 1.2.1 |

Ce sont des versions qui fonctionnent ensemble ; je n'ai pas vérifié pour chacune qu'elle est la plus récente. Bouncy Castle est figé sur la version testée ; toute montée de version repasse par `:core:test`.

**D-03 — minSdk 26.** *En place.*
Android 8.0 apporte `java.time` (dates civiles sans bibliothèque de substitution), les canaux de notification et les icônes adaptatives ; Keystore AES-GCM (API 23) et le compteur de démarrages (API 24) sont déjà là. Comportement dégradé selon la version :

| API | Fonction | Avant cette API |
| --- | --- | --- |
| 28 | StrongBox | Clé dans le TEE |
| 31 | `dataExtractionRules` (cloud et transfert d'appareil) | `allowBackup="false"` et `fullBackupContent` |
| — | Masquage des fenêtres superposées | `filterTouchesWhenObscured` sur toutes les versions ; `setHideOverlayWindows` exige une permission hors liste blanche (`ECARTS.md` E-11) |
| 33 | Presse-papiers marqué sensible | Effacement après 60 s seulement |
| 33 | Permission `POST_NOTIFICATIONS` à l'exécution | Notifications autorisées par défaut |
| 30 | Rendu PDF depuis la mémoire (`memfd_create`) | Fichier temporaire privé, effacé à la fermeture, au verrouillage et au démarrage |

## Accès

**D-04 — Argon2id par Bouncy Castle, m = 32 Mio, t calibré, p = 1.** *En place.*
Bouncy Castle est maintenue et en pur JVM : la même implémentation tourne dans les tests et sur le téléphone, ce qu'une bibliothèque native (JNI) ne permet pas. Elle est plus lente qu'une implémentation native ; la mémoire est donc fixée à 32 Mio (au lieu de 64) pour tenir dans le tas d'un téléphone à 2 Go de RAM, et `PassphraseKdf.calibrate` ajuste le nombre de passes pour viser 0,5 à 1 s, sans jamais descendre sous 19 Mio et 2 passes (recommandation OWASP). Repli : 19 Mio, puis PBKDF2-HMAC-SHA256 à 600 000 itérations si l'allocation échoue. Les paramètres sont versionnés et stockés avec le sel. **Le calibrage n'a pas été mesuré sur un téléphone.**

**D-05 — La phrase est normalisée en NFC avant dérivation.** *En place.*
Une même phrase accentuée saisie avec deux claviers doit ouvrir le coffre. Contrepartie : la normalisation crée une chaîne transitoire que la JVM ne permet pas d'effacer.

**D-06 — Hiérarchie de clés.** *En place ; clé Keystore testée sur émulateur.*

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

**D-12 — Une fiche = un enregistrement chiffré en bloc.** *En place, testé sur émulateur.*
Room stocke `(identifiant, blob chiffré)`. Titre, étiquettes, champs, catégorie, favori et échéance sont tous dans le blob : aucune colonne en clair, donc rien à craindre du journal SQLite ni des index. La recherche et les filtres utilisent un index en mémoire reconstruit au déverrouillage et vidé au verrouillage ; à 500 fiches, c'est immédiat.

**D-13 — Identifiants.** *En place.* UUID aléatoires en minuscules, validés par expression régulière à chaque décodage : un identifiant venu d'une archive ne peut pas former un chemin.

**D-14 — Appareil photo du système, pas de capture intégrée.** *En place, non essayé par l'interface.*
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

**D-20 — `demo` : seconde étape automatique.** *En place, à la demande du commanditaire (11/11/2026).* Pour faciliter les essais pendant le développement, la démo ne demande pas le code TOTP : la graine est gardée en clair dans le stockage privé de la démo et le code est calculé automatiquement (`app/src/demo/.../SecondFactor.kt`). Le noyau n'est pas modifié et aucun contournement n'existe dans `prod`, dont le jeu de sources contient un `SecondFactor` vide. Les éléments de démonstration vivent dans `app/src/demo` et ne sont pas compilés dans `prod` ; `tools/check-apk.sh` le contrôle sur l'APK.

**D-21 — État d'accès dans des fichiers, pas dans Room.** *En place.* Paramètres de dérivation, enveloppes de clés, compteur d'échecs et dernier pas TOTP sont écrits par `FileGateStore` (fichier temporaire synchronisé puis renommage atomique) dans `noBackupFilesDir`. Un fichier abîmé lève une erreur et n'est jamais pris pour une installation neuve.

**D-22 — QR par ZXing.** *En place.* Génération locale, sans service distant ni permission.

**D-23 — Navigation par état, sans bibliothèque de navigation.** *En place.* Une seule activité exportée ; l'écran affiché découle de l'état d'accès, donc tout lancement mène à la connexion tant que la session est verrouillée. L'état sauvegardé de l'activité est ignoré.

**D-24 — Stockage des pièces jointes.** *En place, testé sur émulateur.* Un fichier chiffré par pièce dans `noBackupFilesDir/attachments`, nommé d'après un identifiant aléatoire ; métadonnées (nom d'origine compris) chiffrées dans Room. Ajout : fichier temporaire synchronisé, renommage, puis ligne en base. Suppression : ligne, puis fichier. Un arrêt brutal ne peut laisser qu'un fichier chiffré sans ligne, supprimé au démarrage suivant. Un verrouillage pendant un import l'annule.

**D-25 — Validation après import.** *En place.* Une pièce n'est gardée que si elle s'ouvre réellement : image décodable, ou PDF que `PdfRenderer` accepte (un PDF protégé par mot de passe est refusé avec un message explicite).

**D-26 — Visionneuse.** *En place.* Images décodées en mémoire avec un côté borné à 2 560 pixels et orientation EXIF appliquée. PDF rendu une page à la fois ; depuis la mémoire (`memfd`) sur Android 11 et suivants, par fichier temporaire privé avant.

## Lecture automatique des documents

**D-27 — Lecture automatique à l'ajout (OCR), à la demande du commanditaire.** *En place, testée sur émulateur.*
Le cahier des charges plaçait « OCR ou extraction automatique de champs » hors périmètre. Le commanditaire a demandé le 11/11/2026 que l'application lise le document à l'ajout et pré-remplisse la fiche. Choix faits pour rester dans l'esprit du reste :
- **Tout se passe sur le téléphone.** La reconnaissance de texte utilise ML Kit (Google) avec le modèle latin **embarqué dans l'APK**, sans téléchargement. L'application n'ayant pas la permission `INTERNET` (contrôlé sur l'APK à chaque build), ni l'image ni le texte ne peuvent sortir, et la télémétrie de la bibliothèque est sans effet.
- **L'interprétation du texte est dans le noyau** (`DocumentReader`, `Mrz`), en Kotlin pur et testée sur JVM : nature du document, zone lisible par machine des passeports et cartes (ICAO 9303), dates avec leur libellé, numéro, titulaire, établissement, IBAN, BIC.
- **Rien n'est enregistré sans relecture.** Le scan ouvre la fiche pré-remplie ; chaque champ lu porte la mention « Lu sur le document, à vérifier » jusqu'à ce que l'utilisateur le modifie ; la fiche n'existe qu'après « Enregistrer ». Abandonner supprime la pièce scannée.
- **Prudence sur les identifiants.** Une zone lisible par machine n'est utilisée que si au moins deux de ses clés de contrôle sont justes ; un IBAN n'est proposé que si sa clé est juste. Mieux vaut un champ vide qu'un numéro faux.
- Le texte reconnu n'est jamais stocké : seuls les champs validés le sont, chiffrés comme les autres.

**D-30 — Redressement et recadrage du scan.** *En place, testé sur émulateur.*
Demandé par le commanditaire après un essai sur une vraie carte. À l'ajout d'une photo : le texte est reconnu, l'image est remise à l'endroit d'après l'inclinaison du texte (avec essai des autres sens si presque rien n'est lu), puis rognée sur le document. Le rectangle du document est cherché dans le noyau (`DocumentCropper`, Kotlin pur) : plus grande zone d'un seul tenant qui se détache du fond, acceptée seulement si elle contient l'essentiel du texte ; à défaut, la zone de texte avec une marge. Aucune bibliothèque de plus, rien ne sort du téléphone. Limite assumée : pas de correction de perspective. L'alternative écartée pour l'instant est le scanner de documents des services Google Play, qui corrige la perspective mais exige ces services, télécharge un module, ne s'applique pas aux fichiers importés et ne peut pas être testé par la CI.

**D-31 — Valeurs rattachées aux libellés par leur position.** *En place.* La lecture utilise la position des lignes reconnues : la valeur d'un champ est ce qui suit le libellé sur sa ligne, sinon la ligne à sa droite, sinon celle du dessous dans la même colonne. Les libellés sont reconnus en français et en anglais, y compris sur une même ligne (« Prénoms/ GivenNames »), et ne sont jamais pris pour des valeurs.

**D-28 — Aperçus chiffrés.** *En place.* Chaque pièce a un aperçu réduit (JPEG, 720 pixels au plus) chiffré dans un fichier à part. Les listes et la fiche l'affichent sans déchiffrer la pièce entière ; un appui ouvre le document complet. Les aperçus déchiffrés ne vivent qu'en mémoire pendant la session.

**D-29 — Habillage.** *En place, jamais vu à l'écran (voir `ECARTS.md` E-08).* Barre de titre avec retour, cartes avec vignette du document, pastilles d'échéance, action principale fixe en bas des formulaires, icônes Material. Couleurs de conteneur dérivées de la palette du cahier des charges.

## Points ouverts à valider sur appareil

- `setUnlockedDeviceRequired(true)` sur les clés Keystore : plus protecteur, mais son comportement varie selon les versions et les constructeurs. Non activé tant qu'il n'est pas essayé.
- Rendu PDF depuis la mémoire (D-03).
- Temps réel d'Argon2id sur un téléphone à 2 Go de RAM (D-04).
