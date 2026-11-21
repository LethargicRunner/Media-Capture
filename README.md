# Media Capture

Application Android native Kotlin de navigation et de capture multimédia.

## Version

V0.1.0 — Base navigateur et détection

## Fonctions actuelles

- Navigateur WebView intégré
- Navigation par adresse URL
- Détection de requêtes multimédias
- Liste des médias détectés
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

## Limites de la V0.1.0

La détection actuelle repose sur les extensions de fichiers dans les URL.
Elle ne détecte pas encore tous les flux adaptatifs, les médias sans extension
ou les contenus protégés par DRM.

Aucune capture de média n'est encore implémentée.
