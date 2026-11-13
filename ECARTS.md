# Écarts

Tout ce qui est incomplet, non vérifié ou différent du cahier des charges. **En l'état, l'application ne doit recevoir aucun document réel.**

## Ce qui manque

**E-02 — Fonctions non écrites.**

| Jalon | Fait | Manque |
| --- | --- | --- |
| J1 Accès | Configuration, connexion en deux étapes, QR d'enrôlement, délai progressif, verrouillage (écran éteint, arrière-plan, inactivité), avertissement sans verrouillage d'écran | Paramètres : changement de phrase et nouvel enrôlement TOTP (la logique existe dans le noyau, pas l'écran) ; réglage des durées de verrouillage |
| J2 Fiches et pièces | Fiches : ajout, modification, favori, duplication (avec ou sans pièces), suppression confirmée, champs personnalisés, masquage, accueil, liste, recherche. Pièces jointes : import par le sélecteur système, photo par l'appareil photo du système, limites, format vérifié par signature, annulation, stockage chiffré, visionneuse image et PDF, suppression indépendante, nettoyage au démarrage | Sélecteur de date (saisie au format JJ/MM/AAAA) |
| J3 Partage et échéances | État des échéances affiché | Partage, presse-papiers, rappels et notifications |
| J4 Sauvegarde | Phrase de récupération (générée, affichée une fois, confirmée par ressaisie), export d'une archive chiffrée vers un emplacement choisi, avec progression et annulation, restauration dans une installation vide avec bascule atomique, rappel sur l'accueil quand la sauvegarde manque ou date de plus de 30 jours | Réglages de rappel dans l'archive (il n'y a pas encore de rappels) ; le rappel n'est pas affiché sur l'écran de connexion |
| J5 Recette | Tests JVM et instrumentés automatiques | Recette manuelle, accessibilité, captures d'écran |

**E-08 — Interface vue sur émulateur seulement.**
Depuis le 12/11/2026, une visite guidée automatique (`ScreenshotTour`) pilote la vraie interface de la démo sur l'émulateur de la CI et publie 14 captures d'écran (données fictives) sur la branche `captures`. Les écrans d'accès, l'accueil, la liste, la fiche, la modification, la visionneuse et le choix du scan ont ainsi été vus et retravaillés. Restent non vérifiés : le thème sombre, le paysage, le clavier ouvert, le texte à 200 %, TalkBack, les petits écrans, et tout ce qui dépend d'un vrai téléphone (scanner de Google, sélecteur de fichiers). L'échec de la visite guidée ne fait pas échouer la CI : vérifier son résultat dans le résumé des tests.

## Écarts de conception

**E-03 — Chiffrement sans Tink.**
Le cahier des charges recommande Tink. Le noyau utilise AES-256-GCM du fournisseur de la plateforme et une construction en segments écrite pour ce projet (`DECISIONS.md`, D-10 et D-11). Les propriétés exigées sont testées, y compris par mutation, mais ce code **n'a pas été relu par un tiers**. Maintenant que les dépôts sont accessibles par la CI, le remplacement par Tink est possible ; il n'est pas fait.

**E-05 — Argon2id non mesuré sur un vrai téléphone.**
La dérivation tourne sur l'émulateur de la CI (Android 14, x86-64). Le temps réel et la tenue en mémoire sur un appareil d'entrée de gamme à 2 Go de RAM sont inconnus.

**E-06 — Biométrie non livrée.** Conforme au cahier des charges (parcours facultatif).

**E-07 — Keystore vérifié sur émulateur seulement.**
`KeystoreDeviceKey` passe ses tests avec le Keystore de l'émulateur. StrongBox et le comportement sur du matériel réel (clé invalidée par un changement de verrouillage, réparation, mise à jour) ne sont pas vérifiés.

**E-09 — Variante `demo` : seconde étape automatique.**
À la demande du commanditaire (11/11/2026), la démo ne demande plus le code TOTP : la graine est gardée **en clair** dans le stockage privé de la démo et le code est calculé automatiquement (`app/src/demo/.../SecondFactor.kt`). La démo n'a donc que la protection de la phrase. Ce raccourci n'existe pas dans `prod`, où la double validation est inchangée ; `tools/check-apk.sh` continue de vérifier l'absence d'élément de démonstration dans l'APK `prod`. À retirer ou à garder comme choix de démonstration une fois le développement terminé.

**E-10 — Graine transmise par intent à l'enrôlement.**
Le bouton « Ouvrir dans mon application d'authentification », exigé par le cahier des charges, envoie le lien `otpauth://` (qui contient la graine) à l'application choisie. C'est la seule sortie de la graine, sur action explicite de l'utilisateur.

**E-11 — Protection contre les superpositions partielle.**
`filterTouchesWhenObscured` est activé. Le masquage complet des fenêtres superposées (Android 12+) demande la permission `HIDE_OVERLAY_WINDOWS`, absente de la liste blanche : non utilisé.

**E-12 — Liste non paginée.**
Les listes affichent au plus 100 fiches à la fois, avec invitation à préciser la recherche. Suffisant pour la volumétrie visée, mais ce n'est pas un vrai défilement paresseux.

**E-13 — Migrations de base.** La base est en version 2 ; la migration 1 → 2 (table des pièces jointes) est testée sur émulateur. Le schéma n'est pas exporté par Room : le test recrée la version 1 à la main.

**E-14 — Pièces jointes : points non vérifiés.**
- Le sélecteur de fichiers et l'appareil photo du système sont lancés par l'interface, que rien ne teste (E-08) : le parcours « choisir un fichier » et « prendre une photo » n'a jamais été exécuté de bout en bout.
- Le refus d'un PDF protégé par mot de passe repose sur l'exception levée par `PdfRenderer` ; aucun test ne fournit un tel PDF.
- Le rendu PDF depuis la mémoire est testé sur Android 14 ; le repli par fichier temporaire (Android 8 à 10) n'a pas été exécuté.
- Une pièce est déchiffrée entièrement en mémoire pour être affichée (20 Mo au plus), ce qui peut être lourd sur un téléphone à 2 Go de RAM.
- La photo de travail reste en clair dans le cache privé pendant la prise de vue ; elle est effacée après chiffrement, après annulation et au démarrage.

**E-15 — Lecture automatique : hors périmètre initial, limites réelles.**
Ajoutée à la demande du commanditaire (`DECISIONS.md`, D-27), contre l'exclusion du cahier des charges. À savoir :
- **Qualité presque pas mesurée sur de vrais documents.** Un seul vrai document a été essayé, par l'utilisateur : une carte d'identité, mal lue dans la première version (libellés bilingues, valeurs sous les libellés, deux colonnes, photo couchée). Sa mise en page est maintenant reproduite dans les tests avec des données fictives, mais la vraie carte n'a pas été rescannée. Les autres tests utilisent des images de texte net fabriquées. Sur une photo réelle (reflets, pli, biais, tampon, hologramme, écriture manuscrite), la lecture sera moins bonne, parfois nulle.
- **Heuristiques.** Hors zone lisible par machine et IBAN, les champs sont devinés d'après des libellés français ou anglais courants. Un document à la mise en page inhabituelle donnera des champs vides ou mal attribués ; c'est pourquoi tout est à relire.
- **Alphabet latin seulement** ; pour un PDF, seule la première page est lue.
- **Recadrage.** Il est fait par le scanner de documents (E-16), où l'utilisateur ajuste les coins. Le recadrage automatique maison, jugé incorrect par l'utilisateur sur une vraie photo, est désactivé. Hors scanner (repli, fichier importé), l'image est seulement remise à l'endroit par quarts de tour. La photo d'origine n'est pas conservée dans le coffre.
- **Bibliothèque fermée.** ML Kit est un composant Google non libre embarqué dans l'APK. Sans permission `INTERNET` il ne peut rien transmettre, mais son code n'est pas auditable ici. L'APK grossit sensiblement (modèle et bibliothèques natives).
- **Mémoire** : le document est tenu entièrement en mémoire pendant le scan (20 Mo au plus), plus l'image décodée.

**E-16 — Scanner de documents : jamais exécuté, et hors de l'esprit « tout hors connexion ».**
Voir `DECISIONS.md`, D-32. Le scanner de Google est intégré et compile, mais **aucun test ne l'exerce** : il dépend des services Google Play et d'une interface que la CI ne peut pas piloter. Son comportement réel (lancement, téléchargement du module, retour de l'image, effacement de la copie temporaire, repli) n'a été vérifié par personne. Dépendances nouvelles : services Google Play, 1,7 Go de RAM, connexion du téléphone à la première utilisation. L'emplacement exact des fichiers temporaires du scanner est supposé (cache privé, dossier commençant par `mlkit_docscan`) : à contrôler sur appareil. Sans scanner, la photo n'est pas recadrée.

**E-17 — Sauvegarde et restauration : ce qui n'est pas vérifié.**
La logique est testée sur émulateur avec le vrai Keystore (`BackupTest`). Ne sont pas vérifiés : les écrans eux-mêmes par un parcours complet (choix de l'emplacement et de l'archive par le sélecteur du système), l'écriture vers un fournisseur de documents distant ou une carte SD, la suppression de l'archive partielle après un échec (au mieux, selon le fournisseur), et une restauration réelle d'un téléphone vers un autre. Les aperçus ne sont pas dans l'archive : ils sont refabriqués à la première consultation. Créer une nouvelle phrase de récupération ne rechiffre pas les anciennes archives, qui restent ouvrables avec l'ancienne phrase seulement. Pendant une restauration, la clé de données neuve reste en mémoire jusqu'à la fin de la configuration de l'accès.

## Limites connues

- **Délai progressif après redémarrage** : le délai en cours repart entier (`DECISIONS.md`, D-07).
- **Bonne phrase puis abandon** : chaque passage à l'étape 2 non conclu compte comme un échec.
- **Normalisation de la phrase** : une chaîne transitoire non effaçable est créée à chaque dérivation ; les champs de saisie Compose gardent aussi la phrase en mémoire le temps de la saisie.
- **Indicateur de robustesse** : heuristique simple, sans dictionnaire.
- **Contrôle du RIB** : longueur et caractères seulement.
- **Fiche abîmée** : elle est comptée et signalée sur l'accueil, mais on ne peut pas encore la supprimer depuis l'interface.
- **Archive** : la version 1 est la seule ; il n'y a pas encore d'ancienne version à relire.

## Écarts levés

- **E-01** (projet jamais compilé) : levé le 11/11/2026. La CI GitHub Actions compile `:core` et `:app`, résout toutes les versions du catalogue, produit l'APK `demo` et l'APK `prod`, et exécute `tools/check-apk.sh`.
- **E-04** (tests du noyau hors Gradle) : levé. `./gradlew :core:test` passe avec Kotlin 2.4.10.

## Avant toute utilisation avec des documents réels

1. Terminer E-02 (partage, rappels, paramètres) et dérouler une vraie restauration d'un téléphone vers un autre (E-17).
2. Lever E-03 (Tink ou relecture).
3. Vérifier l'interface sur appareil (E-08) et mesurer Argon2id (E-05).
4. Dérouler la recette manuelle : absence de sauvegarde et de transfert, notifications, restauration sur un second téléphone.
5. Utiliser la variante `prod` signée, jamais la démo (E-09).
