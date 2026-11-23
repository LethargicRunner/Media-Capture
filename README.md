# Media Capture

Application Android native Kotlin de navigation et de capture multimédia.

## Version

V0.1.0 — Base navigateur et détection

## Fonctions actuelles

- Navigateur WebView intégré
- Navigation par adresse URL
- Détection de requêtes multimédias
- Liste des médias détectés
- Interface légère : barre d'adresse fine et bouton flottant déplaçable ; un tap ouvre un panneau (capture, qualité MP3, stéréo/mono, navigation, médias détectés)
- Capture du son système Android, encodée en MP3 stéréo 192 kb/s pendant l'enregistrement (LAME), fichier enregistré dans Téléchargements par défaut, ou dans le dossier choisi dans le panneau
- Navigation arrière, avant et actualisation

## Fonctions prévues

- Capture du flux multimédia original
- Capture de sortie audio Android
- Choix du mode de capture
- Gestion des fichiers capturés
- Historique et journal des erreurs

## Environnement

- Android
- Kotlin
- Android Gradle Plugin
- Compilation via GitHub Actions

## Limites actuelles

La détection actuelle repose sur les extensions de fichiers dans les URL.
Elle ne détecte pas encore tous les flux adaptatifs, les médias sans extension
ou les contenus protégés par DRM.

La capture du flux multimédia original n'est pas encore implémentée : seule la capture du son système (en temps réel) est disponible. Android 10 minimum requis.

L'encodeur MP3 utilise LAME 3.100 (LGPL 2.1), dans `app/src/main/cpp/lame`.
