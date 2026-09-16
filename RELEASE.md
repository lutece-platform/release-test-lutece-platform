# CLAUDE.md — Projet Lutece Multi-Module

## 🏗️ Vue d'ensemble du projet

Ce projet est un **monorepo Maven multi-module** pour la plateforme **Lutece**, un framework open-source d'applications web inspiré de l'architecture Jakarta EE. Il contient des **Starters** (configurations prêtes à l'emploi) et un **BOM** (Bill of Materials) pour démarrer rapidement des applications Lutece.

**Dépôt principal :** `https://github.com/lutece-platform/lutece`

## 📦 Structure des modules

```
lutece/                          # Racine du projet
├── pom.xml                      # POM Parent — fixe les versions de TOUS les plugins Lutece
├── lutece-bom/                  # BOM — centralise les versions des dépendances
│   └── pom.xml
├── forms-starter/               # Starter pour les formulaires
│   └── pom.xml
├── appointment-starter/         # Starter pour la gestion de rendez-vous
│   └── pom.xml
├── editorial-starter/          # Starter pour le contenu éditorial
│   └── pom.xml
└── lutece-starter/              # Starter complet (tous les composants Lutece)
    └── pom.xml
```

### Hiérarchie Maven

- **POM Parent** (`lutece/pom.xml`) : définit les versions de tous les plugins/composants Lutece et les versions individuelles de chaque module
- **BOM** (`lutece-bom`) : centralise les versions des dépendances, utilisé par les consommateurs externes (PAS par les starters, pour garder de la souplesse)
- **Starters spécialisés** (`forms-starter`, `appointment-starter`, `editorial-starter`) : héritent du POM parent, chacun a sa propre version pilotée par une property dédiée et agrège un sous-ensemble de plugins Lutece
- **lutece-starter** : le starter complet, il **dépend des 3 autres starters** + des plugins Lutece supplémentaires qui ne sont dans aucun des starters spécialisés

### Gestion des versions — Découplage par module

Chaque module (starter/BOM) a sa propre property de version dans le POM parent, permettant des **releases individuelles** :

```xml
<properties>
    <!-- Chaque module a sa propre version (independante des autres) -->
    <lutece.forms-starter.version>8.0.0-SNAPSHOT</lutece.forms-starter.version>
    <lutece.appointment-starter.version>8.0.0-SNAPSHOT</lutece.appointment-starter.version>
    <lutece.editorial-starter.version>8.0.0-SNAPSHOT</lutece.editorial-starter.version>
    <lutece.lutece-starter.version>8.0.0-SNAPSHOT</lutece.lutece-starter.version>
    <lutece.lutece-bom.version>8.0.0-SNAPSHOT</lutece.lutece-bom.version>

    <!-- 90+ versions de plugins -->
    <lutece.core.version>8.0.0</lutece.core.version>
    <!-- ... -->
</properties>
```

Chaque module enfant déclare sa version via la property parent :

```xml
<!-- forms-starter/pom.xml -->
<artifactId>forms-starter</artifactId>
<version>${lutece.forms-starter.version}</version>
```

Le **flatten-maven-plugin** (configuré en `flattenMode=clean`) résout la property en valeur concrète dans le POM déployé sur Nexus. Le POM publié est donc autonome.

Cette property sert a la fois pour :
- La version propre du module (`<version>${lutece.forms-starter.version}</version>`)
- La dépendance dans lutece-starter (`<dependency>...<version>${lutece.forms-starter.version}</version>`)
- La version managée dans lutece-bom (`<dependencyManagement>`)

Un seul `sed` sur cette property met tout a jour.

## 🔗 Organisations GitHub

Les plugins Lutece sont répartis sur **deux organisations GitHub** :

| Organisation | URL | Contenu |
|---|---|---|
| **lutece-platform** | `https://github.com/lutece-platform` | Core, plugins principaux, ce dépôt |
| **lutece-secteur-public** | `https://github.com/lutece-secteur-public` | Plugins spécifiques secteur public |

## 🌿 Stratégie de branches

- **Branche de développement V8** : `develop` (parent `lutece-global-pom` 8.0.x)
- **Branche de développement V7** : `develop_core7` (parent `lutece-global-pom` 7.0.x)
- **Branche de production** : `master` — reçoit le merge lors d'une release `all` stable
- Les releases sont préparées sur la branche de développement de la ligne, puis mergées sur `master` lors d'une release `all` stable
- Les releases individuelles (un seul module) **ne mergent pas sur master** (les autres modules peuvent encore etre en SNAPSHOT)
- Les pré-releases (beta / RC) **ne mergent jamais sur master**
- Convention de tags du monorepo :
  - cible `all` → `v{version}` (ex: `v8.0.0`, `v8.0.0-beta-01`)
  - cible d'un module → `{module}-{version}` (ex: `forms-starter-8.0.1`)
  - plugins (release manuelle) → `{artifactId}-{version}` (ex: `lutece-core-8.0.0`)

## ☕ Stack technique

- **Java** : 17 pour la ligne V8, 11 pour la ligne V7 (property `targetJdk` de `lutece-global-pom`)
- **Build** : Maven 3.x
- **CI/CD** : Jenkins (Pipeline déclaratif)
- **SCM** : Git / GitHub

## 🚀 Workflow de release

### Graphe de dépendances

```
plugins Lutece (lutece-platform + lutece-secteur-public)
   │
   ├──► forms-starter          (sous-ensemble de plugins)
   ├──► appointment-starter    (sous-ensemble de plugins)
   ├──► editorial-starter     (sous-ensemble de plugins)
   │
   └──► lutece-starter
           ├── dépend de forms-starter
           ├── dépend de appointment-starter
           ├── dépend de editorial-starter
           └── dépend de plugins Lutece supplémentaires
                (non inclus dans les 3 starters ci-dessus)
```

### Principe fondamental

> **Les composants (plugins) d'un starter doivent être releasés AVANT le starter lui-même.**
> **Le lutece-starter ne peut être releasé qu'APRÈS les 3 autres starters.**

### Ordre de release

```
1. Releaser les plugins Lutece individuels concernés
   (sur lutece-platform et/ou lutece-secteur-public)
   ↓
2. Mettre à jour le POM parent avec les versions release des plugins
   ↓
3. Releaser le(s) starter(s) spécialisé(s) : forms / appointment / editorial
   (indépendamment les uns des autres)
   ↓
4. Releaser le lutece-starter (qui dépend des 3 starters + plugins supplémentaires)
   ↓
5. Releaser le BOM (lutece-bom) — reflète les versions finales de tout
```

### Scénarios de release

> **Périmètre :** la pipeline `Jenkinsfile-release` ne release que le monorepo
> (starters + BOM). Les plugins sont releasés à la main **avant**, et leurs
> versions mises à jour dans le POM parent — la pipeline marque le build
> `UNSTABLE` si une property `lutece.*.version` est encore en SNAPSHOT.
> Voir `RELEASE_PLAYBOOK.md` pour le détail de la pipeline.

**Release individuelle d'un starter (ex: `RELEASE_TARGET = forms-starter`) :**
1. Lit `<lutece.forms-starter.version>` → `8.0.0-SNAPSHOT`
2. Vérifie qu'aucun plugin référencé n'est en SNAPSHOT (prérequis manuel)
3. Met a jour **uniquement** `<lutece.forms-starter.version>` dans le POM parent → `8.0.0`
4. Tag `forms-starter-8.0.0`, deploy sur Nexus (pas de merge sur master)
5. Restaure `<lutece.forms-starter.version>` → `8.0.1-SNAPSHOT`
6. Les autres modules restent inchangés

**Release complète (`RELEASE_TARGET = all`) :**
1. Lit la version de chaque module individuellement (chaque module peut avoir sa propre version)
2. Vérifie qu'aucun plugin n'est en SNAPSHOT (prérequis manuel)
3. Met a jour la version du parent + chaque property de module (avec sa propre version) + parent versions enfants
4. Release les 3 starters specialises en parallele (chacun avec sa propre version)
5. Release `lutece-starter` (avec sa propre version)
6. Release `lutece-bom` (avec sa propre version)
7. Tag `v8.0.0`, puis merge develop sur master (release stable uniquement)
8. Restaure chaque module en SNAPSHOT (patch+1 de sa propre version)

### Détail du processus de release d'un plugin Lutece

**Procédure manuelle** (hors pipeline), à exécuter pour chaque plugin en
SNAPSHOT dont la version doit être releasée avant celle des starters :

```bash
# 1. Lancer les tests avant la release (sur develop)
#    UNIQUEMENT si le packaging est lutece-plugin ou lutece-core
#    Vérifier dans le pom.xml : <packaging>lutece-plugin</packaging> ou <packaging>lutece-core</packaging>
mvn lutece:exploded antrun:run -Dlutece-test-hsql test

# 2. Passer en version release
mvn versions:set -DnewVersion=X.Y.Z

# 2b. Mettre à jour le tag <version> du descripteur XML du plugin
#     Le fichier webapp/WEB-INF/plugins/*.xml contient un tag <version>
#     qui doit être synchronisé avec la version du pom.xml
for xmlFile in webapp/WEB-INF/plugins/*.xml; do
    [ -f "$xmlFile" ] || continue
    sed -i 's|<version>[^<]*</version>|<version>X.Y.Z</version>|' "$xmlFile"
done

# 3. Commit + tag
git add -A
git commit -m "release: {artifactId}-X.Y.Z"
git tag -fa {artifactId}-X.Y.Z -m "Release {artifactId} X.Y.Z"

# 4. Prepare (push branch + tag)
git push origin develop --tags

# 5. Perform (deploy sur Nexus — depuis develop)
mvn clean deploy -DskipTests -DperformRelease=true

# 6. Promote (merge sur master — seulement apres succes du deploy)
git checkout master
git merge develop -m "Merge develop for release {artifactId}-X.Y.Z"
git push origin master

# 7. Revenir sur develop et repasser en SNAPSHOT
git checkout develop
mvn versions:set -DnewVersion=X.Y.(Z+1)-SNAPSHOT
# Mettre à jour aussi le descripteur XML du plugin
for xmlFile in webapp/WEB-INF/plugins/*.xml; do
    [ -f "$xmlFile" ] || continue
    sed -i 's|<version>[^<]*</version>|<version>X.Y.(Z+1)-SNAPSHOT</version>|' "$xmlFile"
done
git add -A
git commit -m "chore: prepare next development iteration {artifactId}-X.Y.(Z+1)-SNAPSHOT"

# 8. Push develop
git push origin develop
```

### Détail du processus de release d'un starter

Les starters **n'ont pas de tests de vérification**, le processus est plus simple :

```bash
# 1. Passer en version release (sur develop)
mvn versions:set -DnewVersion=X.Y.Z

# 2. Commit + tag
git add -A
git commit -m "release: {artifactId}-X.Y.Z"
git tag -a {artifactId}-X.Y.Z -m "Release {artifactId} X.Y.Z"

# 3. Merger sur master
git checkout master
git merge develop
git push origin master

# 4. Deploy (depuis master)
mvn clean deploy -P release

# 5. Revenir sur develop et repasser en SNAPSHOT
git checkout develop
mvn versions:set -DnewVersion=X.Y.(Z+1)-SNAPSHOT
git add -A
git commit -m "chore: prepare next development iteration {artifactId}-X.Y.(Z+1)-SNAPSHOT"

# 6. Push develop + tags
git push origin develop --tags
```

### Release d'un starter

```bash
# 1. S'assurer que TOUS les composants du starter sont en version release (non-SNAPSHOT)
# 2. Mettre à jour le pom parent avec les versions release
# 3. Suivre le même processus de release que ci-dessus pour le starter
```

### Release Candidates (RC)

Les Release Candidates permettent de publier une version pré-release pour validation
avant la release stable. Le mode RC est **optionnel**.

**Différences entre RC et release stable :**

| Aspect | Release stable | Release Candidate |
|--------|---------------|-------------------|
| Version | `X.Y.Z` | `X.Y.Z-RC-NN` (zero-padded) |
| Tag | `{artifactId}-X.Y.Z` | `{artifactId}-X.Y.Z-RC-NN` |
| Merge sur master | Oui | **Non** |
| Deploy depuis | `master` | `develop` |
| Après deploy | Version → `X.Y.(Z+1)-SNAPSHOT` | Version → `X.Y.Z-SNAPSHOT` (restauration) |

**Cycle de vie typique avec RC :**

```
develop
   │
   ├── RC-01: X.Y.Z-SNAPSHOT → X.Y.Z-RC-01 → deploy → X.Y.Z-SNAPSHOT
   │          (corrections...)
   ├── RC-02: X.Y.Z-SNAPSHOT → X.Y.Z-RC-02 → deploy → X.Y.Z-SNAPSHOT
   │        (validation OK)
   └── Stable: X.Y.Z-SNAPSHOT → X.Y.Z → merge master → deploy → X.Y.(Z+1)-SNAPSHOT
```

**Processus de release RC d'un plugin :**

```bash
# 1. Tests (identique à la release stable)
mvn lutece:exploded antrun:run -Dlutece-test-hsql test

# 2. Passer en version RC
mvn versions:set -DnewVersion=X.Y.Z-RC-NN
for xmlFile in webapp/WEB-INF/plugins/*.xml; do
    [ -f "$xmlFile" ] || continue
    sed -i 's|<version>[^<]*</version>|<version>X.Y.Z-RC-NN</version>|' "$xmlFile"
done

# 3. Commit + tag RC
git add -A
git commit -m "release: {artifactId}-X.Y.Z-RC-NN"
git tag -fa {artifactId}-X.Y.Z-RC-NN -m "Release Candidate {artifactId} X.Y.Z-RC-NN"

# 4. Deploy depuis develop (PAS de merge sur master)
mvn clean deploy -P release

# 5. Restaurer la version SNAPSHOT d'origine (pas d'incrément)
mvn versions:set -DnewVersion=X.Y.Z-SNAPSHOT
for xmlFile in webapp/WEB-INF/plugins/*.xml; do
    [ -f "$xmlFile" ] || continue
    sed -i 's|<version>[^<]*</version>|<version>X.Y.Z-SNAPSHOT</version>|' "$xmlFile"
done
git add -A
git commit -m "chore: restore SNAPSHOT after RC {artifactId}-X.Y.Z-RC-NN"
git push origin develop --tags
```

### Validation SNAPSHOT (gate de sécurité)

Avant de releaser les starters, la pipeline verifie qu'**aucune dependance
SNAPSHOT ne subsiste** dans le `pom.xml` racine (hors properties structurelles
des modules). Si des violations sont detectees, le pipeline passe en
etat `UNSTABLE` et les starters ne seront pas deployes.

### Rollback automatique

Grace au pattern **prepare / perform / promote**, la branche `master`
n'est **jamais touchee avant le succes du deploy Nexus**. Le rollback
ne necessite donc **jamais** de `git reset --hard` ni de `git push --force`.

En cas d'echec lors de la release d'un plugin :

```bash
# 1. Revert du commit de release sur develop (pas de force push)
git checkout develop
git revert HEAD --no-edit
git push origin develop

# 2. Suppression du tag (local + remote)
git tag -d {artifactId}-X.Y.Z
git push origin :refs/tags/{artifactId}-X.Y.Z
```

> **Note :** master n'est pas concerne car le merge n'a lieu qu'apres
> le succes du deploy (phase promote).

Si le rollback echoue, une **intervention manuelle** est necessaire.
Les details figurent dans le rapport archive (`release-report.txt`).

## 🔗 Convention de nommage des dépôts GitHub

Le nom du dépôt GitHub suit le pattern `lutece-{catégorie}-{artifactId}`.
L'artifactId est le **suffixe** du nom du dépôt :

| artifactId (POM)              | Dépôt GitHub                              |
|-------------------------------|-------------------------------------------|
| `plugin-forms`                | `lutece-form-plugin-forms`                |
| `module-workflow-forms`       | `lutece-wf-module-workflow-forms`         |
| `library-lucene`              | `lutece-search-library-lucene`            |
| `lutece-core`                 | `lutece-core`                             |

La pipeline utilise l'**API Search GitHub** pour résoudre le dépôt :
```
GET /search/repositories?q=org:{org}+{artifactId}+in:name
→ filtre : repo.name == artifactId OU repo.name se termine par "-{artifactId}"
```

**Cas spéciaux (REPO_OVERRIDES) :** certains dépôts ne suivent pas la
convention standard :

| artifactId POM                       | Dépôt réel                              |
|--------------------------------------|-----------------------------------------|
| `plugin-modulenotifygrumappingmanager` | `lutece-secteur-public/gru-module-notifygru-mapping-manager` |
| `plugin-galleryimage`                | `lutece-platform/lutece-tech-plugin-image-gallery` |
| `library-sql-utils`                  | `lutece-platform/lutece-build-library-sqlutils` |

## 🏭 Pipeline Jenkins — Exigences

### Pipeline souhaitée

- **Release sélective** : pouvoir choisir quel(s) starter(s) releaser individuellement
- **Releases individuelles** : chaque module peut etre releasé indépendamment sans impacter les autres (versions découplées)
- **Résolution automatique des dépendances** : identifier et releaser d'abord les composants SNAPSHOT d'un starter
- **Multi-dépôt** : le pipeline doit pouvoir cloner et releaser des composants depuis les deux organisations GitHub
- **Paramétrable** : version de release, version SNAPSHOT suivante, dry-run mode
- **Release Candidates** : possibilité de créer des RC avant la release stable (optionnel)
- **Parallélisme par batch** : plugins releasés en batches parallèles de 5
- **Validation SNAPSHOT** : gate de sécurité avant release des starters
- **Rollback automatique** : revert des commits et suppression des tags en cas d'échec

### Variables d'environnement attendues

```groovy
GITHUB_TOKEN          // Token d'accès aux deux organisations
MAVEN_SETTINGS_XML    // Settings Maven avec credentials Nexus/registry
JAVA_HOME             // JDK 17
```

### Jenkinsfile — Conventions

- Pipeline **déclaratif** (pas scripted)
- Utiliser `mvn versions:set` (PAS le maven-release-plugin)
- `sed` pour les mises à jour en masse des properties POM (90+ properties)
- Stages clairement nommés en français ou anglais (cohérent)
- Gestion des erreurs avec rollback (revert des commits si le deploy échoue)
- `DRY_RUN = true` par défaut pour empêcher les releases accidentelles
- Notifications (Slack/email) en cas de succès ou échec

## 📋 Conventions de code

- **Commits** : format conventionnel — `type: description` (ex: `release: v3.2.0`, `chore: update dependencies`)
- **Tags** : format `{artifactId}-{version}` (ex: `lutece-core-8.0.0`, `forms-starter-8.0.1`)
- **Tags par release `all`** : 5 tags dans le monorepo (un par module) + un tag par plugin releasé
- **Versioning** : SemVer (MAJOR.MINOR.PATCH)
- **POM** : chaque module utilise `${lutece.<module>.version}` pour sa version, jamais de versions en dur

## ⚠️ Points d'attention

1. **Ne jamais releaser un starter si un de ses composants est encore en SNAPSHOT**
2. **Ne jamais releaser le lutece-starter si les 3 starters spécialisés sont encore en SNAPSHOT**
3. **Le POM parent doit être mis à jour avec les versions release avant la release des starters**
4. **Les starters NE référencent PAS le BOM** — chaque starter gère ses propres versions pour plus de souplesse
5. **Le BOM est releasé en dernier** — il reflète l'état final des versions après toutes les releases
6. **Certains plugins existent sur les deux organisations** — toujours vérifier le bon dépôt source
7. **Les tests doivent passer avant toute release de plugin** (`mvn lutece:exploded antrun:run -Dlutece-test-hsql test`) — uniquement pour les packaging `lutece-plugin` et `lutece-core`
8. **Les starters n'ont pas de tests de vérification** — ils peuvent être releasés directement après mise à jour des versions
9. **Le descripteur XML du plugin** (`webapp/WEB-INF/plugins/*.xml`) contient un tag `<version>` qui doit être synchronisé avec la version du `pom.xml` à chaque changement de version
10. **Les Release Candidates ne touchent pas master** — le deploy RC se fait depuis `develop`, et la version SNAPSHOT d'origine est restaurée après (pas d'incrément)
11. **Les releases individuelles ne touchent pas master** — seule une release `all` merge develop sur master (les autres modules peuvent encore etre en SNAPSHOT)
12. **Les versions des modules sont découplées** — chaque module a sa propre property de version (`lutece.<module>.version`), ce qui permet des releases indépendantes

## 🔍 Commandes utiles

```bash
# Lister toutes les dépendances SNAPSHOT d'un module
mvn dependency:list -DincludeSnapshots=true -DexcludeTransitive=true

# Vérifier les mises à jour disponibles
mvn versions:display-dependency-updates

# Analyser l'arbre de dépendances d'un starter
mvn dependency:tree -pl forms-starter

# Build complet sans tests (vérification rapide)
mvn clean package -DskipTests

# Lancer les tests d'un plugin Lutece (avec HSQL en mémoire)
# UNIQUEMENT pour les composants avec <packaging>lutece-plugin</packaging> ou <packaging>lutece-core</packaging>
mvn lutece:exploded antrun:run -Dlutece-test-hsql test

# Les autres types de packaging (pom, jar, lutece-site...) n'ont pas ce type de test
# Les starters n'ont pas de tests de vérification
```

## 📁 Fichiers importants

| Fichier | Rôle |
|---|---|
| `pom.xml` (racine) | POM parent, versions de tous les plugins |
| `lutece-bom/pom.xml` | BOM des dépendances |
| `*/pom.xml` | POM des starters |
| `Jenkinsfile-release` | Pipeline de release |
| `RELEASE_PLAYBOOK.md` | Documentation complète du processus de release |
| `.mvn/maven.config` | Options Maven par défaut (si présent) |
