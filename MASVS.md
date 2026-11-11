# Revue OWASP MASVS (v2)

Revue **intermédiaire** au 11/11/2026 : noyau testé, accès et fiches en place côté Android et testés sur émulateur, le reste à faire. Aucun contrôle n'est déclaré satisfait tant que l'application n'est pas complète et vérifiée sur un appareil réel. La colonne de preuve date en partie de la première revue ; `TESTS.md` et `ECARTS.md` font foi pour l'état exact.

Statuts : **Noyau prêt** (logique écrite et testée sur JVM, reste à brancher et à vérifier sur appareil), **Squelette** (écrit, non compilé), **À faire**, **Sans objet**.

| Contrôle | Statut | Preuve ou reste à faire |
| --- | --- | --- |
| **STORAGE-1** Stockage sûr des données sensibles | Noyau prêt | `Aead`, `StreamCipher`, `SessionGate` ; `CryptoTest`, `AccessTest`. Persistance (Room, fichiers) à écrire ; inspection sur appareil à faire. |
| **STORAGE-2** Pas de fuite de données sensibles | Squelette | Sauvegarde désactivée (`AndroidManifest.xml`, `res/xml/*`), `FLAG_SECURE`, journaux retirés par R8 (`proguard-rules.pro`). Clavier, presse-papiers, notifications, états sauvegardés : à faire. Rien vérifié sur appareil. |
| **CRYPTO-1** Cryptographie forte, employée selon les bonnes pratiques | Noyau prêt, avec réserve | AES-256-GCM, HKDF-SHA256, Argon2id (Bouncy Castle), vecteurs RFC 5869, RFC 9106, RFC 6238. Réserve : construction en flux propre au projet au lieu de Tink (`ECARTS.md` E-03). HMAC-SHA1 n'est utilisé que pour le TOTP, par compatibilité. |
| **CRYPTO-2** Gestion des clés | Noyau prêt | Hiérarchie `DECISIONS.md` D-06 ; clé par fichier ; clé d'archive indépendante ; effacement après usage. `KeystoreDeviceKey` en squelette (E-07). |
| **AUTH-1** Protocoles d'authentification à distance | Sans objet | Aucun service distant. |
| **AUTH-2** Authentification locale | Noyau prêt | Phrase + TOTP, délai progressif, anti-rejeu : `AccessTest` (27 tests). Limite du TOTP local documentée dans `MENACES.md`. Biométrie non livrée. |
| **AUTH-3** Authentification supplémentaire pour les opérations sensibles | Noyau prêt | Changement de phrase et nouvel enrôlement TOTP exigent la phrase actuelle (`changePassphrase`, `beginTotpReenroll`). Export : phrase de récupération ressaisie (`RecoveryVerifier`). |
| **NETWORK-1** Trafic réseau sécurisé | Sans objet (à prouver) | Aucune permission `INTERNET` ; retrait forcé dans le manifeste. Preuve attendue : `tools/check-apk.sh` sur l'APK. |
| **NETWORK-2** Épinglage d'identité | Sans objet | — |
| **PLATFORM-1** Mécanismes IPC | Squelette | Un seul composant exporté (lanceur). `FileProvider` de partage, récepteur de démarrage, redirection vers la connexion : à faire. |
| **PLATFORM-2** WebView | Sans objet | Aucune WebView. |
| **PLATFORM-3** Interface utilisateur | Squelette | `FLAG_SECURE` en `prod`. Masquage des champs, protection contre les superpositions, notifications neutres : à faire. |
| **CODE-1** Version de plateforme à jour | Squelette | targetSdk 37, minSdk 26 justifié (`DECISIONS.md` D-03). Non compilé. |
| **CODE-2** Mécanisme de mise à jour | Sans objet | Pas de serveur ; mises à jour par le canal de distribution. |
| **CODE-3** Composants tiers sans vulnérabilité connue | À faire | Une seule dépendance d'exécution hors AndroidX : Bouncy Castle 1.78.1 (version testée, à comparer à la dernière publiée). Analyse des dépendances à lancer quand le build tournera. |
| **CODE-4** Validation des entrées non fiables | Noyau prêt | Archive : longueurs bornées, paramètres de dérivation bornés, identifiants validés, tailles contrôlées avant écriture (`ArchiveTest`). Fichiers importés : signature réelle (`FileSignature`). Intents : à faire. |
| **RESILIENCE-1** Intégrité de la plateforme | À faire | Avertissement si pas de verrouillage d'écran. Pas de détection de root prévue : limite documentée. |
| **RESILIENCE-2** Anti-altération | Squelette | Signature de production hors dépôt ; R8. |
| **RESILIENCE-3** Anti-analyse statique | Squelette | Code réduit et optimisé en release. |
| **RESILIENCE-4** Anti-analyse dynamique | Squelette | `prod` non débogable. |
| **PRIVACY-1** Minimisation de l'accès aux données | Squelette | Liste blanche de permissions ; sélecteurs système, pas de permission de stockage ; appareil photo du système. |
| **PRIVACY-2** Pas d'identification de l'utilisateur | Noyau prêt | Aucun compte, aucun identifiant d'appareil, aucune télémétrie. |
| **PRIVACY-3** Transparence | À faire | Textes d'explication (perte d'un facteur, partage, source conservée) à écrire avec les écrans. |
| **PRIVACY-4** Contrôle par l'utilisateur | Noyau prêt en partie | Export complet du coffre (archive) ; suppression définitive et écrans à faire. |
