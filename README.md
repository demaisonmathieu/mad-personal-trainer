# MAD Personal Trainer

Coach sportif personnel sur Android (Kotlin + Jetpack Compose). Premier module : piloter un vélo elliptique **Domyos EL500** en Bluetooth Low Energy via le protocole standard **FTMS** (Fitness Machine Service).

## Vision

MAD Personal Trainer a vocation à accueillir d'autres machines et d'autres usages : autres appareils FTMS (vélos, tapis, rameurs), ceintures cardio, plans d'entraînement, suivi de progression, synchronisation avec Strava ou Health Connect, etc. Le support du EL500 est la première brique.

## Fonctionnalités (module vélo elliptique)

- **Connexion BLE** : scan, connexion, reconnexion automatique, mémorisation du dernier appareil.
- **Stats en direct** : puissance, cadence (pas/min), vitesse, distance, résistance, calories, cardio (si la console le transmet), courbe de puissance.
- **Contrôle** : réglage de la résistance cible et de la puissance cible (mode ERG), avec start, pause et stop envoyés à la console.
- **Éditeur de séances** : étapes en résistance, en puissance ou libres, avec durée et cible ; réordonnancement, duplication et ajout rapide de blocs d'intervalles. Trois programmes d'exemple sont fournis.
- **Exécution automatique** : l'app envoie la cible de chaque étape au bon moment, avec un compte à rebours, la prévisualisation de l'étape suivante et un bouton pour passer à l'étape suivante.
- **Historique** : sauvegarde locale de chaque séance, totaux cumulés, et courbes de puissance, de cadence, de résistance et de cardio.
- **Journal Bluetooth** dans l'onglet Vélo, pour diagnostiquer ce qu'envoie la console.

## Récupérer l'APK

**Lien direct, depuis le téléphone, vers la dernière version :**
https://github.com/demaisonmathieu/mad-personal-trainer/releases/latest/download/MADPersonalTrainer.apk

Il faut être connecté à GitHub dans le navigateur si le dépôt est privé. Chaque push sur `main` compile l'app et publie une nouvelle Release (`v1.0.<n>`). L'APK est toujours signé avec la même clé (`app/release.jks`) : les mises à jour s'installent donc par-dessus la version précédente sans perdre l'historique.

Pour installer : ouvre le lien, ouvre l'APK téléchargé, autorise « Installer des applis inconnues » pour le navigateur, puis touche *Installer*.

## Mises à jour depuis l'app

Au démarrage, l'app interroge la dernière Release GitHub (le dépôt doit être **public**). Si une version plus récente existe, un bandeau « Version 1.0.N disponible » apparaît. **Installer** télécharge l'APK et ouvre l'installeur Android, qui demande une confirmation. La nouvelle version s'installe par-dessus l'ancienne, sans perte de données.

La première fois, Android demande d'autoriser MAD Personal Trainer à installer des applis : active l'option, reviens dans l'app et appuie de nouveau sur **Installer**. Le bouton **Vérifier** (onglet Vélo, carte Application) relance la recherche à la main.

## Ma semaine et plan commun

- **Plan commun** : le coach génère **une seule semaine** qui répartit course et elliptique (plus des jours réservés au renfo). L'elliptique sert de récupération active, ou de solution quand l'impact de la course est déconseillé.
- **Séances en zones cardio** : elles fonctionnent sur les deux sports.
  - En course : guidage vocal « accélère / ralentis ».
  - Sur l'elliptique : la résistance s'ajuste automatiquement toutes les 15 s pour rester dans la zone (cardio de la montre).
- **Faire sur l'autre machine** (menu ⋮ d'un programme, ou ⇄ dans Ma semaine) : convertit la séance (résistance, watts ou allure → zones cardio) et la lance dans l'autre mode.
- **Onglet Semaine** :
  - les séances du plan et du renfo, jour par jour, avec la coche « faite » (automatique quand la séance est enregistrée) et ▶ pour lancer directement dans le bon mode ;
  - un bilan prévu / fait ;
  - le réalisé de la semaine : minutes par sport, km, et temps par zone cardio tous sports confondus, avec le % passé en endurance facile.

## Mode Running

Le sélecteur **Elliptique / Running**, en haut de l'app, bascule tous les onglets. On ne peut pas changer de mode pendant une séance.

- **Capteurs** : la montre cardio.
- **Course** : sortie en extérieur.
  - Mesures : distance et allure par le GPS du téléphone, cardio par la montre.
  - Guidage : annonces vocales en français et vibrations à chaque étape, à chaque kilomètre, et quand il faut accélérer ou ralentir (allure ±15 s/km, ou zone cardio).
  - Écran éteint : le suivi continue (service de premier plan, notification avec le temps, la distance et l'allure).
- **Programmes** : séances running par étapes.
  - Chaque étape se termine à une **durée** ou à une **distance** (ex. 400 m).
  - La consigne est une **allure** (m:ss/km), une **zone cardio** (1 à 5) ou libre.
- **Historique** : distance, allure, cardio, temps au kilomètre et tracé du parcours.
- **Coach** : prompt et plan propres au running. Ajoute tes *Repères running* dans le profil.

Autorisations demandées au premier départ : localisation précise et notifications.

## Coach personnel (IA)

Onglet **Coach** :

1. **Profil** : âge, poids, taille, poids visé, FC repos et FC max, objectif (perte de poids, remise en forme, endurance, santé cardio, performance), niveau, séances par semaine, durée des séances, durée du programme, préférences et contraintes.
2. **Générer mon programme** : l'app construit un prompt avec ton profil, tes 15 dernières séances, les capacités de la machine (résistance de 1 à 15, ERG simulé) et tes zones cardio.
   - **Avec Claude (API)** : renseigne ta clé dans *Réglages IA*, puis appuie sur *Générer avec Claude*. Tout est automatique.
   - **Copier-coller** : *Copier le prompt*, colle-le dans Claude (ou une autre IA), copie toute la réponse, puis *Coller* et *Importer le plan*.
3. La réponse se termine par un bloc JSON. L'app en tire :
   - les **séances**, ajoutées dans Programmes et regroupées par semaine et par jour ;
   - les **conseils** : entraînement, nutrition, récupération, progression et signaux d'alerte, affichés dans l'onglet Coach.

Un nouveau plan remplace les séances du plan précédent. Tes séances perso ne sont pas touchées.

## Renfo et douleurs (IA)

Dans l'onglet Coach, le bouton **Renfo & douleurs** ouvre la section dédiée :

1. **Douleurs et matériel** : zones (sciatique, lombaires, genoux, tendon d'Achille…), intensité de 0 à 10, détails, matériel disponible, séances par semaine, durée et objectifs.
2. **Générer** (API Claude ou copier-coller). Le prompt reprend tes douleurs, tes séances cardio, Strava et **l'évolution de ta douleur après chaque séance de renfo**. L'IA renvoie :
   - les règles de sécurité et les **signaux d'alerte** (pour une sciatique : perte de force, engourdissement du périnée, troubles urinaires…) ;
   - les habitudes quotidiennes ;
   - les séances détaillées : séries, répétitions ou durée, repos, consignes, versions plus facile et plus dure, cas où éviter l'exercice.
3. **Lecteur guidé** : chrono pour le gainage et les étirements, bouton *Fait* pour les répétitions, repos minutés, côtés gauche et droite, annonces vocales.
4. **Suivi de la douleur** : avant et après chaque séance. Le prochain programme retire ou adapte les exercices qui l'ont aggravée.

Ce ne sont que des suggestions, pas un avis médical : en cas de douleur forte ou qui s'aggrave, consulte.

## Strava

Dans l'onglet **Coach**, carte **Strava** : l'app récupère tes activités des 90 derniers jours (course, vélo, marche…) et ton poids. Le prompt du coach en tient compte : volume hebdomadaire par sport, cardio, et dernières séances.

Configuration, à faire une seule fois :

1. Sur https://www.strava.com/settings/api, crée une application :
   - **Nom** : MAD Personal Trainer
   - **Catégorie** : Training
   - **Site web** : http://localhost
   - **Authorization Callback Domain** : `localhost`
2. Dans l'app, ouvre *Configurer*, colle le **Client ID** et le **Client Secret**, puis appuie sur *Se connecter avec Strava*. Autorise l'accès aux activités.
3. *Synchroniser* rafraîchit les données. L'interrupteur *Inclure dans le prompt* permet d'exclure Strava du prompt.

## Cardio : montre ou ceinture

L'onglet **Vélo** comporte une carte **Cardio (montre ou ceinture)**. Elle accepte tout appareil cardio Bluetooth standard : ceinture, ou montre en mode diffusion.

**Amazfit Balance** : sur la montre, ouvre *Réglages > Fréquence cardiaque > Diffusion de la fréquence cardiaque* et active-la. Tu peux aussi l'activer depuis une séance d'entraînement. Ensuite, dans l'app, appuie sur *Rechercher* puis choisis la montre. Elle est mémorisée et l'app s'y reconnecte automatiquement au démarrage.

Le cardio de la montre est prioritaire sur celui de la console. Il est enregistré dans l'historique et envoyé à l'écran de la console Domyos.

## Envoyer le journal Bluetooth

Onglet **Vélo**, puis **Envoyer** à côté de « Journal Bluetooth », puis choisis **Drive** (compte Scalizer). Le fichier `mad-trainer-log-AAAAMMJJ-HHMMSS.txt` contient les services GATT de la console, les événements et toutes les trames brutes reçues et envoyées. C'est ce qu'il faut pour diagnostiquer le protocole.

## Premier lancement

1. Pédale quelques secondes pour réveiller la console, puis vérifie que le Bluetooth est actif et que **l'app Domyos / eConnected / Kinomap n'est pas connectée** au vélo (une seule connexion à la fois).
2. Onglet **Vélo** > *Rechercher*, puis choisis l'appareil (badge **FTMS** si la console l'annonce).
3. Sur Android 8 à 11, la **localisation** du téléphone doit être activée pour que le scan BLE fonctionne.
4. Onglet **Séance** : lance une séance libre ou un programme.

### Si la résistance ne réagit pas

- Si le journal indique *« paramètre invalide »*, active **Résistance sur 2 octets** dans les réglages.
- Si le niveau affiché est 10× trop grand ou trop petit, change l'**échelle** (×1 ou ×10).
- Si la console répond *« non supporté »* pour la puissance, c'est que le mode ERG n'est pas géré par ta console : utilise les séances en résistance.

### Consoles Domyos sans FTMS (ex. `Domyos-EL-xxxx`)

L'app détecte le protocole Domyos propriétaire (service `49535343-fe7d-…`) et le gère :

- initialisation de la console et maintien de la connexion toutes les 300 ms ;
- lecture de la vitesse, de la cadence, de la résistance, des calories et du cardio ;
- résistance de 1 à 15 ;
- **puissance estimée**, car la console ne la transmet pas. Elle est calculée à partir de la résistance et de la cadence ;
- **mode ERG simulé** : l'app ajuste la résistance en continu pour viser la puissance cible.

Les trames du protocole sont documentées d'après le projet open source [qdomyos-zwift](https://github.com/cagnulein/qdomyos-zwift).

## Architecture

```
app/src/main/java/com/mathieu/madtrainer/
├── ble/Ftms.kt          # UUIDs, parsing des trames FTMS, encodage des commandes (Kotlin pur, testé)
├── ble/BikeManager.kt   # Scan, GATT, file d'opérations, notifications, commandes
├── data/                # Modèles, moteur de séance, stockage JSON, réglages
├── AppViewModel.kt      # Séance en cours (tick 1 s), application des cibles, enregistrement
└── ui/                  # Écrans Compose : Vélo, Séance, Programmes, Historique
```

Tests unitaires : `./gradlew testDebugUnitTest`.
