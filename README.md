# release-test-lutece-platform

Dépôt de **test** du plugin releaser : une copie du monorepo `lutece-platform` (même structure, même
`Jenkinsfile-release`, même `release-helpers.groovy`) dont les starters n'agrègent que des composants de test.
Il sert de bac à sable à la feature « release de la plateforme Lutèce » : une campagne complète peut être
jouée dessus, releases réelles des starters de test comprises, sans toucher à la vraie plateforme.

Dépôt : `https://github.com/lutece-platform/release-test-lutece-platform`

## Modules

| Module réel (lutece-platform) | Module de test | Contenu |
|---|---|---|
| `lutece-bom` | `release-test-lutece-bom` | BOM : le vrai `lutece-core` (stable) + les sept composants de test |
| `forms-starter` | `release-test-forms-starter` | composants de test GitHub |
| `appointment-starter` | `release-test-appointment-starter` | composants de test GitHub (plugin + module) |
| `editorial-starter` | `release-test-editorial-starter` | composants de test GitLab |
| `lutece-starter` | `release-test-lutece-starter` | agrège les trois starters de test |

groupId `fr.paris.lutece.starters` (celui des vrais starters, les artifactIds préfixés `release-test-` évitent
toute collision), POM racine `release-test-lutece-parent`, parent `lutece-global-pom` en version stable (voir le
tableau des branches ci-dessous).

## Composants de test

| artifactId | Dépôt | Starter |
|---|---|---|
| `plugin-releaser-test` | github.com/lutece-platform/lutece-build-plugin-releaser-test | forms |
| `releaser-test-redmine-component` | github.com/lutece-platform/lutece-build-releaser-test-redmine-component | forms |
| `releaser-test-redmine-component-buttom` | github.com/lutece-platform/lutece-build-releaser-test-redmine-component-buttom | appointment |
| `module-testrelease` | github.com/lutece-platform/lutece-build-module-testrelease | appointment |
| `plugin-test-release-v7-v8` | dev.lutece.paris.fr/gitlab/bild/u06-tests/plugin-test-releaser-v7-v8 | editorial |
| `releaser-test-redmine-component-gitlab` | dev.lutece.paris.fr/gitlab/bild/u06-tests/releaser-test-redmine-component-gitlab | editorial |
| `plugin-testreleasegitlab` | dev.lutece.paris.fr/gitlab/bild/u06/plugin-releaser-test-gitlab | editorial |

Les properties `lutece.<artifactId>.version` du POM racine portent les snapshots courants de chaque composant au
16/09/2026 (branche `develop`, ou `develop_core7` pour `plugin-test-release-v7-v8` sur la ligne 7). Elles évoluent
à chaque release de test. `lutece.core.version` référence le vrai core et exerce l'alias `core` → `lutece-core`
du releaser.

## Branches

| Branche | Ligne | Parent global-pom | Versions des modules | Core |
|---|---|---|---|---|
| `develop` | Lutece 8 | 8.0.1 | 8.0.0-SNAPSHOT | 8.0.1 |
| `develop_core7` | Lutece 7 | 7.0.8 | 7.2.0-SNAPSHOT | 7.1.9 |

Seul `plugin-test-release-v7-v8` possède une branche `develop_core7` ; les autres composants de test n'ont qu'une
branche `develop`, que le releaser retient par repli sur la ligne 7 (`develop_core7`, puis `develop7.x`, puis `develop`).

## Pipeline

`Jenkinsfile-release` et `release-helpers.groovy` sont ceux de lutece-platform, avec les cinq noms de modules
remplacés par les noms de test et l'URL de push pointée sur ce dépôt. Le job Jenkins qui l'exécute doit
correspondre à la property `releaser.platform.jenkins.platformJob` du releaser de test.

## Brancher le releaser dessus

Sur une base locale ou de recette, jamais en production :

```sql
UPDATE releaser_platform_step_definition
SET scm_url = 'https://github.com/lutece-platform/release-test-lutece-platform.git'
WHERE step_number IN (4, 5);
```
