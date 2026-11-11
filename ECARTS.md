# Écarts

Tout ce qui est incomplet, non vérifié ou différent du cahier des charges. **En l'état, l'application ne doit recevoir aucun document réel.**

## Ce qui manque

**E-02 — Fonctions non écrites.**

| Jalon | Fait | Manque |
| --- | --- | --- |
| J1 Accès | Configuration, connexion en deux étapes, QR d'enrôlement, délai progressif, verrouillage (écran éteint, arrière-plan, inactivité), avertissement sans verrouillage d'écran | Paramètres : changement de phrase et nouvel enrôlement TOTP (la logique existe dans le noyau, pas l'écran) ; réglage des durées de verrouillage |
| J2 Fiches | Ajout, modification, favori, duplication, suppression confirmée, champs personnalisés, masquage des champs sensibles, accueil, liste filtrée, recherche | **Pièces jointes** (import, photo, visualisation, miniatures) ; sélecteur de date (saisie au format JJ/MM/AAAA pour l'instant) |
| J3 Partage et échéances | État des échéances affiché | Partage, presse-papiers, rappels et notifications |
| J4 Sauvegarde | Format et lecture vérifiée dans le noyau | Écrans d'export et de restauration, phrase de récupération, rappel d'export |
| J5 Recette | Tests JVM et instrumentés automatiques | Recette manuelle, accessibilité, captures d'écran |

**E-08 — L'interface n'a été vérifiée par aucun test et je ne l'ai jamais vue.**
Les écrans se compilent et la logique qu'ils appellent est testée, mais aucun test automatique ne parcourt l'interface et aucune capture n'a pu être examinée. Rendu, lisibilité, clavier ouvert, paysage, texte à 200 % et TalkBack restent à vérifier sur appareil.

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

**E-13 — Migrations de base.** La base est en version 1 : il n'y a encore aucune migration à tester. Le schéma n'est pas exporté.

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

1. Terminer E-02 (pièces jointes, partage, rappels, sauvegarde) : sans export, la perte du téléphone ou d'un facteur est définitive.
2. Lever E-03 (Tink ou relecture).
3. Vérifier l'interface sur appareil (E-08) et mesurer Argon2id (E-05).
4. Dérouler la recette manuelle : absence de sauvegarde et de transfert, notifications, restauration sur un second téléphone.
5. Utiliser la variante `prod` signée, jamais la démo (E-09).
