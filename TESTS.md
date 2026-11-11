# Tests

Dernière exécution : 11 novembre 2026.

## Ce qui a été exécuté

**Un seul niveau a été exécuté : les tests unitaires JVM du module `:core`.** 75 tests, 75 réussis.

| | |
| --- | --- |
| Commande exécutée | `tools/run-core-tests.sh` |
| Environnement | Linux x86-64, OpenJDK 21.0.12, compilateur Kotlin 2.0.21, JUnit 4.13.2, Bouncy Castle 1.78.1 |
| Résultat | `OK (75 tests)` : AccessTest 27, ArchiveTest 13, CryptoTest 15, DocsTest 20 |
| Compilation | Sans avertissement (`-Werror`) |

Deux précisions :

- Ces tests ont été lancés par un script qui appelle directement le compilateur Kotlin, **pas par Gradle** : les dépôts Maven étaient inaccessibles. La commande normale, `./gradlew :core:test`, compile les mêmes sources mais **n'a pas été exécutée**.
- **Rien n'a été compilé ni exécuté côté Android** : pas de SDK, pas d'émulateur, pas d'appareil. Aucun APK n'existe.

### Contrôle par mutation

Pour vérifier que les tests détectent réellement une régression de sécurité, six défauts ont été introduits un par un dans une copie du noyau. Tous ont fait échouer la suite.

| Défaut introduit | Tests en échec |
| --- | --- |
| Anti-rejeu TOTP désactivé | 2 |
| Drapeau « dernier segment » ignoré (troncature non détectée) | 1 |
| Clé de données chargée dès l'étape 1 | 7 |
| Tolérance TOTP portée à trois pas | 2 |
| Délai progressif mesuré à l'heure murale | 2 |
| Contexte du chiffré ignoré | 1 |

## Parcours du cahier des charges

Statuts : **exécuté et réussi**, **exécuté et en échec**, **non exécuté**. Chaque parcours est découpé entre sa logique (noyau, JVM) et sa réalisation sur appareil.

| Parcours | Niveau | Statut | Commande ou procédure | Appareil, version |
| --- | --- | --- | --- | --- |
| **Accès hors connexion** : phrase seule sans accès ni clé de données ; TOTP valide ouvre ; TOTP erroné, expiré ou déjà accepté refusé | Unitaire JVM | **Exécuté et réussi** | `AccessTest` : `phrase_correcte_seule_ne_charge_pas_la_cle_de_donnees`, `totp_errone_expire_ou_deja_utilise_est_refuse`, `totp_sans_etape_1_est_refuse`, vecteurs RFC 6238 | JVM 21 |
| | Instrumenté (Keystore réel) | **Non exécuté** | À écrire | — |
| | Manuel, mode avion | **Non exécuté** | À écrire | — |
| **Délais et anti-rejeu** : survivent au redémarrage et au changement d'horloge | Unitaire JVM | **Exécuté et réussi** | `AccessTest` : `delai_progressif_apres_cinq_phrases_erronees_persistant`, `delai_insensible_au_changement_d_horloge`, `delai_survit_au_redemarrage_et_repart_de_zero`, `anti_rejeu_survit_au_redemarrage_de_l_application`, `anti_rejeu_resiste_au_recul_de_l_horloge` (horloge et redémarrage simulés) | JVM 21 |
| | Instrumenté et manuel (vrai redémarrage, vraie persistance) | **Non exécuté** | À écrire | — |
| **Cycle de session** : deux étapes après relance, verrouillage, expiration ; passage par l'application d'authentification | Unitaire JVM | **Exécuté et réussi** | `AccessTest` : `verrouillage_et_relance_redemandent_les_deux_etapes`, `etape_intermediaire_expire_apres_trois_minutes` ; `DocsTest` : `verrouillage_apres_inactivite_et_arriere_plan`, `delai_de_grace_borne_…` | JVM 21 |
| | Instrumenté et manuel (notification, intent, applications récentes) | **Non exécuté** | Écrans non écrits | — |
| **Fiche et pièces jointes** : diplôme avec PDF, recto et verso, RIB à zéros initiaux, recherche | Unitaire JVM | **Exécuté et réussi** pour la sérialisation, les zéros initiaux, la recherche, la signature de fichier, le chiffrement des pièces | `DocsTest` : `fiche_aller_retour_avec_zeros_initiaux_…`, `recherche_insensible_…`, `signature_de_fichier_…` ; `CryptoTest` : `flux_*` | JVM 21 |
| | Instrumenté (Room, import annulé ou interrompu sans orphelin) | **Non exécuté** | Persistance non écrite | — |
| **Partage** : un seul champ ou une seule pièce, sans localisation EXIF, nettoyage | Tous | **Non exécuté** | Non écrit | — |
| **Rappels** : échéances proches, passées, sans date | Unitaire JVM | **Exécuté et réussi** pour le calcul | `DocsTest` : `etat_des_echeances`, `rappels_*`, `aujourd_hui_depend_du_fuseau_…` | JVM 21 |
| | Instrumenté et manuel (notification coffre verrouillé, replanification) | **Non exécuté** | Planification non écrite | — |
| **Restauration** : autre installation ; mauvais secret, fichier modifié ou tronqué | Unitaire JVM | **Exécuté et réussi** pour le format : aller-retour, mauvaise phrase, modification et troncature échantillonnées sur toute l'archive, version inconnue, limites | `ArchiveTest` (13 tests) | JVM 21 |
| | Manuel sur un second téléphone, bascule atomique, nouvel enrôlement | **Non exécuté** | Non écrit | — |
| **Perte d'un facteur** | Unitaire JVM | **Exécuté et réussi** | `AccessTest` : `sans_phrase_ou_sans_totp_aucun_acces`, `cle_keystore_perdue_coffre_illisible` ; `ArchiveTest` : `export_puis_restauration_…` | JVM 21 |
| | Manuel de bout en bout | **Non exécuté** | — | — |
| **Stockage** : inspection des fichiers, base, journal SQLite, caches, logcat | Unitaire JVM | **Exécuté et réussi**, partiel : aucune donnée en clair dans un fichier chiffré ni dans l'archive | `CryptoTest.flux_ne_laisse_pas_le_clair_dans_le_chiffre`, `ArchiveTest.archive_ne_contient_rien_en_clair` | JVM 21 |
| | Inspection sur appareil | **Non exécuté** | Procédure à écrire avec la persistance | — |
| **Manifeste et variantes** : APK `prod` sans `INTERNET`, liste blanche, sans démo, non débogable | Script sur APK | **Non exécuté** | `tools/check-apk.sh <apk>` (écrit, jamais lancé) | — |
| **Interfaces** : compact, paysage, clavier, texte à 200 %, TalkBack | Manuel | **Non exécuté** | Écrans non écrits | — |

## Commandes

```
tools/run-core-tests.sh          # tests du noyau sans Gradle ni réseau (exécuté)
./gradlew :core:test             # même chose par Gradle (non exécuté)
./gradlew :app:assembleDemoDebug # APK de démonstration (non exécuté)
./gradlew :app:assembleProdRelease && tools/check-apk.sh app/build/outputs/apk/prod/release/*.apk   # (non exécuté)
```
