/**
 * Lutece Release Pipeline — Helper Functions
 *
 * Scope: this pipeline releases the lutece-platform monorepo only — the
 * specialized starters, release-test-lutece-starter and release-test-lutece-bom. Plugin releases are
 * out of scope: plugin versions are maintained by hand in the root pom.xml
 * and must already be release versions (see stageValidateReleaseReadiness).
 *
 * Extrait du Jenkinsfile-release pour eviter l'erreur "Method too large"
 * du moteur CPS Jenkins (limite 64 Ko de bytecode par methode JVM).
 *
 * Charge via : def helpers = load('release-helpers.groovy')
 */

/**
 * Returns the POM property name that holds the version for a given starter/BOM module.
 * Example: "release-test-forms-starter" -> "lutece.release-test-forms-starter.version"
 */
def starterVersionProperty(String starterName) {
    return "lutece.${starterName}.version"
}

/**
 * Checks whether the RELEASE_TARGET is a single module (not 'all').
 */
def isSingleModuleRelease() {
    return params.RELEASE_TARGET != 'all'
}

// ========================================================================
// Pre-release qualifiers — beta-NN / RC-NN
// ========================================================================

/**
 * Zero-pads a pre-release number to 2 digits: '1' -> '01', '12' -> '12'.
 */
def padPrereleaseNumber(String raw) {
    def digits = (raw?.trim() ?: '1').replaceAll('[^0-9]', '')
    if (!digits) {
        digits = '1'
    }
    return digits.padLeft(2, '0')
}

/**
 * Appends the pre-release qualifier of the current build to a base version.
 *   none -> 8.0.0
 *   beta -> 8.0.0-beta-01
 *   rc   -> 8.0.0-RC-01
 */
def qualifyVersion(String base) {
    def type = env.PRERELEASE_TYPE ?: 'none'
    if (type == 'none') {
        return base
    }
    def qualifier = (type == 'rc') ? 'RC' : type
    return "${base}-${qualifier}-${env.PRERELEASE_NUM}".toString()
}

/**
 * Strips any -SNAPSHOT and any pre-release qualifier from a version.
 *   8.0.0-SNAPSHOT   -> 8.0.0
 *   8.0.0-beta-01    -> 8.0.0
 *   8.0.0-RC-01      -> 8.0.0
 */
def stripQualifier(String version) {
    return version.replaceAll('-SNAPSHOT$', '')
                  .replaceAll('(?i)-(RC|beta|alpha)[-.]?\\d+$', '')
}

/**
 * Human-readable label for the current build type, used in logs and reports.
 */
def prereleaseLabel() {
    def type = env.PRERELEASE_TYPE ?: 'none'
    if (type == 'none') {
        return 'Stable'
    }
    if (type == 'rc') {
        return "Release Candidate ${env.PRERELEASE_NUM}".toString()
    }
    return "Beta ${env.PRERELEASE_NUM}".toString()
}

// ========================================================================
// Git tag naming
// ========================================================================

/**
 * Tag name for a platform-wide release (RELEASE_TARGET = 'all').
 * Example: v8.0.0-beta-01
 */
def platformTagName(String version) {
    return "v${version}".toString()
}

/**
 * Tag name for a single module release.
 * Example: release-test-forms-starter-8.0.0-beta-01
 */
def moduleTagName(String moduleName, String version) {
    return "${moduleName}-${version}".toString()
}

/**
 * Computes the git tag(s) this release must create on the monorepo.
 *
 * - single-module target                 -> [release-test-forms-starter-8.0.0-beta-01]
 * - 'all', module versions aligned       -> [v8.0.0-beta-01]
 * - 'all', module versions NOT aligned   -> one tag per module, so that every
 *   artifact published to Nexus stays traceable to a tag.
 */
def resolveReleaseTags() {
    if (isSingleModuleRelease()) {
        return [moduleTagName(params.RELEASE_TARGET, env.COMPUTED_RELEASE_VERSION)]
    }

    def moduleVersions = readJSON(text: env.MODULE_VERSIONS_JSON)
    def distinct = moduleVersions.collect { mod, info -> info.releaseVersion } as Set

    if (distinct.size() == 1 && distinct.contains(env.COMPUTED_RELEASE_VERSION)) {
        return [platformTagName(env.COMPUTED_RELEASE_VERSION)]
    }

    echo "WARNING: module versions ${distinct} differ from the platform version ${env.COMPUTED_RELEASE_VERSION}"
    echo "         -> falling back to per-module tags to keep Nexus artifacts traceable"
    return moduleVersions.collect { mod, info -> moduleTagName(mod, info.releaseVersion) }
}

// ========================================================================
// Lutece line (V7 / V8) and JDK selection
// ========================================================================

/**
 * Extracts the major of the <parent> version declared in a POM.
 * Every Lutece POM inherits from lutece-global-pom, whose major matches the
 * Lutece line: global-pom 7.0.x -> Lutece 7, global-pom 8.0.x -> Lutece 8.
 *
 * Returns null when the parent block or its version cannot be found.
 */
def parentGlobalPomMajor(String pomContent) {
    def matcher = pomContent =~ '(?s)<parent>.*?<version>\\s*([^<\\s]+)\\s*</version>.*?</parent>'
    if (!matcher.find()) {
        return null
    }
    def majorMatcher = matcher.group(1) =~ '^(\\d+)'
    return majorMatcher ? majorMatcher[0][1] : null
}

/**
 * Detects the Lutece line ('7' or '8') of the POM in the current directory.
 *
 * The line always comes from the checked-out sources. LUTECE_MAJOR is an
 * assertion, not an override: forcing a line the workspace does not hold
 * would release the wrong sources under the wrong version, so a mismatch
 * fails the build instead.
 */
def detectLuteceMajor() {
    def detected = parentGlobalPomMajor(readFile('pom.xml'))
    def forced = params.LUTECE_MAJOR?.trim()
    def isForced = forced && forced != 'auto'

    if (!detected) {
        if (isForced) {
            echo "WARNING: could not read the global-pom version from <parent> — using LUTECE_MAJOR=${forced}"
            return forced
        }
        echo "WARNING: could not read the global-pom version from <parent> — assuming Lutece 8"
        return '8'
    }

    if (isForced && forced != detected) {
        error("""LUTECE_MAJOR=${forced} but the checked-out pom.xml inherits lutece-global-pom ${detected}.x, i.e. Lutece ${detected}.

The pipeline releases the sources present in the workspace; forcing the line
does not change what was checked out. To release Lutece ${forced}, point the job's
SCM at that line's branch (V7: develop_core7, V8: develop), or set
LUTECE_MAJOR=auto to release Lutece ${detected} from the current checkout.""")
    }

    return detected
}

/**
 * Resolves the monorepo branch this release runs on.
 *
 * The branch is always the one the workspace actually holds. It is never
 * derived from the Lutece line: deriving it would let a V8 checkout be pushed
 * to the V7 branch (or the reverse) whenever the branch could not be detected.
 *
 * MONOREPO_BRANCH is honoured, but only when it agrees with the checkout —
 * the pipeline cannot switch branches, only the job's SCM can.
 */
def resolveMonorepoBranch() {
    def checkedOut = detectCheckedOutBranch()
    def explicit = params.MONOREPO_BRANCH?.trim()

    if (explicit) {
        if (checkedOut && explicit != checkedOut) {
            error("""MONOREPO_BRANCH=${explicit} but the workspace holds the branch ${checkedOut}.

The pipeline releases what was checked out; it does not switch branches.
Point the job's SCM at ${explicit}, or clear MONOREPO_BRANCH to release ${checkedOut}.""")
        }
        return explicit
    }

    if (!checkedOut) {
        error("""Could not determine which branch the workspace holds.

Set MONOREPO_BRANCH explicitly, and make sure it matches the branch configured
in the job's SCM — otherwise the release would be pushed to the wrong branch.""")
    }

    return checkedOut
}

/**
 * Branch a stable release is promoted to : MASTER_BRANCH, 'master' when empty.
 * The releaser derives it from the release branch (master for develop,
 * master_core7 for develop_core7), like the classic component release.
 */
def resolveMasterBranch() {
    def explicit = params.MASTER_BRANCH?.trim()
    return explicit ?: 'master'
}

/**
 * Determines the branch the workspace actually holds.
 *
 * Jenkins checks out a detached SHA, so `git rev-parse --abbrev-ref HEAD`
 * returns 'HEAD' and cannot be used on its own. Hence the fallbacks on the
 * Git plugin variables, then on the remote refs pointing at HEAD.
 *
 * Returns null when the branch cannot be established — the caller then fails
 * rather than guessing a branch name.
 */
def detectCheckedOutBranch() {
    if (env.BRANCH_NAME?.trim()) {
        // Multibranch Pipeline
        echo "Checked-out branch from BRANCH_NAME: ${env.BRANCH_NAME.trim()}"
        return env.BRANCH_NAME.trim()
    }

    if (env.GIT_BRANCH?.trim()) {
        // Git plugin on a single-branch Pipeline job (e.g. 'origin/develop')
        def fromPlugin = env.GIT_BRANCH.trim().replaceFirst('^origin/', '')
        echo "Checked-out branch from GIT_BRANCH: ${fromPlugin}"
        return fromPlugin
    }

    def symbolic = sh(script: 'git symbolic-ref --short HEAD 2>/dev/null || true', returnStdout: true).trim()
    if (symbolic && symbolic != 'HEAD') {
        echo "Checked-out branch from symbolic-ref: ${symbolic}"
        return symbolic
    }

    // Detached HEAD: look for the remote branches pointing at this commit
    def pointing = sh(
        script: "git for-each-ref --points-at HEAD --format='%(refname:short)' refs/remotes/origin 2>/dev/null || true",
        returnStdout: true
    ).trim()
    def candidates = pointing.split('\n')
        .collect { it.trim().replaceFirst('^origin/', '') }
        .findAll { it && it != 'HEAD' }
        .unique()

    if (candidates.size() == 1) {
        echo "Checked-out branch from the refs pointing at HEAD: ${candidates[0]}"
        return candidates[0]
    }
    if (candidates.size() > 1) {
        echo "WARNING: HEAD is pointed at by several branches: ${candidates.join(', ')}"
        return null
    }
    return null
}

/**
 * Normalizes a targetJdk value to a plain major number: '1.8' -> '8', '17' -> '17'.
 */
def normalizeJdkMajor(String raw) {
    def value = raw?.trim()
    if (!value) {
        return null
    }
    if (value.startsWith('1.')) {
        return value.substring(2)
    }
    def matcher = value =~ '^(\\d+)'
    return matcher ? matcher[0][1] : null
}

/**
 * Resolves the effective <targetJdk> of the POM in the current directory.
 *
 * targetJdk is declared by lutece-global-pom, the parent of every Lutece POM:
 *   global-pom 7.0.x   -> <targetJdk>11</targetJdk>
 *   global-pom 8.0.0   -> <targetJdk>17</targetJdk>
 *   global-pom 8.0.1+  -> <targetJdk>${java.version}</targetJdk>   (java.version = 17)
 *
 * `mvn help:evaluate` is used so that inheritance and property indirection
 * (${java.version}) are resolved by Maven itself rather than re-implemented
 * here. When the parent POM cannot be resolved, we fall back to the Lutece
 * line: V7 -> JDK 11, V8 -> JDK 17.
 */
def detectTargetJdk() {
    def raw = null
    try {
        raw = sh(
            script: "mvn -s ${env.MAVEN_SETTINGS_XML} -N -q help:evaluate -Dexpression=targetJdk -DforceStdout 2>/dev/null | tail -1",
            returnStdout: true
        ).trim()
    } catch (Throwable e) {
        echo "WARNING: could not evaluate targetJdk with Maven: ${e.message}"
    }

    def major = normalizeJdkMajor(raw)
    if (major) {
        echo "targetJdk resolved from the effective POM: '${raw}' -> JDK ${major}"
        return major
    }

    def fallback = detectLuteceMajor() == '7' ? '11' : '17'
    echo "WARNING: targetJdk could not be evaluated (got: '${raw}') — falling back to JDK ${fallback}"
    return fallback
}

/**
 * Maps a JDK major number to a Jenkins JDK tool name.
 * Default convention: temurin-{major}-jdk, matching the tool already declared
 * in the Jenkinsfile. Overridable per build with the JDK_TOOL_MAP parameter,
 * e.g. "11=my-jdk11,17=my-jdk17".
 */
def jdkToolName(String major) {
    def overrides = [:]
    params.JDK_TOOL_MAP?.split(',')?.each { entry ->
        def parts = entry.split('=')
        if (parts.length == 2) {
            overrides[parts[0].trim()] = parts[1].trim()
        }
    }
    return (overrides[major] ?: "temurin-${major}-jdk").toString()
}

/**
 * Runs the closure with JAVA_HOME pointing at the Jenkins JDK tool matching
 * the requested major version.
 *
 * Falls back to the build's default JDK (the one declared in the `tools`
 * block) with an explicit warning when the tool is not configured, so that a
 * missing tool declaration does not silently compile against the wrong JDK.
 */
def withJdk(String major, Closure body) {
    if (!major) {
        echo "No target JDK resolved — using the build default JDK"
        body()
        return
    }

    def toolName = jdkToolName(major)
    def jdkHome = null
    try {
        jdkHome = tool(name: toolName, type: 'jdk')
    } catch (Throwable e) {
        echo "WARNING: Jenkins JDK tool '${toolName}' is not configured (${e.message})"
        echo "         -> using the build default JDK. Declare it in Manage Jenkins > Tools,"
        echo "            or remap it with the JDK_TOOL_MAP parameter."
        body()
        return
    }

    echo "Using JDK ${major} -> tool '${toolName}' (${jdkHome})"
    withEnv(["JAVA_HOME=${jdkHome}", "PATH+JDK=${jdkHome}/bin"]) {
        body()
    }
}

/**
 * Reads all 5 module version properties from root pom.xml and builds a version map.
 * Each entry contains: current (SNAPSHOT), release, next (next SNAPSHOT).
 *
 * Used by 'all' mode to handle modules with heterogeneous versions.
 *
 * @param pomContent  Root pom.xml content as String
 * @return Map keyed by module name (e.g. 'release-test-forms-starter') with version details
 */
def readModuleVersionMap(String pomContent) {
    def modules = ['release-test-forms-starter', 'release-test-appointment-starter', 'release-test-editorial-starter', 'release-test-lutece-starter', 'release-test-lutece-bom']
    def isPre = env.IS_PRERELEASE == 'true'

    def versionMap = [:]
    modules.each { mod ->
        def prop = starterVersionProperty(mod)
        def matcher = pomContent =~ "<${prop}>([^<]+)</${prop}>"
        if (matcher.find()) {
            def current = matcher[0][1]
            def base = stripQualifier(current)
            def release = qualifyVersion(base)
            def next = isPre ? current : computeNextSnapshot(base)
            versionMap[mod] = [
                property     : prop,
                current      : current,
                releaseVersion: release,
                next         : next
            ]
        } else {
            echo "WARNING: Could not find property <${prop}> in root pom.xml"
        }
    }
    return versionMap
}

/**
 * Returns the release version for a given module.
 *
 * For 'all' mode: looks up the module's version from the per-module version map.
 * For single-module mode: returns env.COMPUTED_RELEASE_VERSION.
 *
 * @param moduleName  Module name (e.g. 'release-test-forms-starter', 'release-test-lutece-bom')
 * @return The release version string for this module
 */
def getModuleReleaseVersion(String moduleName) {
    if (isSingleModuleRelease()) {
        return env.COMPUTED_RELEASE_VERSION
    }
    // 'all' mode: read from per-module version map
    def moduleVersions = readJSON(text: env.MODULE_VERSIONS_JSON)
    def info = moduleVersions[moduleName]
    if (info) {
        return info.releaseVersion
    }
    // Fallback (should never happen for known modules)
    echo "WARNING: No per-module version found for ${moduleName}, falling back to COMPUTED_RELEASE_VERSION"
    return env.COMPUTED_RELEASE_VERSION
}

/**
 * Computes the next SNAPSHOT version by incrementing the patch number.
 * Example: "8.0.0" -> "8.0.1-SNAPSHOT"
 */
def computeNextSnapshot(String version) {
    def parts = stripQualifier(version).split('\\.')
    if (parts.length < 3) {
        return "${version}.1-SNAPSHOT".toString()
    }
    def patch = parts[2].toInteger() + 1
    return "${parts[0]}.${parts[1]}.${patch}-SNAPSHOT".toString()
}

/**
 * Resolves which starters (and BOM) to release based on the target parameter.
 * Returns a comma-separated string.
 *
 * "release-test-lutece-starter" implies the 3 specialized starters must be released first.
 * "all" includes everything.
 */
def resolveStartersToRelease(String target) {
    switch (target) {
        case 'release-test-forms-starter':
            return 'release-test-forms-starter'
        case 'release-test-appointment-starter':
            return 'release-test-appointment-starter'
        case 'release-test-editorial-starter':
            return 'release-test-editorial-starter'
        case 'release-test-lutece-starter':
            return 'release-test-lutece-starter'
        case 'release-test-lutece-bom':
            return 'release-test-lutece-bom'
        case 'all':
            return 'release-test-forms-starter,release-test-appointment-starter,release-test-editorial-starter,release-test-lutece-starter,release-test-lutece-bom'
        default:
            return target
    }
}

/**
 * Parses the root pom.xml content and extracts all <lutece.*.version> properties
 * that still hold a SNAPSHOT version.
 *
 * Plugin versions are maintained by hand in the root pom.xml; this pipeline
 * only reads them, to refuse releasing a starter that would depend on a
 * SNAPSHOT (see stageValidateReleaseReadiness).
 *
 * Returns a list of maps: [propertyName, artifactId, version]
 */
def parseSnapshotPlugins(String pomContent) {
    def plugins = []
    // Strip XML comments before scanning to avoid detecting commented-out properties
    def cleanContent = pomContent.replaceAll('(?s)<!--.*?-->', '')
    // Monorepo module version properties — these are NOT external plugins
    def monorepoProperties = [
        'lutece.release-test-forms-starter.version',
        'lutece.release-test-appointment-starter.version',
        'lutece.release-test-editorial-starter.version',
        'lutece.release-test-lutece-starter.version',
        'lutece.release-test-lutece-bom.version'
    ] as Set
    def matcher = cleanContent =~ '<(lutece\\.[a-zA-Z0-9._-]+\\.version)>([^<]*-SNAPSHOT)</'
    while (matcher.find()) {
        def propertyName = matcher.group(1)
        def version = matcher.group(2)
        // Skip monorepo module properties — they have their own release lifecycle (Stages 6-8)
        if (monorepoProperties.contains(propertyName)) {
            continue
        }
        // Derive artifactId from property name:
        // lutece.plugin-forms.version -> plugin-forms
        // lutece.library-lucene.version -> library-lucene
        // lutece.core.version -> lutece-core
        // Use replaceFirst to avoid stripping "lutece." inside the name (e.g. plugin-mylutece)
        def artifactId = propertyName
            .replaceFirst('^lutece\\.', '')
            .replaceFirst('\\.version$', '')
        if (artifactId == 'core') {
            artifactId = 'lutece-core'
        }
        plugins.add([
            propertyName: propertyName,
            artifactId: artifactId,
            version: version
        ])
    }
    return plugins
}

/**
 * Builds and deploys a starter or BOM module of this monorepo to Nexus.
 *
 * Tagging (stage 'Tag Release') and the master merge (stage 'Promote to
 * master') are deliberately NOT done here: this function runs inside parallel
 * stages, and tagging or checking out master concurrently from the same
 * working copy corrupts it.
 *
 * The module is built with the JDK matching the POM's targetJdk, so the same
 * pipeline releases the V7 line (JDK 11) and the V8 line (JDK 17).
 *
 * Master is never touched before deploy succeeds -> simple rollback (tag delete only).
 */
def releaseStarter(String starterName) {
    def moduleVersion = getModuleReleaseVersion(starterName)
    def isPre = env.IS_PRERELEASE == 'true'

    echo "=== Releasing ${starterName} ${moduleVersion}${isPre ? ' (' + prereleaseLabel() + ')' : ''} ==="

    if (params.DRY_RUN) {
        echo "[DRY-RUN] Would release ${starterName}"
        echo "[DRY-RUN]   Tag(s) already handled by the 'Tag Release' stage: ${env.RELEASE_TAGS ?: '(not computed)'}"
        echo "[DRY-RUN]   Build with JDK ${env.PLATFORM_TARGET_JDK ?: '(build default)'}"
        echo "[DRY-RUN]   Deploy ${starterName}:${moduleVersion} to Nexus"
        return
    }

    // Build the module and its reactor dependencies (install only), then deploy ONLY the target module.
    // Using -am with deploy would re-deploy already-published dependencies -> Nexus 400 Bad Request.
    withJdk(env.PLATFORM_TARGET_JDK) {
        sh "mvn -s ${env.MAVEN_SETTINGS_XML} clean install -pl ${starterName} -am -DskipTests -DperformRelease=true"
        sh "mvn -s ${env.MAVEN_SETTINGS_XML} deploy -pl ${starterName} -DskipTests -DperformRelease=true"
    }

    echo "Released ${starterName} ${moduleVersion} successfully."
}

/**
 * Rolls back a failed starter release within the monorepo.
 *
 * Master is never touched before deploy succeeds, so rollback is simple:
 * - Delete the release tag(s) (local + remote)
 * - Ensure we are back on the release branch
 */
def rollbackStarter(String starterName) {
    echo "=== Rolling back ${starterName} ==="

    if (params.DRY_RUN) {
        echo "[DRY-RUN] Would rollback ${starterName}"
        return
    }

    // The release tag(s) are created once by the 'Tag Release' stage, so the
    // rollback deletes those — not a per-module tag that no longer exists.
    rollbackTags()

    // Ensure we are back on the release branch
    try {
        sh "git checkout ${env.MONOREPO_BRANCH}"
    } catch (Throwable e) {
        echo "WARNING: Could not checkout ${env.MONOREPO_BRANCH}: ${e.message}"
    }
}

/**
 * Deletes the release tag(s) created by the 'Tag Release' stage, locally and
 * on the remote. Safe to call more than once.
 */
def rollbackTags() {
    def tags = env.RELEASE_TAGS ? env.RELEASE_TAGS.split(',').collect { it.trim() }.findAll { it } : []
    if (!tags) {
        echo "No release tag to roll back"
        return
    }

    if (params.DRY_RUN) {
        echo "[DRY-RUN] Would delete tag(s): ${tags.join(', ')}"
        return
    }

    tags.each { tag ->
        try {
            sh "git tag -d ${tag} || true"
            sh "git push origin :refs/tags/${tag} || true"
            echo "Deleted tag ${tag}"
        } catch (Throwable e) {
            echo "WARNING: Could not delete tag ${tag}: ${e.message}"
        }
    }
}

/**
 * Appends a line to the release report file.
 */
def appendReport(String line) {
    def safe = line.replace("'", "'\\''")
    sh "printf '%s\\n' '${safe}' >> ${env.RELEASE_REPORT}"
}

/**
 * Generates and returns the full release report content.
 */
def generateReleaseReport() {
    return readFile(env.RELEASE_REPORT)
}

// ========================================================================
// Stage body methods — extracted from Jenkinsfile to avoid CPS
// "Method too large" (64 KB JVM bytecode limit per method).
// Each method encapsulates the logic of one pipeline stage.
// ========================================================================

/**
 * Stage 0 — Initialize: configure git, compute versions, create report.
 */
def stageInitialize() {
    // -- Pre-release type: none / beta / rc
    env.PRERELEASE_TYPE = params.PRERELEASE_TYPE?.trim()?.toLowerCase() ?: 'none'
    env.PRERELEASE_NUM = padPrereleaseNumber(params.PRERELEASE_NUMBER)
    env.IS_PRERELEASE = (env.PRERELEASE_TYPE != 'none') ? 'true' : 'false'

    configFileProvider([configFile(fileId: params.MAVEN_SETTINGS_ID, variable: 'MVN_SETTINGS_TMP')]) {
        sh "cp \${MVN_SETTINGS_TMP} ${WORKSPACE}/maven-settings.xml"
    }
    env.MAVEN_SETTINGS_XML = "${WORKSPACE}/maven-settings.xml"
    echo "Maven settings provisioned: ${env.MAVEN_SETTINGS_XML}"

    sh "git config user.email '${params.GIT_USER_EMAIL}'"
    sh "git config user.name '${params.GIT_USER_NAME}'"

    // -- Lutece line (V7 / V8), release branch and JDK.
    //    The branch MUST be resolved before any checkout: the V7 line lives on
    //    develop_core7 and the V8 line on develop.
    env.LUTECE_MAJOR_RESOLVED = detectLuteceMajor()
    env.MONOREPO_BRANCH = resolveMonorepoBranch()
    env.MASTER_BRANCH = resolveMasterBranch()
    env.PLATFORM_TARGET_JDK = detectTargetJdk()
    env.PLATFORM_JDK_TOOL = jdkToolName(env.PLATFORM_TARGET_JDK)

    echo "=========================================="
    echo " Lutece Release Pipeline"
    echo " Target      : ${params.RELEASE_TARGET}"
    echo " Build type  : ${prereleaseLabel()}"
    echo " Dry-Run     : ${params.DRY_RUN}"
    echo " Lutece line : V${env.LUTECE_MAJOR_RESOLVED}"
    echo " Branch      : ${env.MONOREPO_BRANCH}"
    echo " targetJdk   : ${env.PLATFORM_TARGET_JDK} (Jenkins tool: ${env.PLATFORM_JDK_TOOL})"
    echo "=========================================="

    sh "git checkout -B ${env.MONOREPO_BRANCH}"

    withCredentials([string(credentialsId: params.GITHUB_CREDENTIAL_ID, variable: 'GITHUB_TOKEN')]) {
        sh 'git remote set-url origin https://$GITHUB_TOKEN@github.com/lutece-platform/release-test-lutece-platform.git'
    }

    def pomContent = readFile('pom.xml')
    def currentVersion
    if (isSingleModuleRelease()) {
        def versionProp = starterVersionProperty(params.RELEASE_TARGET)
        def propMatcher = pomContent =~ "<${versionProp}>([^<]+)</${versionProp}>"
        if (propMatcher.find()) {
            currentVersion = propMatcher[0][1]
        } else {
            error("Could not find property <${versionProp}> in root pom.xml")
        }
        echo "Single module release: ${params.RELEASE_TARGET}"
        echo "Version property: ${versionProp}"
    } else {
        currentVersion = (pomContent =~ '<artifactId>release-test-lutece-parent</artifactId>\\s*\\n\\s*<version>([^<]+)</version>')[0][1]
        def versionMap = readModuleVersionMap(pomContent)
        writeJSON file: "${WORKSPACE}/module-versions.json", json: versionMap
        env.MODULE_VERSIONS_JSON = readFile("${WORKSPACE}/module-versions.json")
        echo "Per-module versions:"
        versionMap.each { mod, info ->
            echo "  ${mod}: ${info.current} -> ${info.releaseVersion} -> ${info.next}"
        }
    }
    echo "Current project version: ${currentVersion}"
    env.ORIGINAL_SNAPSHOT_VERSION = currentVersion

    def baseVersion
    if (params.RELEASE_VERSION?.trim()) {
        baseVersion = stripQualifier(params.RELEASE_VERSION.trim())
    } else {
        baseVersion = stripQualifier(currentVersion)
    }
    env.BASE_RELEASE_VERSION = baseVersion
    env.COMPUTED_RELEASE_VERSION = qualifyVersion(baseVersion)

    // In 'all' mode RELEASE_VERSION only drives the release-test-lutece-parent version:
    // each module is published under its own <lutece.{module}.version>. When
    // they disagree, the announced release version names no published
    // artifact, so make it loud rather than let it pass in a log line.
    if (!isSingleModuleRelease() && params.RELEASE_VERSION?.trim()) {
        def declaredVersions = readJSON(text: env.MODULE_VERSIONS_JSON)
        def moduleBases = declaredVersions.collect { mod, info -> stripQualifier(info.current) } as Set
        if (!moduleBases.contains(baseVersion)) {
            echo "WARNING: RELEASE_VERSION=${baseVersion} matches none of the module versions ${moduleBases}"
            echo "         In 'all' mode, RELEASE_VERSION only sets the release-test-lutece-parent version."
            echo "         The modules will be published as ${moduleBases.join(', ')}, and the"
            echo "         platform tag falls back to per-module tags."
            echo "         To release the whole platform as ${baseVersion}, bump the 5"
            echo "         <lutece.*.version> properties and release-test-lutece-parent in pom.xml first,"
            echo "         then leave RELEASE_VERSION empty."
            appendReport("WARNING: RELEASE_VERSION ${baseVersion} does not match the module versions ${moduleBases.join(', ')}")
            unstable("RELEASE_VERSION ${baseVersion} does not match the module versions ${moduleBases.join(', ')}")
        }
    }

    if (env.IS_PRERELEASE == 'true') {
        env.COMPUTED_NEXT_SNAPSHOT = env.ORIGINAL_SNAPSHOT_VERSION
    } else if (params.NEXT_SNAPSHOT_VERSION?.trim()) {
        env.COMPUTED_NEXT_SNAPSHOT = params.NEXT_SNAPSHOT_VERSION.trim()
    } else {
        env.COMPUTED_NEXT_SNAPSHOT = computeNextSnapshot(baseVersion)
    }

    echo "Release version      : ${env.COMPUTED_RELEASE_VERSION}"
    echo "Next SNAPSHOT version: ${env.COMPUTED_NEXT_SNAPSHOT}"
    if (env.IS_PRERELEASE == 'true') {
        echo "${prereleaseLabel()}: ${env.MASTER_BRANCH} is NOT touched, deploy runs from ${env.MONOREPO_BRANCH}, SNAPSHOT restored afterwards"
    }

    env.STARTERS_TO_RELEASE = resolveStartersToRelease(params.RELEASE_TARGET)
    echo "Starters to release : ${env.STARTERS_TO_RELEASE}"

    def buildLabel = env.IS_PRERELEASE == 'true' ? " (${prereleaseLabel()})" : ''
    writeFile file: env.RELEASE_REPORT, text: """Lutece Release Report${buildLabel}
====================================
Date         : ${new Date()}
Target       : ${params.RELEASE_TARGET}
Lutece line  : V${env.LUTECE_MAJOR_RESOLVED}
Branch       : ${env.MONOREPO_BRANCH}
targetJdk    : ${env.PLATFORM_TARGET_JDK} (Jenkins tool: ${env.PLATFORM_JDK_TOOL})
Build type   : ${prereleaseLabel()}
Release Ver  : ${env.COMPUTED_RELEASE_VERSION}
Next SNAPSHOT: ${env.COMPUTED_NEXT_SNAPSHOT}
Dry-Run      : ${params.DRY_RUN}
====================================

"""
}

/**
 * Stage 4 — Update POM parent versions (plugin properties + module/parent versions).
 */
def stageUpdatePomVersions() {
    echo "Updating root pom.xml with release versions..."
    def pomFile = 'pom.xml'
    def singleModule = isSingleModuleRelease()

    if (singleModule) {
        _updatePomSingleModule(pomFile)
    } else {
        _updatePomAllModules(pomFile)
    }

    if (singleModule) {
        appendReport("POM Updated: ${params.RELEASE_TARGET} version set to ${env.COMPUTED_RELEASE_VERSION}")
    } else {
        def moduleVersions = readJSON(text: env.MODULE_VERSIONS_JSON)
        appendReport("POM Updated: per-module versions:")
        moduleVersions.each { mod, info ->
            appendReport("  ${mod}: ${info.current} -> ${info.releaseVersion}")
        }
    }
}

/** Internal: update POM for single-module release. */
def _updatePomSingleModule(String pomFile) {
    def versionProp = starterVersionProperty(params.RELEASE_TARGET)
    echo "Single module: updating only <${versionProp}>"

    if (!params.DRY_RUN) {
        sh "sed -i 's|<${versionProp}>${env.ORIGINAL_SNAPSHOT_VERSION}</${versionProp}>|<${versionProp}>${env.COMPUTED_RELEASE_VERSION}</${versionProp}>|g' ${pomFile}"
        sh """
            git add pom.xml
            git diff --cached --quiet && echo 'Version already at ${env.COMPUTED_RELEASE_VERSION} — nothing to commit' || git commit -m "release: update ${params.RELEASE_TARGET} version to ${env.COMPUTED_RELEASE_VERSION}"
        """
        sh "git push origin ${env.MONOREPO_BRANCH}"
    } else {
        echo "[DRY-RUN] Would update <${versionProp}> to ${env.COMPUTED_RELEASE_VERSION}"
    }
}

/** Internal: update POM for 'all' release. */
def _updatePomAllModules(String pomFile) {
    def moduleVersions = readJSON(text: env.MODULE_VERSIONS_JSON)

    if (!params.DRY_RUN) {
        sh "sed -i '/<artifactId>release-test-lutece-parent<\\/artifactId>/{n;s|<version>[^<]*</version>|<version>${env.COMPUTED_RELEASE_VERSION}</version>|}' ${pomFile}"

        moduleVersions.each { mod, info ->
            sh "sed -i 's|<${info.property}>${info.current}</${info.property}>|<${info.property}>${info.releaseVersion}</${info.property}>|g' ${pomFile}"
            echo "Updated ${info.property}: ${info.current} -> ${info.releaseVersion}"
        }

        def modules = ['release-test-lutece-bom', 'release-test-forms-starter', 'release-test-appointment-starter', 'release-test-editorial-starter', 'release-test-lutece-starter']
        modules.each { mod ->
            def modPom = "${mod}/pom.xml"
            if (fileExists(modPom)) {
                sh "sed -i '/<artifactId>release-test-lutece-parent<\\/artifactId>/{n;s|<version>[^<]*</version>|<version>${env.COMPUTED_RELEASE_VERSION}</version>|}' ${modPom}"
            }
        }

        sh """
            git add pom.xml */pom.xml
            git diff --cached --quiet && echo 'Versions already at release — nothing to commit' || git commit -m "release: update versions for release"
        """
        sh "git push origin ${env.MONOREPO_BRANCH}"
    } else {
        echo "[DRY-RUN] Would update POM parent and per-module versions:"
        moduleVersions.each { mod, info ->
            echo "[DRY-RUN]   ${info.property}: ${info.current} -> ${info.releaseVersion}"
        }
    }
}

/**
 * Stage 5 — Validate no SNAPSHOT dependencies remain.
 */
def stageValidateReleaseReadiness() {
    echo "Checking that no plugin dependency is still a SNAPSHOT..."

    def pomContent = readFile('pom.xml')
    def remainingSnapshots = parseSnapshotPlugins(pomContent)

    def ignoredProps = ['lutece.release-test-forms-starter.version',
                        'lutece.release-test-appointment-starter.version',
                        'lutece.release-test-editorial-starter.version',
                        'lutece.release-test-lutece-starter.version',
                        'lutece.release-test-lutece-bom.version']
    def violations = remainingSnapshots.findAll { !ignoredProps.contains(it.propertyName) }

    if (violations.size() > 0) {
        echo "WARNING: ${violations.size()} plugin dependencies are still SNAPSHOT:"
        violations.each { echo "  - ${it.propertyName} = ${it.version}" }
        appendReport("\nSNAPSHOT Violations: ${violations.size()}")
        violations.each { appendReport("  - ${it.propertyName} = ${it.version}") }
        appendReport("  -> Releaser ces plugins, puis mettre a jour leurs versions dans pom.xml")

        // Plugin releases are out of this pipeline's scope: their versions are
        // maintained by hand in the root pom.xml, so a remaining SNAPSHOT is a
        // missing prerequisite, not something the pipeline can fix.
        //
        // Blocking by default: releasing a starter that depends on a SNAPSHOT
        // publishes an artifact whose content keeps changing under its
        // consumers. This stage runs before any tag or deploy, so failing here
        // leaves nothing to roll back.
        if (params.ALLOW_SNAPSHOT_DEPENDENCIES) {
            appendReport("  -> ALLOW_SNAPSHOT_DEPENDENCIES=true : publication malgre les violations")
            unstable("${violations.size()} plugin dependencies are still SNAPSHOT — published anyway (ALLOW_SNAPSHOT_DEPENDENCIES=true)")
        } else {
            error("""${violations.size()} plugin dependencies are still SNAPSHOT. Nothing was tagged or deployed.

Releasing a starter that depends on a SNAPSHOT publishes an artifact whose
content keeps changing under its consumers. The full list is in the archived
report (release-report.txt).

To proceed:
  1. release the plugins listed above (outside this pipeline)
  2. update their <lutece.*.version> properties in pom.xml, commit and push
  3. relaunch this build

Or set ALLOW_SNAPSHOT_DEPENDENCIES=true to publish despite the violations.""")
        }
    } else {
        echo "All plugin dependencies are in release version."
        appendReport("Validation: All dependencies are release versions.")
    }
}

/**
 * Stage 5b — Create and push the release tag(s) on the monorepo.
 *
 * Tagging is done here, once and before any deploy, rather than inside
 * releaseStarter(): that ran inside the parallel starter stages, tagging and
 * pushing from the same working copy concurrently.
 *
 * Tag naming:
 *   RELEASE_TARGET = 'all'   -> v8.0.0-beta-01              (platform)
 *   RELEASE_TARGET = starter -> release-test-forms-starter-8.0.0-beta-01 (module)
 */
def stageTagRelease() {
    def tags = resolveReleaseTags()
    env.RELEASE_TAGS = tags.join(',')

    echo "Release tag(s): ${tags.join(', ')}"

    if (params.DRY_RUN) {
        echo "[DRY-RUN] Would create and push tag(s): ${tags.join(', ')}"
        appendReport("Tags (dry-run): ${tags.join(', ')}")
        return
    }

    tags.each { tag ->
        // A `git fetch --tags` on a re-run brings remote tags back locally,
        // so drop the local tag before recreating it.
        sh "git tag -d ${tag} 2>/dev/null || true"
        sh "git tag -a ${tag} -m \"Release ${tag}\""
        // Not forced on purpose: pushing over an existing remote tag must fail
        // loudly rather than silently overwrite a published release.
        sh "git push origin ${tag}"
    }

    appendReport("Tags created: ${tags.join(', ')}")
    appendReport('')
}

/**
 * Stage 8b — Promote the release branch to master.
 *
 * Runs once, after every module has been deployed. This merge used to live in
 * releaseStarter(), which meant the 3 parallel starter jobs each ran
 * `git checkout master && git merge` on the same working copy.
 *
 * Pre-releases (beta / RC) never touch master.
 */
def stagePromoteToMaster() {
    def master = env.MASTER_BRANCH
    if (env.IS_PRERELEASE == 'true') {
        echo "${prereleaseLabel()}: ${master} is not touched by a pre-release"
        return
    }
    if (isSingleModuleRelease()) {
        echo "Single-module release: ${master} is not touched (other modules may still be in SNAPSHOT)"
        return
    }
    def remoteMaster = sh(script: "git ls-remote --heads origin '${master}' | cut -f1", returnStdout: true).trim()
    if (!remoteMaster) {
        error("Branch ${master} does not exist on the remote : create it (or fix MASTER_BRANCH) before promoting the release.")
    }
    if (params.DRY_RUN) {
        echo "[DRY-RUN] Would merge ${env.MONOREPO_BRANCH} into ${master}"
        return
    }

    sh "git fetch origin '${master}:${master}' || git branch '${master}' '${remoteMaster}'"
    sh "git checkout '${master}'"
    sh "git merge ${env.MONOREPO_BRANCH} -m \"Merge ${env.MONOREPO_BRANCH} for release ${env.RELEASE_TAGS}\""
    sh "git push origin '${master}'"
    sh "git checkout ${env.MONOREPO_BRANCH}"

    appendReport("Promoted to ${master}: ${env.RELEASE_TAGS}")
}

/**
 * Stage 6 — Release specialized starters in parallel.
 */
def stageReleaseSpecializedStarters() {
    def starters = env.STARTERS_TO_RELEASE.split(',').collect { it.trim() }.findAll { it }
    def specializedStarters = starters.findAll { it in ['release-test-forms-starter', 'release-test-appointment-starter', 'release-test-editorial-starter'] }

    def parallelSteps = [:]
    specializedStarters.each { starter ->
        parallelSteps[starter] = {
            def starterVersion = getModuleReleaseVersion(starter)
            try {
                releaseStarter(starter)
                appendReport("Starter Released: ${starter} ${starterVersion}")
            } catch (Throwable e) {
                appendReport("FAILED Starter: ${starter} — ${e.message}")
                appendReport("  -> Si le tag existe deja : git push origin :refs/tags/${env.RELEASE_TAGS}")
                appendReport("  -> Si l'artefact est deja sur Nexus : supprimer manuellement depuis l'interface Nexus")
                appendReport("  -> Puis relancer le build")
                try {
                    rollbackStarter(starter)
                    appendReport("ROLLBACK OK: ${starter}")
                } catch (Throwable re) {
                    appendReport("ROLLBACK FAILED: ${starter} — manual intervention required: ${re.message}")
                }
                error("Failed to release ${starter}: ${e.message}")
            }
        }
    }
    parallel parallelSteps
}

/**
 * Stage 7 — Release release-test-lutece-starter.
 */
def stageReleaseLuteceStarter() {
    echo "Validating specialized starters are in release version before releasing release-test-lutece-starter..."

    def luteceStarterPom = readFile('release-test-lutece-starter/pom.xml')
    def starterRefs = ['release-test-forms-starter', 'release-test-appointment-starter', 'release-test-editorial-starter']
    starterRefs.each { ref ->
        if (luteceStarterPom.contains('SNAPSHOT') && luteceStarterPom.contains(ref)) {
            echo "Note: ${ref} version resolved via \${lutece.${ref}.version} parent property"
        }
    }

    def lsVersion = getModuleReleaseVersion('release-test-lutece-starter')
    try {
        releaseStarter('release-test-lutece-starter')
        appendReport("Starter Released: release-test-lutece-starter ${lsVersion}")
    } catch (Throwable e) {
        appendReport("FAILED Starter: release-test-lutece-starter — ${e.message}")
        appendReport("  -> Si le tag existe deja : git push origin :refs/tags/${env.RELEASE_TAGS}")
        appendReport("  -> Si l'artefact est deja sur Nexus : supprimer manuellement depuis l'interface Nexus")
        appendReport("  -> Puis relancer le build")
        try {
            rollbackStarter('release-test-lutece-starter')
            appendReport("ROLLBACK OK: release-test-lutece-starter")
        } catch (Throwable re) {
            appendReport("ROLLBACK FAILED: release-test-lutece-starter — manual intervention required: ${re.message}")
        }
        error("Failed to release release-test-lutece-starter: ${e.message}")
    }
}

/**
 * Stage 8 — Release release-test-lutece-bom.
 */
def stageReleaseBom() {
    def bomVersion = getModuleReleaseVersion('release-test-lutece-bom')
    try {
        releaseStarter('release-test-lutece-bom')
        appendReport("BOM Released: release-test-lutece-bom ${bomVersion}")
    } catch (Throwable e) {
        appendReport("FAILED: release-test-lutece-bom — ${e.message}")
        appendReport("  -> Si le tag existe deja : git push origin :refs/tags/${env.RELEASE_TAGS}")
        appendReport("  -> Si l'artefact est deja sur Nexus : supprimer manuellement depuis l'interface Nexus")
        appendReport("  -> Puis relancer le build")
        try {
            rollbackStarter('release-test-lutece-bom')
            appendReport("ROLLBACK OK: release-test-lutece-bom")
        } catch (Throwable re) {
            appendReport("ROLLBACK FAILED: release-test-lutece-bom — manual intervention required: ${re.message}")
        }
        error("Failed to release release-test-lutece-bom: ${e.message}")
    }
}

/**
 * Stage 9 — Prepare next SNAPSHOT versions.
 */
def stagePrepareNextSnapshot() {
    def isPre = env.IS_PRERELEASE == 'true'
    def singleModule = isSingleModuleRelease()

    if (isPre) {
        echo "${prereleaseLabel()}: restoring original SNAPSHOT version ${env.ORIGINAL_SNAPSHOT_VERSION}"
    } else {
        echo "Preparing next development iteration: ${env.COMPUTED_NEXT_SNAPSHOT}"
    }

    if (params.DRY_RUN) {
        _nextSnapshotDryRun(isPre, singleModule)
    } else if (singleModule) {
        _nextSnapshotSingleModule(isPre)
    } else {
        _nextSnapshotAllModules(isPre)
    }

    appendReport(isPre ?
        "\nRestored SNAPSHOT: ${env.ORIGINAL_SNAPSHOT_VERSION}" :
        "\nNext SNAPSHOT: ${env.COMPUTED_NEXT_SNAPSHOT}")
}

/** Internal: dry-run output for next snapshot. */
def _nextSnapshotDryRun(boolean isPre, boolean singleModule) {
    if (singleModule) {
        def versionProp = starterVersionProperty(params.RELEASE_TARGET)
        echo "[DRY-RUN] Would set <${versionProp}> to ${isPre ? env.ORIGINAL_SNAPSHOT_VERSION : env.COMPUTED_NEXT_SNAPSHOT}"
    } else {
        def moduleVersions = readJSON(text: env.MODULE_VERSIONS_JSON)
        echo "[DRY-RUN] Would restore per-module versions:"
        moduleVersions.each { mod, info ->
            def nextVer = isPre ? info.current : info.next
            echo "[DRY-RUN]   ${info.property}: ${info.releaseVersion} -> ${nextVer}"
        }
    }
}

/** Internal: next snapshot for single-module release. */
def _nextSnapshotSingleModule(boolean isPre) {
    def pomFile = 'pom.xml'
    def versionProp = starterVersionProperty(params.RELEASE_TARGET)
    def targetVersion = isPre ? env.ORIGINAL_SNAPSHOT_VERSION : env.COMPUTED_NEXT_SNAPSHOT

    sh "sed -i 's|<${versionProp}>${env.COMPUTED_RELEASE_VERSION}</${versionProp}>|<${versionProp}>${targetVersion}</${versionProp}>|g' ${pomFile}"

    def commitMsg = isPre ?
        "chore: restore SNAPSHOT for ${params.RELEASE_TARGET} after ${prereleaseLabel()} ${env.COMPUTED_RELEASE_VERSION}" :
        "chore: prepare next development iteration ${params.RELEASE_TARGET} ${targetVersion}"
    sh """
        git add pom.xml
        git diff --cached --quiet && echo 'Version already at target — nothing to commit' || git commit -m "${commitMsg}"
        git push origin ${env.MONOREPO_BRANCH}
    """
}

/** Internal: next snapshot for 'all' release. */
def _nextSnapshotAllModules(boolean isPre) {
    def pomFile = 'pom.xml'
    def moduleVersions = readJSON(text: env.MODULE_VERSIONS_JSON)

    def parentTarget = isPre ? env.ORIGINAL_SNAPSHOT_VERSION : env.COMPUTED_NEXT_SNAPSHOT
    sh "sed -i '/<artifactId>release-test-lutece-parent<\\/artifactId>/{n;s|<version>[^<]*</version>|<version>${parentTarget}</version>|}' ${pomFile}"

    moduleVersions.each { mod, info ->
        def nextVer = isPre ? info.current : info.next
        sh "sed -i 's|<${info.property}>${info.releaseVersion}</${info.property}>|<${info.property}>${nextVer}</${info.property}>|g' ${pomFile}"
        echo "Restored ${info.property}: ${info.releaseVersion} -> ${nextVer}"
    }

    def modules = ['release-test-lutece-bom', 'release-test-forms-starter', 'release-test-appointment-starter', 'release-test-editorial-starter', 'release-test-lutece-starter']
    modules.each { mod ->
        def modPom = "${mod}/pom.xml"
        if (fileExists(modPom)) {
            sh "sed -i '/<artifactId>release-test-lutece-parent<\\/artifactId>/{n;s|<version>[^<]*</version>|<version>${parentTarget}</version>|}' ${modPom}"
        }
    }

    def commitMsg = isPre ?
        "chore: restore SNAPSHOT after ${prereleaseLabel()}" :
        "chore: prepare next development iteration"
    sh """
        git add pom.xml */pom.xml
        git diff --cached --quiet && echo 'Versions already at target — nothing to commit' || git commit -m "${commitMsg}"
        git push origin ${env.MONOREPO_BRANCH}
    """
}

/**
 * Stage 10 — Generate and archive the release report.
 */
def stageReleaseReport() {
    appendReport("\n====================================")
    appendReport("Pipeline completed: ${new Date()}")
    appendReport("Status: ${currentBuild.result ?: 'SUCCESS'}")
    appendReport("====================================")

    def report = readFile(env.RELEASE_REPORT)
    echo report
}

/**
 * Post — Success notifications.
 */
def postSuccess() {
    try {
        def report = fileExists(env.RELEASE_REPORT) ? readFile(env.RELEASE_REPORT) : 'No report generated.'
        def typeInfo = "\nType: ${prereleaseLabel()}"
        try {
            slackSend(
                channel: '#lutece-releases',
                color: 'good',
                message: """*Lutece Release ${env.COMPUTED_RELEASE_VERSION} — SUCCESS*
Target: ${params.RELEASE_TARGET}${typeInfo}
Tags: ${env.RELEASE_TAGS ?: '(none)'}
Lutece line: V${env.LUTECE_MAJOR_RESOLVED} (JDK ${env.PLATFORM_TARGET_JDK})
Dry-Run: ${params.DRY_RUN}
${params.DRY_RUN ? '(Simulation only — no changes were pushed)' : ''}
<${env.BUILD_URL}|Build #${env.BUILD_NUMBER}>"""
            )
        } catch (Throwable e) {
            echo "Slack notification skipped: ${e.message}"
        }
        try {
            emailext(
                subject: "Lutece Release ${env.COMPUTED_RELEASE_VERSION} — SUCCESS",
                body: report,
                recipientProviders: [culprits(), requestor()]
            )
        } catch (Throwable e) {
            echo "Email notification skipped: ${e.message}"
        }
    } catch (Throwable e) {
        echo "Post-success actions skipped: ${e.message}"
    }
}

/**
 * Post — Failure notifications.
 */
def postFailure() {
    try {
        def report = fileExists(env.RELEASE_REPORT) ? readFile(env.RELEASE_REPORT) : 'No report generated — pipeline failed early.'
        try {
            slackSend(
                channel: '#lutece-releases',
                color: 'danger',
                message: """*Lutece Release ${env.COMPUTED_RELEASE_VERSION ?: 'UNKNOWN'} — FAILED*
Target: ${params.RELEASE_TARGET}
Type: ${env.PRERELEASE_TYPE ?: 'unknown'}
Tags: ${env.RELEASE_TAGS ?: '(none created)'}
Check the report for rollback actions required.
<${env.BUILD_URL}|Build #${env.BUILD_NUMBER}>"""
            )
        } catch (Throwable e) {
            echo "Slack notification skipped: ${e.message}"
        }
        try {
            emailext(
                subject: "Lutece Release ${env.COMPUTED_RELEASE_VERSION ?: 'UNKNOWN'} — FAILED",
                body: report,
                recipientProviders: [culprits(), requestor()]
            )
        } catch (Throwable e) {
            echo "Email notification skipped: ${e.message}"
        }
    } catch (Throwable e) {
        echo "Post-failure actions skipped: ${e.message}"
    }
}

// Required: return 'this' so that load() can assign the script to a variable
return this
