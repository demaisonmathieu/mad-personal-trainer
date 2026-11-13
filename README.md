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

- **GitHub Actions** : chaque push sur `main` compile l'APK. Va dans l'onglet *Actions*, ouvre le dernier run, puis télécharge l'artefact `MADPersonalTrainer-apk` (un zip qui contient l'APK).
- **Android Studio** : ouvre le dossier du projet, puis *Run* (JDK 17, SDK 35).

Pour l'installer, autorise « Installer des applis inconnues » pour ton navigateur ou ton gestionnaire de fichiers.

> L'APK est signé avec une clé de debug générée à chaque compilation CI. Si l'installation d'une nouvelle version échoue, désinstalle d'abord l'ancienne (tu perdras l'historique local).

## Premier lancement

1. Pédale quelques secondes pour réveiller la console, puis vérifie que le Bluetooth est actif et que **l'app Domyos / eConnected / Kinomap n'est pas connectée** au vélo (une seule connexion à la fois).
2. Onglet **Vélo** > *Rechercher*, puis choisis l'appareil (badge **FTMS** si la console l'annonce).
3. Sur Android 8 à 11, la **localisation** du téléphone doit être activée pour que le scan BLE fonctionne.
4. Onglet **Séance** : lance une séance libre ou un programme.

### Si la résistance ne réagit pas

- Si le journal indique *« paramètre invalide »*, active **Résistance sur 2 octets** dans les réglages.
- Si le niveau affiché est 10× trop grand ou trop petit, change l'**échelle** (×1 ou ×10).
- Si la console répond *« non supporté »* pour la puissance, c'est que le mode ERG n'est pas géré par ta console : utilise les séances en résistance.
- Si le journal indique *« protocole Domyos propriétaire »*, ta console est d'une ancienne génération, sans FTMS. Ce protocole n'est pas encore implémenté. Envoie-moi le journal Bluetooth pour qu'on l'ajoute.

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
