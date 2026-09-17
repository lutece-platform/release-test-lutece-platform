/**
 * Lutece Platform — Step Pipeline — Helper Functions
 *
 * Stage bodies of Jenkinsfile-platform-step, extracted to stay under the
 * "Method too large" limit of the Jenkins CPS engine.
 *
 * Loaded with : def helpers = load('platform-step-helpers.groovy')
 */

// ========================================================================
// Plan and report
// ========================================================================

/**
 * Returns the plan sent by the releaser, parsed once in stageInitialize.
 */
def plan() {
    return readJSON(text: env.PLAN_JSON)
}

/**
 * Maven coordinates "groupId:artifactId" of a plan resource, the key of the report and of the version updates.
 */
def coordinates(resource) {
    return "${resource.groupId}:${resource.artifactId}".toString()
}

/**
 * Whether the plan asks for a simulation : nothing is pushed, deployed or merged.
 */
def isDryRun() {
    return env.DRY_RUN == 'true'
}

/**
 * Whether the plan carries an aggregate to update.
 */
def hasAggregate() {
    return plan().aggregate != null
}

/**
 * Whether the aggregate itself is released by this pipeline. The lutece-platform monorepo (plugins step) only gets its POM updated :
 * its starters and BOM are released by Jenkinsfile-release at the last step of the campaign.
 */
def isAggregateReleased() {
    return hasAggregate() && plan().stepCode != 'PLATFORM_PLUGINS'
}

/**
 * Reads the current report.
 */
def readReport() {
    return readJSON(file: env.STEP_REPORT)
}

/**
 * Writes and archives the report. Archiving after every write is what lets the releaser read a partial report after a failure.
 */
def writeReport(report) {
    writeJSON file: env.STEP_REPORT, json: report, pretty: 2
    archiveArtifacts artifacts: 'step-report.json', fingerprint: true
}

/**
 * Appends a line to the free text part of the report.
 */
def appendReport(String line) {
    def report = readReport()
    report.report = (report.report ?: '') + line + '\n'
    writeReport(report)
    echo line
}

/**
 * Records a released resource in the report.
 */
def recordReleased(resource) {
    def report = readReport()
    report.releasedVersions[coordinates(resource)] = resource.targetVersion
    report.report = (report.report ?: '') + "Released ${coordinates(resource)} ${resource.targetVersion}\n"
    writeReport(report)
}

// ========================================================================
// Git credentials — GitHub and GitLab
// ========================================================================

/**
 * Credential id matching the host of a repository URL.
 */
def credentialIdFor(String scmUrl) {
    return scmUrl.contains('github.com') ? params.GITHUB_CREDENTIAL_ID : params.GITLAB_CREDENTIAL_ID
}

/**
 * Authenticated clone/push URL : GitHub takes the token as user, GitLab takes it as the password of the oauth2 user.
 */
def authenticatedUrl(String scmUrl, String token) {
    def url = scmUrl.replaceFirst('^scm:git:', '')
    if (url.contains('github.com')) {
        return url.replaceFirst('^https://', "https://${token}@")
    }
    return url.replaceFirst('^https://', "https://oauth2:${token}@")
}

/**
 * Runs the closure with the authenticated URL of a repository (the token is masked in the logs by withCredentials).
 */
def withRepositoryUrl(String scmUrl, Closure body) {
    withCredentials([string(credentialsId: credentialIdFor(scmUrl), variable: 'GIT_TOKEN')]) {
        body(authenticatedUrl(scmUrl, env.GIT_TOKEN))
    }
}

// ========================================================================
// JDK selection — same rules as Jenkinsfile-release
// ========================================================================

/**
 * Normalizes a targetJdk value to a plain major number : '1.8' -> '8', '17' -> '17'.
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
 * Resolves the effective targetJdk of the POM in a directory, falling back on the core version of the campaign (core 7 -> JDK 11, else 17).
 */
def detectTargetJdk(String dir) {
    def raw = null
    dir(dir) {
        try {
            raw = sh(
                script: "mvn -s ${env.MAVEN_SETTINGS_XML} -N -q help:evaluate -Dexpression=targetJdk -DforceStdout 2>/dev/null | tail -1",
                returnStdout: true
            ).trim()
        } catch (Throwable e) {
            echo "WARNING: could not evaluate targetJdk with Maven: ${e.message}"
        }
    }
    def major = normalizeJdkMajor(raw)
    if (major) {
        return major
    }
    def fallback = plan().coreMajor == 7 ? '11' : '17'
    echo "WARNING: targetJdk could not be evaluated (got: '${raw}') — falling back to JDK ${fallback}"
    return fallback
}

/**
 * Maps a JDK major number to a Jenkins JDK tool name (convention temurin-{major}-jdk, overridable with JDK_TOOL_MAP).
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
 * Runs the closure with JAVA_HOME pointing at the Jenkins JDK tool of the requested major, or the build default JDK with a warning.
 */
def withJdk(String major, Closure body) {
    def toolName = jdkToolName(major)
    def jdkHome = null
    try {
        jdkHome = tool(name: toolName, type: 'jdk')
    } catch (Throwable e) {
        echo "WARNING: Jenkins JDK tool '${toolName}' is not configured (${e.message}) -> using the build default JDK"
        body()
        return
    }
    echo "Using JDK ${major} -> tool '${toolName}' (${jdkHome})"
    withEnv(["JAVA_HOME=${jdkHome}", "PATH+JDK=${jdkHome}/bin"]) {
        body()
    }
}

// ========================================================================
// Versioned files of a Lutece resource — same files as the releaser workflow
// ========================================================================

/**
 * Sets the version of a resource everywhere the releaser does : the POM (versions:set), the plugin descriptors
 * webapp/WEB-INF/plugins/*.xml, and for lutece-core the descriptor webapp/WEB-INF/conf/core.xml and AppInfo.java.
 */
def setResourceVersion(String dir, resource, String version) {
    dir(dir) {
        sh "mvn -s ${env.MAVEN_SETTINGS_XML} -q versions:set -DnewVersion=${version} -DgenerateBackupPoms=false"
        sh """
            for xmlFile in webapp/WEB-INF/plugins/*.xml; do
                [ -f "\$xmlFile" ] || continue
                sed -i '0,/<version>[^<]*<\\/version>/s||<version>${version}</version>|' "\$xmlFile"
            done
            if [ -f webapp/WEB-INF/conf/core.xml ]; then
                sed -i '0,/<version>[^<]*<\\/version>/s||<version>${version}</version>|' webapp/WEB-INF/conf/core.xml
            fi
            if [ -f src/java/fr/paris/lutece/portal/service/init/AppInfo.java ]; then
                sed -i -E '/VERSION[[:space:]]*=/ s|"[^"]*"|"${version}"|' src/java/fr/paris/lutece/portal/service/init/AppInfo.java
            fi
        """
    }
}

/**
 * Whether the tests must run before releasing a resource : only Lutece plugins and the core, when RUN_TESTS is set.
 */
def mustRunTests(resource) {
    return params.RUN_TESTS && (resource.type == 'lutece-plugin' || resource.type == 'lutece-core')
}

// ========================================================================
// Release of one resource (component or aggregate)
// ========================================================================

/**
 * Clones a resource on its release branch into the work directory and returns the directory.
 */
def cloneResource(resource) {
    def dir = "${env.WORK_DIR}/${resource.artifactId}"
    sh "rm -rf '${dir}'"
    withRepositoryUrl(resource.scmUrl) { authUrl ->
        sh "git clone --branch '${resource.branch}' '${authUrl}' '${dir}'"
    }
    dir(dir) {
        sh "git config user.email '${params.GIT_USER_EMAIL}'"
        sh "git config user.name '${params.GIT_USER_NAME}'"
    }
    return dir
}

/**
 * Releases a resource cloned in a directory, following RELEASE.md : tests, release version, commit and tag (all local), then in one go
 * push, Nexus deploy, master merge (stable only) and next snapshot. In dry run the local part runs and the publication is only logged.
 */
def releaseResource(String dir, resource) {
    def tag = "${resource.artifactId}-${resource.targetVersion}".toString()
    echo "=== Releasing ${coordinates(resource)} ${resource.currentVersion} -> ${resource.targetVersion} (branch ${resource.branch}, tag ${tag}) ==="

    dir(dir) {
        if (mustRunTests(resource)) {
            echo "Running the tests of ${resource.artifactId}"
            sh "mvn -s ${env.MAVEN_SETTINGS_XML} clean lutece:exploded antrun:run -Dlutece-test-hsql test -q"
        }
    }

    setResourceVersion(dir, resource, resource.targetVersion)
    dir(dir) {
        sh """
            git add -A
            git diff --cached --quiet && echo 'Version already at ${resource.targetVersion} — nothing to commit' || git commit -m "release: ${tag}"
            git tag -fa '${tag}' -m "Release ${resource.artifactId} ${resource.targetVersion}"
        """
    }

    if (isDryRun()) {
        echo "[DRY-RUN] Would push ${resource.branch} and tag ${tag}"
        echo "[DRY-RUN] Would deploy ${coordinates(resource)}:${resource.targetVersion} to Nexus"
        if (resource.masterBranch) {
            echo "[DRY-RUN] Would merge ${resource.branch} into ${resource.masterBranch}"
        }
        echo "[DRY-RUN] Would set the next development version ${resource.nextSnapshotVersion}"
        return
    }

    withRepositoryUrl(resource.scmUrl) { authUrl ->
        dir(dir) {
            sh "git push '${authUrl}' '${resource.branch}'"
            sh "git push '${authUrl}' 'refs/tags/${tag}'"
        }
    }

    dir(dir) {
        sh "mvn -s ${env.MAVEN_SETTINGS_XML} clean deploy -DskipTests -DperformRelease=true"
    }

    withRepositoryUrl(resource.scmUrl) { authUrl ->
        dir(dir) {
            if (resource.masterBranch) {
                sh """
                    git fetch '${authUrl}' '${resource.masterBranch}:${resource.masterBranch}' || git branch '${resource.masterBranch}' 'refs/remotes/origin/${resource.masterBranch}'
                    git checkout '${resource.masterBranch}'
                    git merge '${resource.branch}' -m "Merge ${resource.branch} for release ${tag}"
                    git push '${authUrl}' '${resource.masterBranch}'
                    git checkout '${resource.branch}'
                """
            }
        }
    }

    if (resource.nextSnapshotVersion) {
        setResourceVersion(dir, resource, resource.nextSnapshotVersion)
        withRepositoryUrl(resource.scmUrl) { authUrl ->
            dir(dir) {
                sh """
                    git add -A
                    git diff --cached --quiet && echo 'Version already at ${resource.nextSnapshotVersion} — nothing to commit' || git commit -m "chore: prepare next development iteration ${resource.artifactId}-${resource.nextSnapshotVersion}"
                    git push '${authUrl}' '${resource.branch}'
                """
            }
        }
    }

    echo "Released ${coordinates(resource)} ${resource.targetVersion}"
}

// ========================================================================
// POM updates of the aggregate — the releaser decides, the pipeline applies
// ========================================================================

/**
 * Applies the version updates decided by the releaser to the POM of the aggregate : the parent version, the properties
 * (property name -> value) and the dependency declarations ("groupId:artifactId" -> version, the <version> tag right after the
 * <artifactId> tag). Nothing is guessed here : a version held by a property comes in "properties", never in "dependencies".
 */
def applyVersionUpdates(String dir, aggregate) {
    dir(dir) {
        if (aggregate.parentVersion) {
            sh "sed -i '/<parent>/,/<\\/parent>/ s|<version>[^<]*</version>|<version>${aggregate.parentVersion}</version>|' pom.xml"
            echo "Parent version -> ${aggregate.parentVersion}"
        }
        def updates = aggregate.versionUpdates ?: [:]
        (updates.properties ?: [:]).each { name, version ->
            sh "sed -i 's|<${name}>[^<]*</${name}>|<${name}>${version}</${name}>|g' pom.xml"
            echo "Property ${name} -> ${version}"
        }
        (updates.dependencies ?: [:]).each { coords, version ->
            def artifactId = coords.split(':')[1]
            sh "sed -i '/<artifactId>${artifactId}<\\/artifactId>/{n;s|<version>[^<]*</version>|<version>${version}</version>|}' pom.xml"
            echo "Dependency ${coords} -> ${version}"
        }
    }
}

// ========================================================================
// Stage bodies
// ========================================================================

/**
 * Stage 1 — Initialize : parse the plan, provision the Maven settings, create the empty report.
 */
def stageInitialize() {
    if (!params.RELEASE_PLAN?.trim()) {
        error('RELEASE_PLAN is empty : this job is meant to be triggered by the releaser with the plan of a step.')
    }
    def thePlan = readJSON(text: params.RELEASE_PLAN)
    if (!thePlan.stepCode || thePlan.components == null) {
        error('RELEASE_PLAN is not a step plan : stepCode and components are required.')
    }
    env.PLAN_JSON = params.RELEASE_PLAN
    env.DRY_RUN = thePlan.dryRun ? 'true' : 'false'

    configFileProvider([configFile(fileId: params.MAVEN_SETTINGS_ID, variable: 'MVN_SETTINGS_TMP')]) {
        sh "cp \${MVN_SETTINGS_TMP} ${WORKSPACE}/maven-settings.xml"
    }
    env.MAVEN_SETTINGS_XML = "${WORKSPACE}/maven-settings.xml"

    sh "rm -rf '${env.WORK_DIR}' && mkdir -p '${env.WORK_DIR}'"
    writeReport([releasedVersions: [:], aggregateVersion: null, report: ''])

    echo "=========================================="
    echo " Lutece Platform Step Pipeline"
    echo " Campaign    : #${thePlan.campaignId} ${thePlan.campaignName}"
    echo " Step        : ${thePlan.step} ${thePlan.stepCode}"
    echo " Type        : ${thePlan.releaseType} (core ${thePlan.coreMajor})"
    echo " Dry-Run     : ${env.DRY_RUN}"
    echo " Components  : ${thePlan.components.size()}"
    echo " Aggregate   : ${thePlan.aggregate ? coordinates(thePlan.aggregate) + ' ' + thePlan.aggregate.currentVersion + ' -> ' + thePlan.aggregate.targetVersion : '(none)'}"
    echo "=========================================="
    appendReport("Step ${thePlan.step} ${thePlan.stepCode} of campaign #${thePlan.campaignId} ${thePlan.campaignName} — ${thePlan.releaseType} — dry run : ${env.DRY_RUN}")
}

/**
 * Stage 2 — Release the components in the order received, fail-fast, the report growing after each one.
 */
def stageReleaseComponents() {
    def components = plan().components
    if (components.isEmpty()) {
        appendReport('No component to release in this step.')
        return
    }
    components.each { component ->
        def dir = cloneResource(component)
        withJdk(detectTargetJdk(dir)) {
            releaseResource(dir, component)
        }
        recordReleased(component)
    }
}

/**
 * Stage 3 — Update the POM of the aggregate with the versions released (this step and the previous ones) and the parent version.
 * The plugins step stops here : the monorepo POM is committed and pushed, its release is the last step of the campaign.
 */
def stageUpdateAggregate() {
    def aggregate = plan().aggregate
    def dir = cloneResource(aggregate)
    env.AGGREGATE_DIR = dir
    applyVersionUpdates(dir, aggregate)

    dir(dir) {
        sh """
            git add -A
            git diff --cached --quiet && echo 'Aggregate POM already up to date — nothing to commit' || git commit -m "chore: update versions for the release of ${aggregate.artifactId} ${aggregate.targetVersion}"
        """
    }

    if (isAggregateReleased()) {
        return
    }
    if (isDryRun()) {
        echo "[DRY-RUN] Would push the updated POM of ${aggregate.artifactId} on ${aggregate.branch}"
    } else {
        withRepositoryUrl(aggregate.scmUrl) { authUrl ->
            dir(dir) {
                sh "git push '${authUrl}' '${aggregate.branch}'"
            }
        }
    }
    appendReport("Aggregate ${coordinates(aggregate)} : POM updated on ${aggregate.branch}, not released by this step.")
}

/**
 * Stage 4 — Release the aggregate (global-pom, site-pom, core) once its POM references the released components.
 */
def stageReleaseAggregate() {
    def aggregate = plan().aggregate
    def dir = env.AGGREGATE_DIR
    withJdk(detectTargetJdk(dir)) {
        releaseResource(dir, aggregate)
    }
    def report = readReport()
    report.aggregateVersion = aggregate.targetVersion
    report.report = (report.report ?: '') + "Released aggregate ${coordinates(aggregate)} ${aggregate.targetVersion}\n"
    writeReport(report)
}

/**
 * Stage 5 — Final report.
 */
def stageReport() {
    appendReport("Pipeline completed : ${new Date()} — status ${currentBuild.result ?: 'SUCCESS'}")
    echo readFile(env.STEP_REPORT)
}

/**
 * Post — Failure : the partial report already lists what was released ; say so.
 */
def postFailure() {
    try {
        appendReport("FAILED at ${new Date()} : the resources listed in releasedVersions were released, the others were not. Prepare the step again in the releaser to release the remaining ones.")
    } catch (Throwable e) {
        echo "Could not complete the report : ${e.message}"
    }
}

// Required : return 'this' so that load() can assign the script to a variable
return this
