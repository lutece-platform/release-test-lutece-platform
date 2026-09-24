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
 * Returns the plan sent by the releaser, parsed once in stageInitialize. Plain objects (returnPojo) so that a JSON null is a real null,
 * not a JSONNull instance that Groovy would evaluate as true.
 */
def plan() {
    return readJSON(text: env.PLAN_JSON, returnPojo: true)
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
    return readJSON(file: env.STEP_REPORT, returnPojo: true)
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
 * Records a released resource in the report, as soon as it is published (tag pushed and Nexus deploy done) : a later failure (master merge,
 * next snapshot) must not hide a published version from the releaser. The aggregate goes in aggregateVersion, a component in releasedVersions.
 */
def recordReleased(resource, boolean isAggregate) {
    def report = readReport()
    if (isAggregate) {
        report.aggregateVersion = resource.targetVersion
        report.report = (report.report ?: '') + "Released aggregate ${coordinates(resource)} ${resource.targetVersion}\n"
    } else {
        report.releasedVersions[coordinates(resource)] = resource.targetVersion
        report.report = (report.report ?: '') + "Released ${coordinates(resource)} ${resource.targetVersion}\n"
    }
    writeReport(report)
}

/**
 * Restores the work tree to the last commit and removes the build outputs, so that files touched by Maven (even tracked ones, like a
 * target/ directory committed by mistake) never block a checkout nor end up in a release commit.
 */
def cleanWorkTree(String workDir) {
    dir(workDir) {
        sh 'git reset -q --hard HEAD && git clean -fdq'
    }
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
 * The token is NOT in the returned string : it holds a literal $GIT_TOKEN reference that only the shell expands, so the secret never
 * goes through Groovy string interpolation. Callers must put the URL between double quotes in the shell command.
 */
def authenticatedUrl(String scmUrl) {
    def url = scmUrl.replaceFirst('^scm:git:', '')
    if (url.contains('github.com')) {
        return url.replaceFirst('^https://', 'https://\\$GIT_TOKEN@')
    }
    return url.replaceFirst('^https://', 'https://oauth2:\\$GIT_TOKEN@')
}

/**
 * Runs the closure with the authenticated URL of a repository, the GIT_TOKEN shell variable being bound by withCredentials (masked in the logs).
 */
def withRepositoryUrl(String scmUrl, Closure body) {
    withCredentials([string(credentialsId: credentialIdFor(scmUrl), variable: 'GIT_TOKEN')]) {
        body(authenticatedUrl(scmUrl))
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
def detectTargetJdk(String workDir) {
    def raw = null
    dir(workDir) {
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
def setResourceVersion(String workDir, resource, String version) {
    dir(workDir) {
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
 * Clones a resource on its release branch into the work directory and returns the directory. The commit of the release branch at clone time
 * is kept in .git/releaser-origin-sha for the rollback, and the remote URL is reset to the plain one so that no token stays in .git/config.
 */
def cloneResource(resource) {
    def workDir = "${env.WORK_DIR}/${resource.artifactId}".toString()
    sh "rm -rf '${workDir}'"
    withRepositoryUrl(resource.scmUrl) { authUrl ->
        sh "git clone --branch '${resource.branch}' \"${authUrl}\" '${workDir}'"
    }
    dir(workDir) {
        sh "git remote set-url origin '${resource.scmUrl.replaceFirst('^scm:git:', '')}'"
        sh "git config user.email '${params.GIT_USER_EMAIL}'"
        sh "git config user.name '${params.GIT_USER_NAME}'"
        sh 'git rev-parse HEAD > .git/releaser-origin-sha'
    }
    return workDir
}

/**
 * Abbreviated commit id for the report.
 */
def shortSha(String sha) {
    return sha.length() > 7 ? sha.substring(0, 7) : sha
}

/**
 * Commit of a remote branch, or an empty string when the branch does not exist.
 */
def remoteBranchSha(String workDir, String scmUrl, String branch) {
    def sha = ''
    withRepositoryUrl(scmUrl) { authUrl ->
        dir(workDir) {
            sha = sh(script: "git ls-remote \"${authUrl}\" 'refs/heads/${branch}' | cut -f1", returnStdout: true).trim()
        }
    }
    return sha
}

/**
 * Releases a resource cloned in a directory in the order of the releaser workflow : everything Git first (release commit and tag pushed,
 * tag merged into the master branch for a stable version, next snapshot pushed), the Nexus deploy from the tag LAST. Any failure rolls the
 * repository back like the releaser does (release branch and master branch force-pushed to their commits of before the release, tag
 * deleted) : as nothing is in Nexus before the last command, the same version can be released again. The resource is recorded in the report
 * once deployed. In dry run the local part runs and the publication is only logged.
 */
def releaseResource(String workDir, resource, boolean isAggregate) {
    def tag = "${resource.artifactId}-${resource.targetVersion}".toString()
    echo "=== Releasing ${coordinates(resource)} ${resource.currentVersion} -> ${resource.targetVersion} (branch ${resource.branch}, tag ${tag}) ==="

    if (mustRunTests(resource)) {
        dir(workDir) {
            echo "Running the tests of ${resource.artifactId}"
            sh "mvn -s ${env.MAVEN_SETTINGS_XML} clean lutece:exploded antrun:run -Dlutece-test-hsql test -q"
        }
        cleanWorkTree(workDir)
    }

    setResourceVersion(workDir, resource, resource.targetVersion)
    def releaseSha = ''
    dir(workDir) {
        sh """
            git add -u
            git diff --cached --quiet && echo 'Version already at ${resource.targetVersion} — nothing to commit' || git commit -m "release: ${tag}"
            git tag -fa '${tag}' -m "Release ${resource.artifactId} ${resource.targetVersion}"
        """
        releaseSha = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
    }

    if (isDryRun()) {
        echo "[DRY-RUN] Would push ${resource.branch} and tag ${tag}"
        if (resource.masterBranch) {
            echo "[DRY-RUN] Would merge ${tag} into ${resource.masterBranch}"
        }
        echo "[DRY-RUN] Would set the next development version ${resource.nextSnapshotVersion}"
        echo "[DRY-RUN] Would deploy ${coordinates(resource)}:${resource.targetVersion} to Nexus from ${tag}"
        recordReleased(resource, isAggregate)
        return
    }

    def originSha = readFile("${workDir}/.git/releaser-origin-sha").trim()
    def originMasterSha = resource.masterBranch ? remoteBranchSha(workDir, resource.scmUrl, resource.masterBranch) : ''

    try {
        withRepositoryUrl(resource.scmUrl) { authUrl ->
            dir(workDir) {
                sh "git push \"${authUrl}\" '${resource.branch}'"
                sh "git push \"${authUrl}\" 'refs/tags/${tag}'"
            }
        }

        if (resource.masterBranch) {
            if (!originMasterSha) {
                error("Branch ${resource.masterBranch} not found on ${resource.scmUrl} : cannot merge ${tag} into it.")
            }
            withRepositoryUrl(resource.scmUrl) { authUrl ->
                dir(workDir) {
                    sh """
                        git fetch "${authUrl}" '${resource.masterBranch}:${resource.masterBranch}'
                        git checkout '${resource.masterBranch}'
                        git merge -m "Merge ${tag} into ${resource.masterBranch}" '${tag}^{commit}'
                        git push "${authUrl}" '${resource.masterBranch}'
                        git checkout '${resource.branch}'
                    """
                }
            }
        }

        if (resource.nextSnapshotVersion) {
            setResourceVersion(workDir, resource, resource.nextSnapshotVersion)
            withRepositoryUrl(resource.scmUrl) { authUrl ->
                dir(workDir) {
                    sh """
                        git add -u
                        git diff --cached --quiet && echo 'Version already at ${resource.nextSnapshotVersion} — nothing to commit' || git commit -m "chore: prepare next development iteration ${resource.artifactId}-${resource.nextSnapshotVersion}"
                        git push "${authUrl}" '${resource.branch}'
                    """
                }
            }
        }

        dir(workDir) {
            sh "git checkout -q 'refs/tags/${tag}'"
            sh "mvn -s ${env.MAVEN_SETTINGS_XML} clean deploy -DskipTests -DperformRelease=true"
        }
    } catch (Throwable e) {
        rollbackResource(workDir, resource, tag, releaseSha, originSha, originMasterSha)
        throw e
    }

    recordReleased(resource, isAggregate)
    cleanWorkTree(workDir)
    dir(workDir) {
        sh "git checkout -q '${resource.branch}'"
    }
    echo "Released ${coordinates(resource)} ${resource.targetVersion}"
}

/**
 * Rolls a failed release back, as GitResourceService.rollbackRelease does in the releaser : the release branch and the master branch are
 * force-pushed to their commits of before the release, the tag is deleted when it still points to the release commit. Every command is
 * attempted even if a previous one fails, and the outcome goes to the report.
 */
def rollbackResource(String workDir, resource, String tag, String releaseSha, String originSha, String originMasterSha) {
    echo "=== Rolling back ${coordinates(resource)} ${resource.targetVersion} ==="
    def done = []
    def failed = []
    withRepositoryUrl(resource.scmUrl) { authUrl ->
        dir(workDir) {
            if (sh(script: "git push --force \"${authUrl}\" '${originSha}:refs/heads/${resource.branch}'", returnStatus: true) == 0) {
                done << "${resource.branch} reset to ${shortSha(originSha)}"
            } else {
                failed << "${resource.branch} NOT reset to ${shortSha(originSha)}"
            }
            if (originMasterSha) {
                if (sh(script: "git push --force \"${authUrl}\" '${originMasterSha}:refs/heads/${resource.masterBranch}'", returnStatus: true) == 0) {
                    done << "${resource.masterBranch} reset to ${shortSha(originMasterSha)}"
                } else {
                    failed << "${resource.masterBranch} NOT reset to ${shortSha(originMasterSha)}"
                }
            }
            def remoteTagSha = sh(script: "git ls-remote \"${authUrl}\" 'refs/tags/${tag}^{}' | cut -f1", returnStdout: true).trim()
            if (!remoteTagSha) {
                done << "tag ${tag} not on the remote"
            } else if (remoteTagSha == releaseSha) {
                if (sh(script: "git push \"${authUrl}\" ':refs/tags/${tag}'", returnStatus: true) == 0) {
                    done << "tag ${tag} deleted"
                } else {
                    failed << "tag ${tag} NOT deleted"
                }
            } else {
                failed << "tag ${tag} kept : it does not point to the release commit"
            }
        }
    }
    def status = failed ? 'INCOMPLETE ROLLBACK, fix by hand' : 'rolled back'
    appendReport("${coordinates(resource)} ${resource.targetVersion} ${status} : ${(done + failed).join(', ')}")
}

// ========================================================================
// POM updates of the aggregate — the releaser decides, the pipeline applies
// ========================================================================

/**
 * Applies the version updates decided by the releaser to the POM of the aggregate : the parent version, the properties
 * (property name -> value) and the dependency declarations ("groupId:artifactId" -> version, the <version> tag right after the
 * <artifactId> tag). Nothing is guessed here : a version held by a property comes in "properties", never in "dependencies".
 */
def applyVersionUpdates(String workDir, aggregate) {
    dir(workDir) {
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
    def thePlan = readJSON(text: params.RELEASE_PLAN, returnPojo: true)
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
        def workDir = cloneResource(component)
        withJdk(detectTargetJdk(workDir)) {
            releaseResource(workDir, component, false)
        }
    }
}

/**
 * Stage 3 — Update the POM of the aggregate with the versions released (this step and the previous ones) and the parent version.
 * The plugins step stops here : the monorepo POM is committed and pushed, its release is the last step of the campaign.
 */
def stageUpdateAggregate() {
    def aggregate = plan().aggregate
    def workDir = cloneResource(aggregate)
    env.AGGREGATE_DIR = workDir
    applyVersionUpdates(workDir, aggregate)

    dir(workDir) {
        sh """
            git add -u
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
            dir(workDir) {
                sh "git push \"${authUrl}\" '${aggregate.branch}'"
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
    def workDir = env.AGGREGATE_DIR
    withJdk(detectTargetJdk(workDir)) {
        releaseResource(workDir, aggregate, true)
    }
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
