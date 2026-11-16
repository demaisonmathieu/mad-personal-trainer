# MAD Personal Trainer — contexte pour Claude Code

App Android perso (Kotlin + Jetpack Compose) de Mathieu : pilotage d'un vélo elliptique **Domyos EL500** en Bluetooth, course à pied guidée (GPS + voix), coach IA, renfo adapté aux douleurs, Strava, montre Amazfit Balance comme cardio.
Dépôt public : `demaisonmathieu/mad-personal-trainer` (push en SSH). Package `com.mathieu.madtrainer`. Réponds en français.

## Build et livraison
- Kotlin 2.0.21, Compose BOM 2024.12.01, Material3, AGP 8.7.3, Gradle 8.11.1, minSdk 26, target/compileSdk 35, kotlinx.serialization 1.7.3.
- **La CI GitHub Actions** (`.github/workflows/build.yml`) lance `testDebugUnitTest` + `assembleRelease`, puis publie une release `v1.0.<run_number>` avec `MADPersonalTrainer.apk`. L'app a un updater intégré (`update/Updater.kt`) qui lit ces releases.
- Signature : `release.jks`, mot de passe `madtrainer`, alias `mad`. `versionCode` = `GITHUB_RUN_NUMBER`.
- Après chaque changement : `./gradlew testDebugUnitTest` si le SDK Android est dispo localement, sinon pousser et vérifier l'onglet Actions.

## Architecture (`app/src/main/java/com/mathieu/madtrainer/`)
- `ble/` : `Ftms.kt` (FTMS 0x2ACE/0x2AD2, point de contrôle 0x2AD9), `DomyosLegacy.kt`, `BikeManager.kt` (FTMS + legacy, reconnexion bornée 15 s × 4), `HeartRateManager.kt` (0x180D, reconnexion **par nom** car l'adresse Amazfit change).
- `data/` : `Models.kt` (Sport, StepMode FREE/RESISTANCE/POWER/PACE/HR_ZONE, WorkoutStep, Workout, Sample avec `step`, SessionRecord avec `steps` et `aiReport`), `WorkoutEngine`, `RunEngine`, `WorkoutConverter` (conversion entre sports via zones, `zoneFor`, `resistanceForZone`), `WeekPlanner`, `HrZones` (zones % FC max ou importées de Strava), `VoiceCoach` (annonces, préavis 30 s, alarme cardio), `SessionReport` (rapport de progression + prompt IA), `Repository`, `SettingsStore` (dont `voice`, `hrAlerts`).
- `coach/` : `CoachPrompt`/`CoachParser` (plan commun course + elliptique), `JsonRepair` (réponses IA coupées), `StrengthPrompt`/`StrengthModels`/`StrengthPlayer` (renfo, `image_id`), `ExerciseCatalog` (banque free-exercise-db dans `assets/exercises.json`, correspondances FR → id), `AnthropicClient` (API Messages, reprise si `max_tokens`). Sans IA : `RulePlanner` (plan course + elliptique par règles : volume calé sur l'historique app + Strava, +10 %/semaine, allègement toutes les 4 semaines, course limitée si douleur ou IMC ≥ 30, allures tirées des sorties récentes ; produit un `CoachPlan` importé comme celui de l'IA) et `NutritionPlanner` (métabolisme balance > masse maigre > Mifflin, déficit, macros, repas types, affiché dans l'écran Corps).
- `strava/` : OAuth `madtrainer://localhost/strava`, activités 90 j, poids, zones cardio (`/athlete/zones`).
- `health/` : `HealthConnectSource` (lecture Health Connect : poids et composition de la balance Starfit, FC de repos Zepp ; `connect-client` 1.1.0-beta01 car la 1.1.0 stable exige compileSdk 36 / AGP 8.9). Calculs dans `data/BodyMetrics.kt` (`BodyStats` : regroupement en pesées, tendances, résumé pour les prompts IA) ; la dernière pesée met à jour le profil.
- `run/` : `RunTracker` (GPS + FUSED, TTS fr, vibrations, guidage allure/zone, préavis, alarme cardio), `RunService` (service de premier plan, type location).
- `ui/` : onglets Semaine (défaut, glisser-déposer des séances, décalage du plan, édition), Vélo/Capteurs, Séance/Course, Coach, Programmes ; Historique et Corps (boutons en haut, `BodyScreen`) ; `ReportCard` (rapport + analyse IA), `ExerciseMedia` (images animées + YouTube), `Reorder` (drag & drop maison), `StrengthSessionEditor`.

## Protocole Domyos legacy (EL500)
Service `49535343-fe7d-…`, écriture `…8841…`, notif `…1e4d…`. Séquence d'init, noOp `F0 AC 9C` toutes les 300 ms. Trame d'état 26 octets : vitesse o6-7 /10, cadence o9, kcal o10-11, résistance o14, cardio o18. Résistance : trame `F0 AD`, octet 10 = niveau, **octet 17 = 0xFF**, checksum octet 22, envoyée en 20 + 3 octets **sans pause entre les deux** ; la console confirme par un écho `F0 BD`.

## Pièges connus
- Compose : plusieurs enfants dans un `item {}` de LazyColumn se superposent → les envelopper dans une Column. Contenu d'un SegmentedButton : mettre icône + texte dans une Row.
- Éviter les `return@Column` / retours étiquetés dans les lambdas composables : utiliser if/else.
- Regex Android (ICU) : `}` non échappé = erreur → éviter les regex pour extraire le JSON.
- kotlinx : `String.serializer()` (pas `serializer<String>()`), pas d'API expérimentales (`allowTrailingComma`).

## Tests unitaires (app/src/test)
FtmsParserTest (+ DomyosLegacy), CoachTest (+ Strava, parseurs), RunEngineTest, StrengthTest, ConverterTest, WeekPlannerTest, HrZonesTest, ReportVoiceTest, BodyMetricsTest, RulePlannerTest (+ nutrition), coach/JsonRepairTest, coach/ExerciseCatalogTest.

## État au 16/11/2026
Dernières fonctions ajoutées, **compilées et testées en CI (release v1.0.24), pas encore essayées sur l'appareil** : glisser-déposer « Ma semaine », images/vidéos renfo, zones cardio Strava, coach vocal (préavis 30 s, alerte cardio trop élevé, annonces sur l'elliptique), rapport de progression avec analyse IA.
Problème ouvert : la montre Amazfit Balance n'est pas toujours découverte (il faut activer la diffusion du cardio sur la montre).
