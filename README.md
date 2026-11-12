# Mes Papiers

> **État au 11 novembre 2026 : ne pas utiliser avec des documents réels.**
>
> - L'application se compile et s'installe, mais elle est **incomplète** : pas de partage, pas de rappels, **pas de sauvegarde**. Sans sauvegarde, perdre le téléphone ou un facteur d'accès, c'est tout perdre.
> - La variante `demo` ne demande **pas** le code de l'application d'authentification (raccourci de démonstration) : elle n'a que la protection de la phrase secrète.
> - L'interface n'a été vérifiée par aucun test ni sur aucun téléphone réel.
> - Le scanner de documents (détection des bords, coins ajustables) dépend des services Google Play et n'a jamais été exécuté : il compile, sans plus.
> - La lecture automatique des documents propose des valeurs qui peuvent être fausses : elle n'a pas été essayée sur de vrais documents. Tout est à relire avant d'enregistrer.
> - Le chiffrement n'utilise pas Tink mais AES-256-GCM de la plateforme avec une construction en flux propre au projet, non relue par un tiers.
>
> Détail et liste des points à lever : [`ECARTS.md`](ECARTS.md).

Application Android individuelle et hors connexion pour conserver et retrouver pièces d'identité, diplômes, actes, justificatifs d'adresse, coordonnées bancaires et autres documents personnels. Accès par phrase secrète puis code TOTP ; données chiffrées sur le téléphone ; sauvegarde par archive chiffrée.

## Avancement par jalon

| Jalon | Noyau (`:core`) | Application (`:app`) |
| --- | --- | --- |
| J0 Architecture, menaces, décisions | Fait | — |
| J1 Accès et stockage chiffré | Fait et testé | Fait : configuration, connexion en deux étapes, verrouillage ; testé sur émulateur avec le vrai Keystore. Manquent les écrans de paramètres |
| J2 Fiches et pièces jointes | Fait et testé | Fait : fiches (Room chiffré, accueil, liste, détail, édition) et pièces jointes (import, photo, visionneuse image et PDF). Scan avec lecture automatique et fiche pré-remplie à relire ; aperçus des documents |
| J3 Partage et échéances | Calcul fait et testé | État des échéances affiché. Partage et rappels à faire |
| J4 Sauvegarde et restauration | Format fait et testé | À faire |
| J5 Recette et revue MASVS | 104 tests JVM, contrôle par mutation | 23 tests instrumentés ; recette manuelle à faire |

Des captures d'écran de la démo (données fictives) sont publiées par la CI sur la branche `captures`.

Chaque poussée sur `main` déclenche la CI : tests du noyau, lint, APK `demo` et `prod`, contrôle de l'APK `prod`, tests sur émulateur. L'APK de démonstration est téléchargeable dans les artefacts de l'exécution (`apk-demo`).

## Organisation du dépôt

```
core/    noyau Kotlin pur, sans dépendance Android (sources et tests)
app/     application Android : src/main, src/prod, src/demo
tools/   run-core-tests.sh (tests du noyau sans Gradle), check-apk.sh (contrôle de l'APK prod)
```

Documents : `DECISIONS.md` (choix et versions), `MENACES.md` (modèle de menaces et données lisibles hors session), `TESTS.md` (ce qui a été exécuté ou non), `ECARTS.md`, `MASVS.md`.

## Prérequis

- JDK 17 ou plus récent.
- Pour l'application : Android Studio récent ou SDK Android en ligne de commande (plateforme 37, build-tools 36), et un accès à `google()` et `mavenCentral()`.

## Commandes

Tests du noyau, sans Gradle ni réseau :

```
tools/run-core-tests.sh /chemin/vers/une/distribution/gradle
```

Par Gradle (ce que la CI exécute) :

```
./gradlew :core:test                 # tests JVM du noyau
./gradlew :app:assembleDemoDebug     # APK de démonstration
./gradlew :app:lintProdRelease       # lint de la variante prod
./gradlew :app:connectedDemoDebugAndroidTest   # tests instrumentés, appareil ou émulateur branché
```

## Produire un APK

La compilation des deux APK est exécutée par la CI. La signature de production, elle, n'a jamais été essayée.

**APK de démonstration** (identifiant `app.mespapiers.demo`, bandeau « DÉMO », captures autorisées) :

```
./gradlew :app:assembleDemoDebug
# → app/build/outputs/apk/demo/debug/app-demo-debug.apk
adb install -r app/build/outputs/apk/demo/debug/app-demo-debug.apk
```

**APK de production** (non débogable, code réduit, journaux retirés). La signature reste hors du dépôt :

1. Créer un magasin de clés en dehors du dépôt :
   `keytool -genkeypair -v -keystore ~/cles/mespapiers.jks -alias mespapiers -keyalg RSA -keysize 4096 -validity 9125`
2. Créer, hors du dépôt, un fichier de propriétés :
   ```
   storeFile=/chemin/absolu/mespapiers.jks
   storePassword=…
   keyAlias=mespapiers
   keyPassword=…
   ```
3. Compiler puis contrôler l'APK :
   ```
   MESPAPIERS_SIGNING=/chemin/absolu/signature.properties ./gradlew :app:assembleProdRelease
   tools/check-apk.sh app/build/outputs/apk/prod/release/*.apk
   ```

Sans `MESPAPIERS_SIGNING`, l'APK `prod` est produit non signé (`app-prod-release-unsigned.apk`) et ne peut pas être installé. `tools/check-apk.sh` refuse l'APK s'il contient `INTERNET`, une permission hors liste blanche, un élément de démonstration, s'il est débogable ou si la sauvegarde automatique n'est pas désactivée.

## Variantes

| | `prod` | `demo` |
| --- | --- | --- |
| Identifiant | `app.mespapiers` | `app.mespapiers.demo` |
| Données fictives | Absentes du code | `app/src/demo` |
| Captures d'écran | Bloquées | Autorisées |
| Double validation | Phrase puis TOTP | **Phrase seule** : le TOTP est calculé automatiquement (raccourci de démonstration, `ECARTS.md` E-09) |
