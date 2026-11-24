# Sillage — consignes pour les agents

App Android compagnon de singletrack (nom de code interne : `singletrackCompanion`). Elle enregistre
une sortie VTT sans réseau, pose des moments clés en un tap, exporte en GPX/KML et, en option, parle à
un serveur singletrack (sorties, compte associé, envoi de la trace, cartes hors ligne).

Développée sur le poste de Clément (pas d'agentRunner). Le board Kanboard sert de spec et de suivi.

## Principes non négociables

- **Rien ne dépend du réseau pour enregistrer.** L'enregistrement, les moments clés, l'export et
  l'import marchent en mode avion, sans compte. singletrack est optionnel.
- **F-Droid, pas de Play Store** : aucune dépendance propriétaire. Pas de Google Play Services
  (localisation par `LocationManager`), pas de ML Kit (QR par ZXing), pas de Firebase/FCM. Avant
  d'ajouter une dépendance, vérifier sa licence et qu'elle ne tire rien de propriétaire.
- **AGPL v3**, dépôt public. Aucun secret, aucune adresse privée dans le dépôt.
- `applicationId` = `io.github.clement83.sillage`, **à ne jamais changer** (F-Droid le traiterait
  comme une nouvelle app).
- URL du serveur singletrack configurable (défaut `https://singletrack.quintard.me`), jamais codée en dur
  ailleurs que dans `ServerUrl.DEFAULT`.
- OpenTopoMap en affichage seulement : **jamais de préchargement en masse** (conditions d'usage).

## Stack

- Kotlin 2.2, Jetpack Compose (Material 3), navigation-compose, AGP 8.13, Gradle 8.14 (wrapper).
- minSdk 26, compile/targetSdk 36. Versions dans `gradle/libs.versions.toml`.
- Carte : MapLibre Native Android (`org.maplibre.gl:android-sdk`).
- Persistance : Room (enregistrements, points, moments clés), DataStore (réglages).
- Injection manuelle : `AppContainer`, créé par `SillageApp`.
- `MainActivity` hérite d'`AppCompatActivity` pour la langue par app sous Android 13.

## Organisation du code (`app/src/main/java/io/github/clement83/sillage/`)

- `settings/` réglages (DataStore) et validation d'URL.
- `map/` intégration MapLibre (cycle de vie de la `MapView`, styles).
- `ui/` écrans Compose, un dossier par écran ; `ui/theme/` couleurs.
- La logique pure (calculs géo, filtrage, GPX/KML) va dans des fonctions sans dépendance Android,
  couvertes par des tests unitaires JVM (`app/src/test`).

## Commandes

```sh
./gradlew assembleDebug testDebugUnitTest   # build + tests unitaires
./gradlew lintDebug                         # lint (joué par la CI)
./gradlew installDebug                      # installer sur le téléphone branché (adb)
```

Le SDK Android est dans `~/Android/Sdk` (`local.properties`, non versionné). Android Studio n'est pas
nécessaire.

## Textes et langue

- Tous les textes visibles passent par `strings.xml` : anglais dans `values/`, français dans
  `values-fr/`. Toute nouvelle chaîne existe dans les deux.
- Le français tutoie l'utilisateur.
- README, commentaires de code et messages de commit en anglais.

## Appareil de test

Téléphone de Clément : **Unihertz Jelly 2**, Android 11 (API 30), écran 480×854 à 220 dpi (environ
349×621 dp). Chaque écran doit rester utilisable à cette taille : pas de contenu coupé, boutons d'action
d'au moins 48 dp (64 dp pour le moment clé, utilisable avec des gants).

## Kanboard — suivi

- **Projet Kanboard** : sillage (`project_id: 56`). Côté serveur : singletrack (`project_id: 54`).
- Colonnes : BACKLOG → TODO → WIP → HUMAN REVIEW → DONE. Prendre un ticket = le passer en WIP ;
  terminé côté code = HUMAN REVIEW, avec un commentaire qui dit ce qui est fait, ce qui reste à vérifier
  sur le téléphone, et tout écart à la spec.
- Ordre : Phase 1 autonome (sans singletrack) d'abord, puis Phase 2 (voir l'epic #1732).
- Une décision prise en commentaire doit aussi être reportée dans la description du ticket.
