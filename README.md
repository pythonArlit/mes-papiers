# Mes Papiers

> **État au 11 novembre 2026 : ne pas utiliser avec des documents réels.**
>
> - L'application Android **n'existe pas encore** : le module `:app` est un squelette qui n'a **jamais été compilé** (pas de SDK Android ni d'accès aux dépôts dans l'environnement de développement). Aucun APK, aucune capture d'écran.
> - Seul le noyau de sécurité (`:core`, Kotlin pur) est écrit et testé : 75 tests JVM réussis.
> - Le chiffrement n'utilise pas Tink mais AES-256-GCM de la plateforme avec une construction en flux propre au projet, non relue par un tiers.
>
> Détail et liste des points à lever : [`ECARTS.md`](ECARTS.md).

Application Android individuelle et hors connexion pour conserver et retrouver pièces d'identité, diplômes, actes, justificatifs d'adresse, coordonnées bancaires et autres documents personnels. Accès par phrase secrète puis code TOTP ; données chiffrées sur le téléphone ; sauvegarde par archive chiffrée.

## Avancement par jalon

| Jalon | Noyau (`:core`, testé) | Application (`:app`) |
| --- | --- | --- |
| J0 Architecture, menaces, décisions | Fait : `DECISIONS.md`, `MENACES.md` | — |
| J1 Accès et stockage chiffré | Fait : dérivation, hiérarchie de clés, TOTP, anti-rejeu, délai progressif, session, chiffrement | Squelette : horloge, clé Keystore, manifeste |
| J2 Fiches et pièces jointes | Fait : modèle, sérialisation, chiffrement en flux, recherche, contrôles de format, signature de fichier | À faire |
| J3 Partage et échéances | Fait : état des échéances, calendrier des rappels, verrouillage automatique | À faire |
| J4 Sauvegarde et restauration | Fait : phrase de récupération, format d'archive, lecture vérifiée | À faire |
| J5 Recette et revue MASVS | Tests JVM et contrôle par mutation : `TESTS.md` ; revue intermédiaire : `MASVS.md` | À faire |

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

Tests du noyau, sans Gradle ni réseau (c'est la commande qui a été exécutée) :

```
tools/run-core-tests.sh /chemin/vers/une/distribution/gradle
```

Par Gradle (non exécuté à ce jour) :

```
./gradlew :core:test                 # tests JVM du noyau
./gradlew :app:assembleDemoDebug     # APK de démonstration
./gradlew :app:lintProdRelease       # lint de la variante prod
```

## Produire un APK

Procédure prévue, **jamais déroulée** ; elle devra être corrigée à la première compilation.

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
   tools/check-apk.sh app/build/outputs/apk/prod/release/app-prod-release.apk
   ```

Sans `MESPAPIERS_SIGNING`, l'APK `prod` est produit non signé. `tools/check-apk.sh` refuse l'APK s'il contient `INTERNET`, une permission hors liste blanche, un élément de démonstration, s'il est débogable ou si la sauvegarde automatique n'est pas désactivée.

## Variantes

| | `prod` | `demo` |
| --- | --- | --- |
| Identifiant | `app.mespapiers` | `app.mespapiers.demo` |
| Données fictives | Absentes du code | `app/src/demo` |
| Captures d'écran | Bloquées | Autorisées |
| Double validation | Réelle | Réelle, sans raccourci |
