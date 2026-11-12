# Tests

Dernière exécution : 11 novembre 2026, par la CI GitHub Actions (`.github/workflows/ci.yml`) sur le dépôt `pythonArlit/mes-papiers`.

## Ce qui a été exécuté

| Niveau | Commande | Environnement | Résultat |
| --- | --- | --- | --- |
| Unitaire JVM, module `:core` | `./gradlew :core:test` | Ubuntu 26.04, JDK 17, Gradle 9.6.0, Kotlin 2.4.10 | **112 tests réussis** : AccessTest 27, ArchiveTest 13, AttachmentTest 6, CryptoTest 15, DocsTest 21, ExtractionTest 23, StoreTest 7 |
| Instrumenté, module `:app` | `./gradlew :app:connectedDemoDebugAndroidTest` | Émulateur Android 14 (API 34), x86-64 | **27 tests réussis** : KeystoreGateTest 7, VaultDbTest 4, AttachmentStoreTest 8, TextReaderTest 8 |
| Lint | `./gradlew :app:lintProdRelease` | Idem | Réussi, sans erreur |
| Compilation | `./gradlew :app:assembleDemoDebug :app:assembleProdRelease` | Idem | Réussie |
| Contrôle de l'APK `prod` | `tools/check-apk.sh` | aapt2 des build-tools | Réussi |

Les mêmes tests du noyau passent aussi hors Gradle (`tools/run-core-tests.sh`, Kotlin 2.0.21, JDK 21).

**Ce qui n'a pas été exécuté :** aucun test d'interface, aucun essai sur un téléphone réel, aucune recette manuelle. Les tests instrumentés tournent sur la variante `demo` en mode debug ; ils n'utilisent pas le raccourci de démonstration et exercent la double validation complète.

### Contrôle par mutation (noyau)

Six défauts de sécurité introduits un par un dans une copie du noyau ; tous ont fait échouer la suite.

| Défaut introduit | Tests en échec |
| --- | --- |
| Anti-rejeu TOTP désactivé | 2 |
| Drapeau « dernier segment » ignoré (troncature non détectée) | 1 |
| Clé de données chargée dès l'étape 1 | 7 |
| Tolérance TOTP portée à trois pas | 2 |
| Délai progressif mesuré à l'heure murale | 2 |
| Contexte du chiffré ignoré | 1 |

## Parcours du cahier des charges

Statuts : **exécuté et réussi**, **exécuté et en échec**, **non exécuté**.

| Parcours | Niveau | Statut | Test ou procédure | Appareil, version |
| --- | --- | --- | --- | --- |
| **Accès hors connexion** : phrase seule sans accès ni clé de données ; TOTP valide ouvre ; erroné, expiré ou déjà accepté refusé | Unitaire JVM | **Exécuté et réussi** | `AccessTest` : `phrase_correcte_seule_ne_charge_pas_la_cle_de_donnees`, `totp_errone_expire_ou_deja_utilise_est_refuse`, vecteurs RFC 6238 | JVM 17 |
| | Instrumenté | **Exécuté et réussi** | `KeystoreGateTest.double_validation_avec_le_vrai_keystore` | Émulateur, Android 14 |
| | Manuel, mode avion, par l'interface | **Non exécuté** | — | — |
| **Délais et anti-rejeu** : survivent au redémarrage et au changement d'horloge | Unitaire JVM | **Exécuté et réussi** | `AccessTest` : `delai_*`, `anti_rejeu_*` (horloge et redémarrage simulés) ; `StoreTest.double_validation_de_bout_en_bout_sur_disque_avec_relance` | JVM 17 |
| | Manuel (vrai redémarrage du téléphone, vraie modification de l'heure) | **Non exécuté** | — | — |
| **Cycle de session** | Unitaire JVM | **Exécuté et réussi** | `AccessTest.verrouillage_et_relance_redemandent_les_deux_etapes`, `etape_intermediaire_expire_apres_trois_minutes` ; `DocsTest.verrouillage_apres_inactivite_et_arriere_plan` | JVM 17 |
| | Instrumenté | **Exécuté et réussi**, partiel | `VaultDbTest.coffre_verrouille_aucune_fiche_accessible` | Émulateur, Android 14 |
| | Manuel (applications récentes, extinction d'écran, passage par l'application d'authentification) | **Non exécuté** | — | — |
| **Fiche et pièces jointes** | Unitaire JVM | **Exécuté et réussi** pour les fiches | `DocsTest` : `fiche_aller_retour_avec_zeros_initiaux_…`, `fiche_chiffree_pour_la_base_…`, `recherche_insensible_…` | JVM 17 |
| | Instrumenté | **Exécuté et réussi** pour les fiches | `VaultDbTest.ajout_modification_suppression_apres_reouverture_de_la_base` (RIB à zéros initiaux compris) | Émulateur, Android 14 |
| | Unitaire JVM, pièces jointes | **Exécuté et réussi** | `AttachmentTest` : format vérifié par signature, taille limite, annulation, liaison à l'identifiant, métadonnées chiffrées | JVM 17 |
| | Instrumenté, pièces jointes | **Exécuté et réussi** | `AttachmentStoreTest` : `import_relecture_et_rien_en_clair_sur_le_disque`, `import_refuse_ou_annule_ne_laisse_aucun_fichier` (annulation et verrouillage en cours d'import), `suppression_et_nettoyage_des_orphelins_au_demarrage`, `pdf_rendu_page_par_page_sans_copie_en_clair_restante`, `image_decodee_avec_dimensions_bornees`, `migration_de_la_base_version_1_vers_2_sans_perte` | Émulateur, Android 14 |
| | Par l'interface : sélecteur de fichiers, appareil photo, PDF protégé par mot de passe | **Non exécuté** | — | — |
| **Lecture automatique à l'ajout** (ajoutée au périmètre, `DECISIONS.md` D-27) | Unitaire JVM | **Exécuté et réussi** | `ExtractionTest` : zone lisible par machine (spécimens ICAO, erreurs d'OCR, clés fausses refusées), dates par libellé, numéro à zéros initiaux, diplôme, RIB avec IBAN et BIC, IBAN à clé fausse non proposé, texte sans rapport | JVM 17 |
| | Instrumenté | **Exécuté et réussi** | `TextReaderTest` : image → texte reconnu sur l'appareil → champs proposés (carte d'identité, zone de passeport, image vide) ; images de texte net fabriquées pour le test | Émulateur, Android 14 |
| | Unitaire JVM, recadrage et mise en page réelle | **Exécuté et réussi** | `ExtractionTest` : carte bilingue sur deux colonnes avec valeurs sous les libellés (mise en page d'une vraie carte signalée par l'utilisateur, données fictives), valeur à droite du libellé, recadrage clair sur sombre, sombre sur clair, repli sur le texte, zone sans texte refusée, rien à rogner | JVM 17 |
| | Instrumenté, redressement et recadrage | **Exécuté et réussi** | `TextReaderTest` : `photo_couchee_ou_a_l_envers_redressee` (90°, 180°, 270°), `arriere_plan_rogne_autour_du_document`, `photo_couchee_sur_fond_sombre_redressee_puis_rognee`, `carte_bilingue_sur_deux_colonnes` | Émulateur, Android 14 |
| | Scanner de documents (bords, coins, perspective), `DECISIONS.md` D-32 | **Non exécuté** : compilé seulement, aucun test possible en CI | À faire à la main : scanner une carte, ajuster les coins, vérifier la fiche et l'image conservée | — |
| | Recadrage automatique maison | **Exécuté par l'utilisateur, en échec** le 12/11/2026 sur une vraie photo ; désactivé depuis (les tests automatiques sur images fabriquées, eux, passent) | — | Téléphone de l'utilisateur |
| | Sur de vraies photos de documents, par l'interface | **Exécuté une fois par l'utilisateur, en échec** le 11/11/2026 sur une carte d'identité (prénom mal lu, pas de recadrage) ; corrigé depuis, **non réexécuté** sur la vraie carte | Démo installée par l'utilisateur | Téléphone de l'utilisateur |
| **Partage** | Tous | **Non exécuté** | Non écrit | — |
| **Rappels** | Unitaire JVM | **Exécuté et réussi** pour le calcul | `DocsTest` : `etat_des_echeances`, `rappels_*` | JVM 17 |
| | Notifications coffre verrouillé, replanification | **Non exécuté** | Non écrit | — |
| **Restauration** | Unitaire JVM | **Exécuté et réussi** pour le format | `ArchiveTest` (13 tests) | JVM 17 |
| | Sur un second téléphone, bascule atomique | **Non exécuté** | Non écrit | — |
| **Perte d'un facteur** | Unitaire JVM | **Exécuté et réussi** | `AccessTest.sans_phrase_ou_sans_totp_aucun_acces`, `cle_keystore_perdue_coffre_illisible` | JVM 17 |
| | Instrumenté | **Exécuté et réussi** | `KeystoreGateTest.cle_keystore_perdue_signalee_sans_compter_d_echec`, `cle_supprimee_coffre_illisible_sans_recreation_silencieuse` | Émulateur, Android 14 |
| **Stockage** | Unitaire JVM | **Exécuté et réussi** | `StoreTest.aucun_secret_en_clair_dans_les_fichiers_d_etat`, `CryptoTest.flux_ne_laisse_pas_le_clair_dans_le_chiffre`, `ArchiveTest.archive_ne_contient_rien_en_clair` | JVM 17 |
| | Instrumenté | **Exécuté et réussi** pour la base et l'état d'accès | `VaultDbTest.aucune_donnee_en_clair_dans_les_fichiers_de_la_base` (base, journal WAL, mémoire partagée) ; `KeystoreGateTest.etat_d_acces_hors_des_sauvegardes_et_sans_secret` | Émulateur, Android 14 |
| | Caches, logcat, après consultation et partage | **Non exécuté** | — | — |
| **Manifeste et variantes** | Script sur l'APK `prod` | **Exécuté et réussi** | `tools/check-apk.sh` : pas d'`INTERNET`, liste blanche, non débogable, `allowBackup=false`, règles d'extraction, un seul composant exporté, aucun élément de démonstration | CI |
| | Instrumenté | **Exécuté et réussi** | `KeystoreGateTest.manifeste_installe_sans_internet_ni_sauvegarde` | Émulateur, Android 14 |
| | Sauvegarde et transfert d'appareil réels | **Non exécuté** | — | — |
| **Interfaces** | Visite guidée automatique avec captures d'écran | **Exécuté et réussi** (deux échecs sur des essais précédents, dus à un écran en cours de mise à jour ; corrigé) | `ScreenshotTour` : configuration, connexion, accueil, liste, fiche, modification, visionneuse, choix du scan ; captures sur la branche `captures` | Émulateur Pixel 6, Android 14 |
| | Compact, paysage, clavier, texte à 200 %, TalkBack, thème sombre | **Non exécuté** | — | — |

## Commandes

```
./gradlew :core:test                             # tests JVM du noyau
./gradlew :app:connectedDemoDebugAndroidTest     # tests instrumentés (appareil ou émulateur branché)
./gradlew :app:assembleDemoDebug                 # APK de démonstration
./gradlew :app:assembleProdRelease && tools/check-apk.sh app/build/outputs/apk/prod/release/*.apk
tools/run-core-tests.sh                          # tests du noyau sans Gradle ni réseau
```
