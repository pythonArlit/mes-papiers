# Modèle de menaces

Ce document décrit la conception visée. La colonne « État » dit ce qui existe réellement aujourd'hui : **noyau** (écrit et testé sur JVM), **squelette** (écrit, jamais compilé), **prévu** (pas encore écrit). Tant qu'une mesure est « prévue », la menace correspondante n'est pas couverte.

## Ce qu'on protège

1. Le contenu des fiches : titres, étiquettes, champs, catégories, échéances.
2. Les pièces jointes et leurs noms d'origine.
3. Les secrets d'accès : phrase secrète, graine TOTP, clé de données, phrase de récupération.
4. L'existence même de certains documents (par exemple, qu'un passeport expire bientôt).

## Scénarios

| Scénario | Ce que l'attaquant obtient | Mesures | État |
| --- | --- | --- | --- |
| **Téléphone perdu ou volé, verrouillé** | Rien sans casser le verrouillage d'écran. S'il extrait le stockage : des fichiers chiffrés, inutilisables sans la clé Keystore du téléphone **et** la phrase. | Double enveloppe de la clé de données (phrase + Keystore) | Noyau ; Keystore en squelette |
| **Téléphone volé, déverrouillé, application verrouillée** | Doit fournir la phrase puis un TOTP. Essais de phrase limités par le délai progressif, insensible à l'horloge et aux redémarrages. | `SessionGate`, `Backoff` | Noyau |
| **Téléphone volé, déverrouillé, session ouverte** | Accès complet pendant au plus 2 min d'inactivité ou 30 s en arrière-plan. | Verrouillage automatique, extinction d'écran | Calcul en noyau, branchement prévu |
| **Les deux applications sur le même téléphone** | Avec le téléphone déverrouillé et l'application d'authentification non protégée, le second facteur tombe : il ne reste que la phrase. | Avertissement à l'enrôlement, conseil d'un second appareil | Prévu |
| **Application tierce malveillante (sans root)** | Ne lit pas le stockage privé. Peut tenter : superposition d'écran, lecture du presse-papiers, interception d'un partage, envoi d'intents. | `FLAG_SECURE`, filtrage des touches sous superposition, presse-papiers sur demande marqué sensible et vidé après 60 s, un seul composant exporté, toute entrée mène à la connexion | `FLAG_SECURE` et manifeste en squelette, reste prévu |
| **Bibliothèque de reconnaissance de texte (ML Kit, code fermé)** | Voit en clair l'image et le texte de chaque document scanné, dans le processus de l'application. Ne peut rien transmettre : l'application n'a pas la permission `INTERNET`. | Modèle embarqué, aucun téléchargement ; absence d'`INTERNET` contrôlée sur l'APK à chaque build ; texte reconnu jamais stocké | En place |
| **Clavier tiers** | Voit tout ce qui est tapé, phrase comprise. Aucune application ne peut l'empêcher. | Pas d'apprentissage ni de suggestions sur les champs sensibles ; avertissement | Prévu |
| **Extraction des fichiers (sauvegarde ADB, image disque, dépôt en réparation)** | Fichiers chiffrés. Sans la clé Keystore, la phrase ne peut même pas être testée hors ligne. | Enveloppe Keystore sur la graine et sur la clé de données | Noyau (test « fichiers extraits sans le téléphone ») |
| **Sauvegarde cloud ou transfert vers un nouvel appareil** | Rien : aucune donnée de l'application n'est sauvegardée ni transférée. | `allowBackup="false"`, règles d'exclusion pour toutes les versions | Squelette, non vérifié sur appareil |
| **Archive d'export interceptée** (clé USB, messagerie, cloud personnel) | Fichier chiffré sous une clé dérivée d'une phrase de 140 bits. Recherche exhaustive hors de portée. | `ArchiveWriter` | Noyau |
| **Archive modifiée ou piégée donnée à restaurer** | Refus complet : aucune donnée activée. Pas d'écriture hors de la zone de préparation, pas de dérivation démesurée, pas de dépassement de taille. | `ArchiveReader`, bornes sur les paramètres, identifiants validés | Noyau |
| **Destinataire d'un partage** | Garde une copie en clair de ce qui lui a été envoyé, définitivement. | Partage champ par champ ou pièce par pièce, aperçu, rappel explicite, EXIF retirés des photos | Prévu |
| **Autre application pendant un partage** | Seule l'application choisie reçoit un droit de lecture temporaire sur le seul fichier partagé. | `FileProvider` dédié, révocation et effacement après 10 min | Prévu |
| **Notifications sur écran verrouillé** | « Un document arrive à expiration », sans titre ni catégorie. | Texte neutre, calendrier minimal | Texte en squelette, planification prévue |
| **Téléphone sans verrouillage d'écran** | Le vol donne directement accès à l'écran de connexion ; Keystore est moins bien protégé. | Avertissement à l'utilisateur | Prévu |
| **Téléphone rooté ou système compromis** | **Non couvert.** Voir ci-dessous. | — | Limite assumée |

## Ce que le TOTP local protège, et ce qu'il ne protège pas

Le TOTP est vérifié par l'application elle-même, sur le téléphone. C'est une **barrière logique**, pas cryptographique : il ne dérive aucune clé.

- Il protège contre quelqu'un qui connaît ou devine la phrase et utilise l'application normalement (proche, voleur du téléphone déverrouillé, phrase vue par-dessus l'épaule).
- Il ne protège pas contre un attaquant qui **maîtrise le système** (root, exploit, débogueur) **et** connaît la phrase : celui-ci peut dériver `kData`, demander à Keystore de désenvelopper la clé de données et ignorer la vérification du code. La graine elle-même est sur le téléphone, chiffrée, mais déchiffrable avec la phrase.
- Sa force est celle d'un code à six chiffres avec trois pas acceptés : environ une chance sur 330 000 par essai, cinq essais par passage à l'étape 1, chaque passage comptant dans le délai progressif.

## Données lisibles hors session

Voici **tout** ce qui est conçu pour être stocké en clair. Toute autre donnée en clair serait un défaut.

| Donnée | Pourquoi elle doit être lisible | Ce qu'elle révèle |
| --- | --- | --- |
| Paramètres de dérivation (algorithme, version, sel, mémoire, passes) | Nécessaires pour dériver la clé avant toute ouverture | Rien sur la phrase |
| Compteur d'échecs, délai en cours, échéance en temps monotone, compteur de démarrages | Le délai progressif doit tenir coffre verrouillé et après redémarrage | Qu'il y a eu des échecs |
| Dernier pas TOTP accepté | L'anti-rejeu doit tenir après redémarrage | L'heure approximative de la dernière ouverture |
| Calendrier des rappels : identifiant opaque aléatoire et instant de déclenchement | Les rappels doivent partir coffre verrouillé | **Qu'un** document expire vers telle date, sans dire lequel |
| Réglages non sensibles (durées de verrouillage, heure des rappels) | Appliqués avant ouverture | Des préférences |
| Date du dernier export | Rappel d'export sur l'écran de connexion | Une date |
| Enveloppes de la graine et de la clé de données | Chiffrées deux fois (phrase, puis Keystore) | Rien |
| Nombre et taille des fichiers chiffrés | Inévitable | Le nombre de pièces jointes et leur taille approximative |

Le lien entre l'identifiant opaque d'un rappel et la fiche concernée est dans le coffre chiffré.

## Copies en clair temporaires

| Copie | Durée de vie | Nettoyage |
| --- | --- | --- |
| Fichier partagé | 10 min, même session verrouillée | Révocation des droits puis effacement ; systématique au démarrage |
| Photo en cours de capture | Le temps de la prise de vue | Chiffrée puis effacée ; répertoire vidé au démarrage et après annulation |
| PDF affiché, avant Android 11 | Le temps de la consultation | Effacé à la fermeture, au verrouillage et au démarrage |
| Presse-papiers | 60 s | Vidé si la plateforme le permet ; une autre application a pu le lire entre-temps |

Ces répertoires sont privés et exclus des sauvegardes. État : **prévu**.

## Perte d'une clé Keystore

Une clé Keystore peut disparaître : réinitialisation du téléphone, effacement des données de l'application, certaines mises à jour ou réparations, et selon les réglages retenus la suppression du verrouillage d'écran. Le coffre local devient alors **définitivement illisible**, même avec la bonne phrase. L'application le signale (« le coffre de ce téléphone n'est plus lisible ») sans compter d'échec de phrase. La seule issue est de réinstaller puis de restaurer une archive avec la phrase de récupération. C'est pour cela que l'export est proposé dès la fin de la configuration et rappelé ensuite.

## Effacement

Sur mémoire flash, écraser un fichier ne garantit pas la disparition des anciens blocs. La suppression d'une fiche ou d'une pièce repose donc sur le chiffrement : chaque pièce a sa propre clé dérivée, et détruire les enveloppes de clés (désinstallation, réinitialisation) rend tout le reste indéchiffrable. Une copie en clair temporaire, elle, peut subsister physiquement après effacement : d'où des durées de vie courtes et le chiffrement du stockage par Android.

## Mémoire vive

Les clés et la phrase sont tenues dans des tableaux effacés après usage (`wipe`). La JVM peut néanmoins en avoir fait des copies (ramasse-miettes, chaîne transitoire créée par la normalisation Unicode de la phrase, champs de saisie Compose). Un attaquant capable de lire la mémoire du processus pendant une session ouverte obtient la clé de données. Limite assumée.

## Risques résiduels principaux

1. Système compromis ou téléphone rooté.
2. Phrase faible : douze caractères au minimum, sans autre règle ; l'indicateur de robustesse n'est qu'une aide.
3. Les deux facteurs sur le même téléphone.
4. Perte simultanée du téléphone et de la phrase de récupération : tout est perdu, par construction.
5. Construction de chiffrement en flux écrite pour ce projet au lieu de Tink (`ECARTS.md`, E-03) : testée, mais non relue par un tiers.
