# Écarts

Tout ce qui est incomplet, non vérifié ou différent du cahier des charges. **En l'état, l'application n'est pas utilisable et ne doit recevoir aucun document réel.**

## Bloquants

**E-01 — Le projet Android n'a jamais été compilé.**
L'environnement de développement n'avait ni SDK Android ni accès aux dépôts Google, Maven Central et Gradle (refus du proxy, constaté le 11/11/2026). Conséquences :
- aucun APK, ni `demo` ni `prod` ; aucune capture d'écran ;
- le build Gradle (`settings.gradle.kts`, catalogue de versions, `app/build.gradle.kts`) n'a jamais été évalué ; plusieurs versions du catalogue sont supposées (`DECISIONS.md`, D-02) ;
- le wrapper Gradle a été généré hors ligne par Gradle 8.14.3 et pointe vers Gradle 9.6.0, jamais téléchargé ;
- `tools/check-apk.sh` et `.github/workflows/ci.yml` sont écrits mais n'ont jamais tourné.

**E-02 — Le module `:app` est un squelette.**
Il contient le manifeste, les règles de sauvegarde, les variantes `prod` et `demo`, l'icône, un écran d'attente, `AndroidClock` et `KeystoreDeviceKey`. Aucun de ces fichiers n'a été compilé. Ne sont **pas écrits** :

| Jalon | Manque |
| --- | --- |
| J1 | Écrans de configuration et de connexion, QR d'enrôlement, `GateStore` persistant et atomique, branchement du verrouillage automatique, avertissement sans verrouillage d'écran |
| J2 | Room, stockage des pièces, import par sélecteur et photo, visualisation image et PDF, miniatures, écrans accueil / liste / détail / édition |
| J3 | Partage (aperçu, `FileProvider`, nettoyage, EXIF), presse-papiers, planification des rappels et notifications |
| J4 | Écrans d'export et de restauration, zone de préparation sur disque, bascule atomique |
| J5 | Tests instrumentés, recette manuelle, accessibilité |

La logique correspondante existe et est testée dans `:core` pour J1 (accès, clés, délais), une partie de J2 (modèle, chiffrement des pièces, recherche, contrôles), le calcul de J3 (échéances, rappels) et le format de J4 (archive).

## Écarts de conception

**E-03 — Chiffrement sans Tink.**
Le cahier des charges recommande Tink. Tink n'a pas pu être téléchargé ; le noyau utilise AES-256-GCM du fournisseur de la plateforme et une construction en segments écrite pour ce projet (`DECISIONS.md`, D-10 et D-11). Les propriétés exigées sont testées, y compris par mutation, mais ce code **n'a pas été relu par un tiers**. À lever : soit faire relire `crypto/Aead.kt` (environ 250 lignes), soit le remplacer par `StreamingAead` et `Aead` de Tink derrière les mêmes fonctions, et repasser les tests.

**E-04 — Tests du noyau exécutés hors Gradle, avec Kotlin 2.0.21.**
Le build vise Kotlin 2.4.10 ; la suite a tourné avec le compilateur 2.0.21 embarqué dans Gradle 8.14.3. À lever : `./gradlew :core:test`.

**E-05 — Argon2id non mesuré sur téléphone.**
Les paramètres par défaut (32 Mio, passes calibrées) et l'usage d'une implémentation en pur JVM n'ont été exercés que sur un serveur. Le temps réel et la tenue en mémoire sur un appareil à 2 Go de RAM sont inconnus.

**E-06 — Biométrie non livrée.** Conforme au cahier des charges (parcours facultatif, non validable ici).

**E-07 — `KeystoreDeviceKey` jamais exécuté.**
StrongBox, le repli sur le TEE et le comportement en cas de clé invalidée ne sont vérifiés que par une clé logicielle de remplacement dans les tests JVM.

## Limites connues du noyau

- **Délai progressif après redémarrage** : le délai en cours repart entier, y compris s'il était presque purgé. Choix protecteur (`DECISIONS.md`, D-07).
- **Bonne phrase puis abandon** : chaque passage à l'étape 2 non conclu compte comme un échec.
- **Normalisation de la phrase** : une chaîne transitoire non effaçable est créée à chaque dérivation.
- **Indicateur de robustesse** : heuristique simple, sans dictionnaire.
- **Contrôle du RIB** : longueur et caractères seulement, pas de clé de contrôle (les formats varient selon les pays).
- **Détection des PDF protégés par mot de passe** : non écrite, prévue côté Android au moment du rendu.
- **Archive** : la version 1 est la seule ; le chemin « lire une version plus ancienne » existe dans le code mais n'a rien à lire pour l'instant.

## Avant toute utilisation avec des documents réels

1. Compiler et faire passer `./gradlew :core:test` (E-01, E-04).
2. Écrire `:app` jalon par jalon (E-02), avec les tests instrumentés.
3. Lever E-03 (Tink ou relecture).
4. Mesurer Argon2id sur un appareil d'entrée de gamme (E-05).
5. Exécuter la recette manuelle et `tools/check-apk.sh` sur l'APK `prod`.
6. Vérifier sur appareil l'absence de sauvegarde et de transfert.
