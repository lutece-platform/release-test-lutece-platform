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
 * Whether the plan asks for a verification : every check that needs a build runs on each resource, nothing is committed nor pushed.
 */
def isVerify() {
    return env.STEP_MODE == 'VERIFY'
}

/**
 * Mode of the plan : RELEASE, DRY_RUN or VERIFY. A plan without mode (releaser before the VERIFY mode) is read from its dryRun flag.
 */
def planMode(thePlan) {
    def mode = (thePlan.mode ?: (thePlan.dryRun ? 'DRY_RUN' : 'RELEASE')).toString().toUpperCase()
    if (!(mode in ['RELEASE', 'DRY_RUN', 'VERIFY'])) {
        error("Unknown plan mode '${mode}' : RELEASE, DRY_RUN or VERIFY expected.")
    }
    return mode
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
    // archiveArtifacts resolves its pattern from the current dir() : called from inside a resource directory it would find nothing
    dir(env.WORKSPACE) {
        archiveArtifacts artifacts: 'step-report.json', fingerprint: true
    }
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
 * Records a component whose release failed. The release of the other components goes on : the releaser shows this one as failed and lets
 * the person fix it and launch the step again. The first line of the error is the reason, the next ones (error lines of the failed command)
 * go under it in the report ; whether a rollback was needed is reported by rollbackResource.
 */
def recordFailed(resource, String error) {
    def lines = error.readLines()
    def report = readReport()
    if (report.failedVersions == null) {
        report.failedVersions = [:]
    }
    report.failedVersions[coordinates(resource)] = lines[0]
    report.report = (report.report ?: '') + "FAILED ${coordinates(resource)} ${resource.targetVersion} : ${lines[0]}\n" + formatDetails(lines)
    writeReport(report)
}

/**
 * Records a failure of the aggregate, during the update of its POM or its release, with the error lines of the failed command : without it
 * the report would only hold the generic line of postFailure.
 */
def reportAggregateFailure(aggregate, String phase, Throwable e) {
    def lines = (e.message ?: e.class.simpleName).toString().readLines()
    appendReport("FAILED aggregate ${coordinates(aggregate)} ${aggregate.targetVersion} during the ${phase}${isDryRun() ? ' [DRY-RUN]' : ''} : ${lines[0]}\n" + formatDetails(lines).trim())
}

/**
 * Indents the lines of an error that follow its first one, for the report.
 */
def formatDetails(List lines) {
    return lines.drop(1).findAll { it.trim() }.collect { "    | ${it}\n" }.join('')
}

/**
 * Runs a shell command of a resource in the current directory : its output goes to the console and to work/logs/<artifactId>.log, archived
 * with the build. On failure the error holds a summary line, then the useful lines of the output (Maven [ERROR], git fatal or error, push
 * rejected) : the report shows the cause, not only "script returned exit code 1". The command runs in its own shell with -e, so that a
 * command of several lines stops at its first failure.
 */
def runLogged(resource, String label, String command) {
    def logDir = "${env.WORK_DIR}/logs"
    def base = "${logDir}/${resource.artifactId}"
    sh "mkdir -p '${logDir}'"
    writeFile file: "${base}.cmd.sh", text: command
    sh """
        echo '>>> ${label}' >> '${base}.log'
        { sh -e '${base}.cmd.sh' 2>&1 && echo 0 > '${base}.rc' || echo \$? > '${base}.rc'; } | tee '${base}.out'
        cat '${base}.out' >> '${base}.log'
    """
    def status = readFile("${base}.rc").trim()
    if (status != '0') {
        error("${label} failed (exit code ${status})\n${errorExcerpt("${base}.out")}")
    }
}

/**
 * The useful lines of a failed command output, at most 15 : Maven [ERROR] lines without its generic help lines, git fatal and error lines,
 * rejected pushes.
 */
def errorExcerpt(String outFile) {
    def excerpt = sh(script: """
        grep -E '^\\[ERROR\\]|^(fatal|error|ERROR):|^ ! \\[' '${outFile}' \\
          | grep -v -E '^\\[ERROR\\] *\$|-> \\[Help|^\\[ERROR\\] \\[Help|To see the full stack trace|Re-run Maven using|For more information about the errors' \\
          | cut -c1-300 | head -15 || true
    """, returnStdout: true).trim()
    return excerpt ?: '(no error line found in the output : see work/logs in the build artifacts)'
}

/**
 * Whether a component of this step failed.
 */
def hasFailures() {
    def failed = readReport().failedVersions
    return failed != null && !failed.isEmpty()
}

/**
 * Whether a component of the plan was published by this build.
 */
def isReleased(resource) {
    def released = readReport().releasedVersions ?: [:]
    return released.containsKey(coordinates(resource))
}

/**
 * Records that the POM of the aggregate was pushed with the released versions.
 */
def markPomUpdated() {
    def report = readReport()
    report.pomUpdated = true
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
 * Login and token of the person releasing for the host of a repository URL, GitHub or GitLab : the releaser sends them as build parameters,
 * a manual build types them. No credential is stored in Jenkins.
 */
def credentialsFor(String scmUrl) {
    def github = scmUrl.contains('github.com')
    return [host : github ? 'GitHub' : 'GitLab',
            login: github ? params.GITHUB_LOGIN : params.GITLAB_LOGIN,
            token: (github ? params.GITHUB_TOKEN : params.GITLAB_TOKEN)?.toString()]
}

/**
 * Writes the script git calls for a username or a password : it answers with the GITHUB_* or GITLAB_* build parameters, read from the
 * environment, according to the host named in the prompt. The token thus never appears in a command line, a URL or the clone
 * configuration ; GIT_ASKPASS points to this script and GIT_TERMINAL_PROMPT=0 forbids any interactive prompt.
 */
def writeAskpassScript() {
    writeFile file: env.GIT_ASKPASS, text: '''#!/bin/sh
case "$1" in
  *github.com*) [ "${1#Username}" != "$1" ] && printf '%s\\n' "$GITHUB_LOGIN" || printf '%s\\n' "$GITHUB_TOKEN" ;;
  *)            [ "${1#Username}" != "$1" ] && printf '%s\\n' "$GITLAB_LOGIN" || printf '%s\\n' "$GITLAB_TOKEN" ;;
esac
'''
    sh "chmod 700 '${env.GIT_ASKPASS}'"
}

/**
 * Fails before any clone when the plan references a host for which no login or token was received.
 */
def checkCredentials(thePlan) {
    def resources = (thePlan.components ?: []) + (thePlan.aggregate ? [thePlan.aggregate] : [])
    def missing = resources.findAll { r ->
        def c = credentialsFor(r.scmUrl ?: '')
        !c.login?.trim() || !c.token?.trim()
    }.collect { r -> "${credentialsFor(r.scmUrl ?: '').host} (${r.artifactId})".toString() }.unique()
    if (missing) {
        error("Missing login or token for ${missing.join(', ')} : the releaser sends GITHUB_LOGIN/GITHUB_TOKEN and GITLAB_LOGIN/GITLAB_TOKEN from the credentials of the person releasing.")
    }
}

/**
 * Runs the closure with the plain URL of a repository : git authenticates through the askpass script, see writeAskpassScript.
 */
def withRepositoryUrl(String scmUrl, Closure body) {
    body(scmUrl.replaceFirst('^scm:git:', ''))
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
 * Sets the version of the parent POM of a resource, as chosen in the releaser : the same edit as the one applied to the aggregate.
 */
def setParentVersion(String workDir, String parentVersion) {
    dir(workDir) {
        sh "sed -i '/<parent>/,/<\\/parent>/ s|<version>[^<]*</version>|<version>${parentVersion}</version>|' pom.xml"
    }
    echo "Parent version -> ${parentVersion}"
}

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

/**
 * The Nexus repository a resource goes to, for the logs : the private one of the plan for GitLab, the one of the POM for GitHub.
 */
def deployTarget(resource) {
    if (resource.deployRepository) {
        return 'private repository'
    }
    return resource.scmUrl.contains('github.com') ? 'public repository' : 'private repository NOT CONFIGURED'
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
        runLogged(resource, "Clone of ${resource.branch}", "git clone --branch '${resource.branch}' \"${authUrl}\" '${workDir}'")
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
 * Refuses to release a resource whose POM, versions set, still declares SNAPSHOT artifacts : a SNAPSHOT parent, or a dependency whose
 * declared version (properties resolved, every module of a multi-module build) is a SNAPSHOT. Declared versions, not resolved ones : a
 * Lutece range such as [7.0.0,8.0.0) may resolve to a SNAPSHOT of the core without the POM declaring one, exactly as the maven-release-plugin
 * of the classic release judges it. Runs before any commit, so nothing is to roll back.
 */
def checkSnapshotDependencies(String workDir, resource) {
    def problems = snapshotProblems(workDir)
    if (!problems.isEmpty()) {
        appendReport("${coordinates(resource)} : release refused, the POM rests on SNAPSHOT artifacts : ${problems.join(' ; ')}")
        error("${coordinates(resource)} rests on SNAPSHOT artifacts : ${problems.join(' ; ')}")
    }
}

/**
 * The SNAPSHOT artifacts the POM in a directory declares : its parent, and the dependencies whose declared version is a SNAPSHOT
 * ("parent x.y.z-SNAPSHOT", "groupId:artifactId:version"). Empty when the POM declares none.
 */
def snapshotProblems(String workDir) {
    def problems = []
    dir(workDir) {
        def parentVersion = sh(script: "mvn -s ${env.MAVEN_SETTINGS_XML} -N -q help:evaluate -Dexpression=project.parent.version -DforceStdout 2>/dev/null | tail -1 || true", returnStdout: true).trim()
        def declared = sh(script: "mvn -s ${env.MAVEN_SETTINGS_XML} -q help:evaluate -Dexpression=project.dependencies -DforceStdout 2>/dev/null || true", returnStdout: true)
        def snapshots = [] as Set
        def current = [:]
        declared.readLines().each { line ->
            def m = (line =~ /<(groupId|artifactId|version)>([^<]*)<\/\1>/)
            if (m.find()) {
                current[m.group(1)] = m.group(2)
                if (m.group(1) == 'version' && m.group(2).endsWith('-SNAPSHOT')) {
                    snapshots.add("${current.groupId}:${current.artifactId}:${m.group(2)}".toString())
                }
            }
        }
        if (parentVersion.endsWith('-SNAPSHOT')) {
            problems.add("parent ${parentVersion}".toString())
        }
        problems.addAll(snapshots)
    }
    return problems
}

/**
 * Runs the tests of a resource and fails with the failed test classes and the first failure lines. The work tree is then restored.
 */
def runTests(String workDir, resource) {
    dir(workDir) {
        echo "Running the tests of ${resource.artifactId}"
        runLogged(resource, 'Tests (mvn test)', "mvn -s ${env.MAVEN_SETTINGS_XML} clean lutece:exploded antrun:run -Dlutece-test-hsql test -q")
        // lutece-global-pom sets testFailureIgnore=true : Maven exits 0 whatever the tests say, the JUnit reports (TEST-*.xml) are the
        // only truth. antrun_report.xml, written by the Lutece database setup, is not a test report.
        def failedReports = sh(script: "grep -l -E '<(failure|error)[ >]' target/surefire-reports/TEST-*.xml 2>/dev/null || true", returnStdout: true).trim()
        if (failedReports) {
            def failedClasses = failedReports.readLines().collect { it.replaceAll('.*/TEST-', '').replaceAll('\\.xml$', '') }
            def failures = sh(script: "grep -h -E -o '<(failure|error)[^>]*' target/surefire-reports/TEST-*.xml | cut -c1-300 | head -15 || true", returnStdout: true).trim()
            echo failures
            error("${failedClasses.size()} test class(es) failed in ${resource.artifactId} : ${failedClasses.join(', ')}\n${failures}")
        }
    }
    cleanWorkTree(workDir)
}

/**
 * Compiles the main and test sources of the release commit, every type of resource, before anything is pushed : a resource that does
 * not compile with its release versions and parent fails here, with nothing to roll back, instead of failing at the Nexus deploy after
 * the pushes. The work tree is then restored to the release commit, so that no file touched by the build ends up in a later commit.
 */
def checkCompiles(String workDir, resource) {
    dir(workDir) {
        echo "Compiling ${resource.artifactId} ${resource.targetVersion} before any push"
        runLogged(resource, 'Compilation of the release commit, nothing pushed (mvn test-compile)', "mvn -s ${env.MAVEN_SETTINGS_XML} -q clean test-compile")
    }
    cleanWorkTree(workDir)
}

/**
 * Releases a resource cloned in a directory in the order of the releaser workflow : everything Git first (release commit and tag pushed,
 * tag merged into the master branch for a stable version, next snapshot pushed), the Nexus deploy from the tag LAST. Any failure rolls the
 * repository back like the releaser does (release branch and master branch force-pushed to their commits of before the release, tag
 * deleted) : as nothing is in Nexus before the last command, the same version can be released again. The release commit is compiled
 * before any push. The resource is recorded in the report once deployed. In dry run the local part runs, compilation included, and the
 * publication is only logged.
 */
def releaseResource(String workDir, resource, boolean isAggregate) {
    def tag = "${resource.artifactId}-${resource.targetVersion}".toString()
    echo "=== Releasing ${coordinates(resource)} ${resource.currentVersion} -> ${resource.targetVersion} (branch ${resource.branch}, tag ${tag}) ==="

    if (!isDryRun() && !resource.deployRepository && !resource.scmUrl.contains('github.com')) {
        error("${coordinates(resource)} : no private Nexus repository in the plan for a GitLab resource, release refused before any change.")
    }
    if (mustRunTests(resource)) {
        def headSha = sh(script: "git -C '${workDir}' rev-parse HEAD", returnStdout: true).trim()
        if (resource.verifiedCommit && resource.verifiedCommit == headSha) {
            appendReport("${coordinates(resource)} : tests skipped, commit ${shortSha(headSha)} already tested by the verification")
        } else {
            runTests(workDir, resource)
        }
    }

    if (resource.parentVersion && !isAggregate) {
        setParentVersion(workDir, resource.parentVersion)
        appendReport("${coordinates(resource)} : parent POM set to ${resource.parentVersion} before the release")
    }
    setResourceVersion(workDir, resource, resource.targetVersion)
    checkSnapshotDependencies(workDir, resource)
    def releaseSha = ''
    dir(workDir) {
        sh """
            git add -u
            git diff --cached --quiet && echo 'Version already at ${resource.targetVersion} — nothing to commit' || git commit -m "release: ${tag}"
            git tag -fa '${tag}' -m "Release ${resource.artifactId} ${resource.targetVersion}"
        """
        releaseSha = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
    }
    checkCompiles(workDir, resource)

    if (isDryRun()) {
        echo "[DRY-RUN] Would push ${resource.branch} and tag ${tag}"
        if (resource.masterBranch) {
            echo "[DRY-RUN] Would merge ${tag} into ${resource.masterBranch}"
        }
        echo "[DRY-RUN] Would set the next development version ${resource.nextSnapshotVersion}"
        echo "[DRY-RUN] Would deploy ${coordinates(resource)}:${resource.targetVersion} to Nexus (${deployTarget(resource)}) from ${tag}"
        recordReleased(resource, isAggregate)
        return
    }

    def originSha = readFile("${workDir}/.git/releaser-origin-sha").trim()
    def originMasterSha = resource.masterBranch ? remoteBranchSha(workDir, resource.scmUrl, resource.masterBranch) : ''

    try {
        withRepositoryUrl(resource.scmUrl) { authUrl ->
            dir(workDir) {
                runLogged(resource, "Push of ${resource.branch} and tag ${tag}", """
                    git push "${authUrl}" '${resource.branch}'
                    git push "${authUrl}" 'refs/tags/${tag}'
                """)
            }
        }

        if (resource.masterBranch) {
            if (!originMasterSha) {
                error("Branch ${resource.masterBranch} not found on ${resource.scmUrl} : cannot merge ${tag} into it.")
            }
            withRepositoryUrl(resource.scmUrl) { authUrl ->
                dir(workDir) {
                    runLogged(resource, "Merge of ${tag} into ${resource.masterBranch}", """
                        git fetch "${authUrl}" '${resource.masterBranch}:${resource.masterBranch}'
                        git checkout '${resource.masterBranch}'
                        git merge -m "Merge ${tag} into ${resource.masterBranch}" '${tag}^{commit}'
                        git push "${authUrl}" '${resource.masterBranch}'
                        git checkout '${resource.branch}'
                    """)
                }
            }
        }

        if (resource.nextSnapshotVersion) {
            setResourceVersion(workDir, resource, resource.nextSnapshotVersion)
            withRepositoryUrl(resource.scmUrl) { authUrl ->
                dir(workDir) {
                    runLogged(resource, "Next development version ${resource.nextSnapshotVersion}", """
                        git add -u
                        git diff --cached --quiet && echo 'Version already at ${resource.nextSnapshotVersion} — nothing to commit' || git commit -m "chore: prepare next development iteration ${resource.artifactId}-${resource.nextSnapshotVersion}"
                        git push "${authUrl}" '${resource.branch}'
                    """)
                }
            }
        }

        dir(workDir) {
            sh "git checkout -q 'refs/tags/${tag}'"
            def altRepository = resource.deployRepository ? " -DaltReleaseDeploymentRepository='${resource.deployRepository}'" : ''
            runLogged(resource, "Nexus deploy from ${tag} (${deployTarget(resource)})", "mvn -s ${env.MAVEN_SETTINGS_XML} clean deploy -DskipTests -DperformRelease=true${altRepository}")
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
// Verification of the resources — mode VERIFY, nothing committed nor pushed
// ========================================================================

/**
 * Writes and archives the verification report, after every verified resource like the step report.
 */
def writeVerifyReport(report) {
    writeJSON file: env.VERIFY_REPORT, json: report, pretty: 2
    dir(env.WORKSPACE) {
        archiveArtifacts artifacts: 'verify-report.json', fingerprint: true
    }
}

/**
 * Runs one check and returns its result : OK, or the given failure status (TO_FIX or WARNING) with the error lines. A failed check never
 * stops the verification of the other checks nor of the other resources.
 */
def runCheck(String check, String failureStatus, Closure body) {
    try {
        body()
        return [check: check, status: 'OK', details: '']
    } catch (Throwable e) {
        echo "Check '${check}' : ${failureStatus} — ${e.message}"
        return [check: check, status: failureStatus, details: (e.message ?: e.class.simpleName).toString()]
    }
}

/**
 * Fails when the POM declares SNAPSHOT artifacts, as listed by snapshotProblems.
 */
def checkNoDirectSnapshot(List problems) {
    if (!problems.isEmpty()) {
        error("The POM rests on SNAPSHOT artifacts : ${problems.join(' ; ')}")
    }
}

/**
 * Fails when the resolved dependency tree holds SNAPSHOT artifacts the POM does not declare itself : a direct dependency whose version
 * range resolved to a SNAPSHOT (a Lutece range such as [7.0.0,8.0.0) may resolve to a SNAPSHOT of the core), or a dependency of a
 * dependency. Only a warning.
 */
def checkNoTransitiveSnapshot(String workDir, resource, List directProblems) {
    def direct = directProblems as Set
    def treeFile = "${workDir}/target/releaser-dependency-tree.txt"
    dir(workDir) {
        runLogged(resource, 'Dependency tree (mvn dependency:tree)', "mvn -s ${env.MAVEN_SETTINGS_XML} -q dependency:tree -DoutputFile='${treeFile}' -DappendOutput=true")
    }
    def ranged = [] as Set
    def found = [] as Set
    readFile(treeFile).readLines().each { line ->
        def m = (line =~ /^([| ]*)[+\\]- ([^:\s]+):([^:\s]+):[^\s]*?([^:\s]+-SNAPSHOT)(:[a-z]+)?/)
        if (m.find()) {
            def gav = "${m.group(2)}:${m.group(3)}:${m.group(4)}".toString()
            if (!direct.contains(gav)) {
                (m.group(1).isEmpty() ? ranged : found).add(gav)
            }
        }
    }
    def messages = []
    if (!ranged.isEmpty()) {
        messages.add("Version ranges of the POM resolved as SNAPSHOT : ${ranged.join(' ; ')}")
    }
    if (!found.isEmpty()) {
        messages.add("SNAPSHOT artifacts reached through other dependencies : ${found.join(' ; ')}")
    }
    if (!messages.isEmpty()) {
        error(messages.join('\n'))
    }
}

/**
 * Fails when the release tag of a resource already exists on its remote : the release would be refused at the push.
 */
def checkTagAbsent(String workDir, resource) {
    def tag = "${resource.artifactId}-${resource.targetVersion}".toString()
    def sha = ''
    withRepositoryUrl(resource.scmUrl) { authUrl ->
        dir(workDir) {
            sha = sh(script: "git ls-remote \"${authUrl}\" 'refs/tags/${tag}' | cut -f1", returnStdout: true).trim()
        }
    }
    if (sha) {
        error("Tag ${tag} already exists on the remote (${shortSha(sha)})")
    }
}

/**
 * Verifies one resource as its release would build it, without committing nor pushing anything : clone, tests (plugins and core), versions
 * set, SNAPSHOT artifacts, compilation and packaging of the release profile, tag. The aggregate is verified on its current POM, which still
 * references the SNAPSHOT versions of the components of the step : its SNAPSHOT checks are skipped. Returns the status (OK, TO_FIX,
 * WARNING), the commit verified (head of the branch at clone time) and the result of each check.
 */
def verifyResource(resource, boolean isAggregate) {
    def result = [status: 'OK', version: resource.targetVersion, commit: '', checks: []]
    if (isAggregate) {
        result.note = 'verified before the version updates'
    }
    echo "=== Verifying ${coordinates(resource)} ${resource.currentVersion} -> ${resource.targetVersion} (branch ${resource.branch}) ==="
    def workDir = null
    def clone = runCheck('clone', 'TO_FIX') {
        workDir = cloneResource(resource)
        result.commit = readFile("${workDir}/.git/releaser-origin-sha").trim()
    }
    if (clone.status != 'OK') {
        result.checks << clone
    } else {
        withJdk(detectTargetJdk(workDir)) {
            if (mustRunTests(resource)) {
                result.checks << runCheck('tests', 'TO_FIX') { runTests(workDir, resource) }
                cleanWorkTree(workDir)
            }
            def versions = runCheck('versions', 'TO_FIX') {
                if (resource.parentVersion && !isAggregate) {
                    setParentVersion(workDir, resource.parentVersion)
                }
                if (!isAggregate) {
                    setResourceVersion(workDir, resource, resource.targetVersion)
                }
            }
            result.checks << versions
            if (versions.status == 'OK') {
                if (!isAggregate) {
                    def directProblems = []
                    result.checks << runCheck('snapshot-dependencies', 'TO_FIX') {
                        directProblems = snapshotProblems(workDir)
                        checkNoDirectSnapshot(directProblems)
                    }
                    result.checks << runCheck('transitive-snapshot-dependencies', 'WARNING') { checkNoTransitiveSnapshot(workDir, resource, directProblems) }
                }
                result.checks << runCheck('build', 'TO_FIX') {
                    dir(workDir) {
                        runLogged(resource, 'Build of the release profile, nothing pushed (mvn clean verify)', "mvn -s ${env.MAVEN_SETTINGS_XML} -q clean verify -DperformRelease=true -DskipTests")
                    }
                }
            }
            result.checks << runCheck('tag', 'TO_FIX') { checkTagAbsent(workDir, resource) }
        }
        cleanWorkTree(workDir)
    }
    def statuses = result.checks.collect { it.status }
    result.status = statuses.contains('TO_FIX') ? 'TO_FIX' : (statuses.contains('WARNING') ? 'WARNING' : 'OK')
    return result
}

/**
 * Writes the result of a verified resource in the step report : one line, then the first lines of each check that did not pass.
 */
def reportVerification(resource, result) {
    def line = "${result.status == 'OK' ? 'VERIFIED' : (result.status == 'TO_FIX' ? 'TO FIX' : 'WARNING')} ${coordinates(resource)} ${resource.targetVersion}" +
            (result.commit ? " (commit ${shortSha(result.commit)})" : '') + (result.note ? " — ${result.note}" : '')
    def details = result.checks.findAll { it.status != 'OK' }.collect { check ->
        def lines = check.details.readLines().findAll { it.trim() }
        "  - ${check.check} : ${lines ? lines[0] : ''}\n" + formatDetails(lines).readLines().take(15).collect { "  ${it}\n" }.join('')
    }.join('')
    appendReport((line + '\n' + details).trim())
}

/**
 * Stage 2 (mode VERIFY) — Verify the components, then the aggregate, the verification report growing after each one.
 */
def stageVerifyComponents() {
    def thePlan = plan()
    def report = [mode: 'VERIFY', components: [:], aggregate: null]
    writeVerifyReport(report)
    thePlan.components.each { component ->
        def result = verifyResource(component, false)
        report.components[coordinates(component)] = result
        writeVerifyReport(report)
        reportVerification(component, result)
    }
    if (thePlan.aggregate) {
        def result = verifyResource(thePlan.aggregate, true)
        result.coordinates = coordinates(thePlan.aggregate)
        report.aggregate = result
        writeVerifyReport(report)
        reportVerification(thePlan.aggregate, result)
    }
}

/**
 * Final report of a verification : the count of each status. The build is UNSTABLE when a resource is to fix or has a warning : the
 * verification itself ran, the resources have problems.
 */
def reportVerificationSummary() {
    def report = readJSON(file: env.VERIFY_REPORT, returnPojo: true)
    def results = report.components.values() + (report.aggregate ? [report.aggregate] : [])
    def toFix = results.count { it.status == 'TO_FIX' }
    def warnings = results.count { r -> r.checks.any { it.status == 'WARNING' } }
    appendReport("Verification completed : ${toFix} to fix, ${warnings} with a warning, out of ${results.size()} resource(s). Nothing was committed nor pushed.")
    if (toFix > 0 || warnings > 0) {
        unstable("${toFix} resource(s) to fix, ${warnings} with a warning")
    }
}

// ========================================================================
// POM updates of the aggregate — the releaser decides, the pipeline applies
// ========================================================================

/**
 * Applies the version updates decided by the releaser to the POM of the aggregate : the parent version, the properties
 * (property name -> value) and the dependency declarations ("groupId:artifactId" -> version, the <version> tag right after the
 * <artifactId> tag). Nothing is guessed here : a version held by a property comes in "properties", never in "dependencies".
 * The updates of the components of this step that were not published (failed, or never reached) are left out : the POM only
 * references versions that exist.
 */
def applyVersionUpdates(String workDir, aggregate) {
    def skippedProperties = [] as Set
    def skippedDependencies = [] as Set
    plan().components.each { component ->
        if (!isReleased(component)) {
            if (component.versionProperty) {
                skippedProperties.add(component.versionProperty)
            } else {
                skippedDependencies.add(coordinates(component))
            }
            appendReport("${coordinates(aggregate)} : version of ${coordinates(component)} left unchanged, the component was not published")
        }
    }
    dir(workDir) {
        if (aggregate.parentVersion) {
            sh "sed -i '/<parent>/,/<\\/parent>/ s|<version>[^<]*</version>|<version>${aggregate.parentVersion}</version>|' pom.xml"
            echo "Parent version -> ${aggregate.parentVersion}"
            appendReport("${coordinates(aggregate)} : parent POM set to ${aggregate.parentVersion}")
        }
        def updates = aggregate.versionUpdates ?: [:]
        (updates.properties ?: [:]).each { name, version ->
            if (skippedProperties.contains(name)) {
                return
            }
            sh "sed -i 's|<${name}>[^<]*</${name}>|<${name}>${version}</${name}>|g' pom.xml"
            echo "Property ${name} -> ${version}"
        }
        (updates.dependencies ?: [:]).each { coords, version ->
            if (skippedDependencies.contains(coords)) {
                return
            }
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
    env.STEP_MODE = planMode(thePlan)
    env.DRY_RUN = env.STEP_MODE == 'DRY_RUN' ? 'true' : 'false'
    checkCredentials(thePlan)
    writeAskpassScript()

    configFileProvider([configFile(fileId: params.MAVEN_SETTINGS_ID, variable: 'MVN_SETTINGS_TMP')]) {
        sh "cp \${MVN_SETTINGS_TMP} ${WORKSPACE}/maven-settings.xml"
    }
    env.MAVEN_SETTINGS_XML = "${WORKSPACE}/maven-settings.xml"

    sh "rm -rf '${env.WORK_DIR}' && mkdir -p '${env.WORK_DIR}'"
    writeReport([releasedVersions: [:], failedVersions: [:], notProcessed: [], pomUpdated: false, aggregateVersion: null, report: ''])

    echo "=========================================="
    echo " Lutece Platform Step Pipeline"
    echo " Campaign    : #${thePlan.campaignId} ${thePlan.campaignName}"
    echo " Step        : ${thePlan.step} ${thePlan.stepCode}"
    echo " Type        : ${thePlan.releaseType} (core ${thePlan.coreMajor})"
    echo " Mode        : ${env.STEP_MODE}"
    echo " Components  : ${thePlan.components.size()}"
    echo " Aggregate   : ${thePlan.aggregate ? coordinates(thePlan.aggregate) + ' ' + thePlan.aggregate.currentVersion + ' -> ' + thePlan.aggregate.targetVersion : '(none)'}"
    echo "=========================================="
    appendReport("Step ${thePlan.step} ${thePlan.stepCode} of campaign #${thePlan.campaignId} ${thePlan.campaignName} — ${thePlan.releaseType} — ${isVerify() ? 'verification' : 'dry run : ' + env.DRY_RUN}")
    for (def despite : (thePlan.launchedDespite ?: [])) {
        appendReport("Launched despite the verification : ${despite}")
    }
}

/**
 * Stage 2 — Release the components in the order received, the report growing after each one. A failure does not stop the stage :
 * the failed component is rolled back and recorded, the following ones are released, so that one broken component does not hold the
 * whole step. The aggregate is then updated with the published versions only and not released, and the build ends in failure.
 */
def stageReleaseComponents() {
    def components = plan().components
    if (components.isEmpty()) {
        appendReport('No component to release in this step.')
        return
    }
    components.each { component ->
        try {
            def workDir = cloneResource(component)
            withJdk(detectTargetJdk(workDir)) {
                releaseResource(workDir, component, false)
            }
        } catch (Throwable e) {
            echo "Release of ${coordinates(component)} failed : ${e.message}"
            recordFailed(component, (e.message ?: e.class.simpleName).toString())
        }
    }
}

/**
 * Stage 3 — Update the POM of the aggregate with the versions published (this step and the previous ones) and the parent version.
 * The plugins step stops here : the monorepo POM is committed and pushed, its release is the last step of the campaign. When a
 * component failed, the POM is pushed as well, with the published versions only, and the aggregate is not released.
 */
def stageUpdateAggregate() {
    def aggregate = plan().aggregate
    try {
        updateAggregatePom(aggregate)
    } catch (Throwable e) {
        reportAggregateFailure(aggregate, 'update of its POM', e)
        throw e
    }
}

/**
 * Clones the aggregate, applies the version updates and commits them ; pushes the POM when the aggregate is not released by this step.
 */
def updateAggregatePom(aggregate) {
    def workDir = cloneResource(aggregate)
    env.AGGREGATE_DIR = workDir
    applyVersionUpdates(workDir, aggregate)

    dir(workDir) {
        sh """
            git add -u
            git diff --cached --quiet && echo 'Aggregate POM already up to date — nothing to commit' || git commit -m "chore: update versions for the release of ${aggregate.artifactId} ${aggregate.targetVersion}"
        """
    }

    if (isAggregateReleased() && !hasFailures()) {
        return
    }
    if (isDryRun()) {
        echo "[DRY-RUN] Would push the updated POM of ${aggregate.artifactId} on ${aggregate.branch}"
    } else {
        withRepositoryUrl(aggregate.scmUrl) { authUrl ->
            dir(workDir) {
                runLogged(aggregate, "Push of the updated POM on ${aggregate.branch}", "git push \"${authUrl}\" '${aggregate.branch}'")
            }
        }
        markPomUpdated()
    }
    def verb = isDryRun() ? '[DRY-RUN] POM would be updated' : 'POM updated'
    if (hasFailures()) {
        appendReport("Aggregate ${coordinates(aggregate)} : ${verb} on ${aggregate.branch} with the published versions only, NOT released because a component failed.")
    } else {
        appendReport("Aggregate ${coordinates(aggregate)} : ${verb} on ${aggregate.branch}, not released by this step.")
    }
}

/**
 * Stage 4 — Release the aggregate (global-pom, site-pom, core) once its POM references the released components.
 */
def stageReleaseAggregate() {
    def aggregate = plan().aggregate
    def workDir = env.AGGREGATE_DIR
    try {
        withJdk(detectTargetJdk(workDir)) {
            releaseResource(workDir, aggregate, true)
        }
    } catch (Throwable e) {
        reportAggregateFailure(aggregate, 'release', e)
        throw e
    }
}

/**
 * Stage 5 — Final report. When a component failed, the report says exactly what happened to each component and the build fails :
 * the released versions are in Nexus and in the aggregate POM, the failed ones were rolled back, the ones never reached are listed.
 */
def stageReport() {
    if (isVerify()) {
        reportVerificationSummary()
        echo readFile(env.STEP_REPORT)
        return
    }
    def report = readReport()
    def released = report.releasedVersions ?: [:]
    def failed = report.failedVersions ?: [:]
    def notProcessed = plan().components.findAll { !released.containsKey(coordinates(it)) && !failed.containsKey(coordinates(it)) }
            .collect { coordinates(it) }
    report.notProcessed = notProcessed
    writeReport(report)

    if (failed.isEmpty()) {
        appendReport("Pipeline completed : ${new Date()} — status ${currentBuild.result ?: 'SUCCESS'}")
        echo readFile(env.STEP_REPORT)
        return
    }
    def lines = []
    lines.add("STEP FAILED : ${failed.size()} component(s) could not be released.${isDryRun() ? ' [DRY-RUN : nothing was pushed nor published]' : ''}".toString())
    lines.add('  Failed (nothing published ; error lines and rollback above) :')
    failed.each { coords, reason -> lines.add("    - ${coords} : ${reason}".toString()) }
    lines.add(isDryRun() ? '  Would be published (in Nexus, referenced by the aggregate POM) :' : '  Published (in Nexus, referenced by the aggregate POM) :')
    if (released.isEmpty()) {
        lines.add('    - none')
    }
    released.each { coords, version -> lines.add("    - ${coords} ${version}".toString()) }
    lines.add('  Not processed :')
    if (notProcessed.isEmpty()) {
        lines.add('    - none')
    }
    notProcessed.each { lines.add("    - ${it}".toString()) }
    lines.add('  The aggregate was not released. Fix the failed component(s), then prepare the step again in the releaser : the published ones are shown as already released and will not be released twice.')
    appendReport(lines.join('\n'))
    echo readFile(env.STEP_REPORT)
    error("${failed.size()} component(s) failed, see the step report")
}

/**
 * Post — Failure : the partial report already lists what was released ; say so when the failure did not come from a component.
 */
def postFailure() {
    try {
        if (!hasFailures()) {
            appendReport("FAILED at ${new Date()} : the resources listed in releasedVersions were released, the others were not. Prepare the step again in the releaser to release the remaining ones.")
        }
    } catch (Throwable e) {
        echo "Could not complete the report : ${e.message}"
    }
}

// Required : return 'this' so that load() can assign the script to a variable
return this
