/**
 * Lutece Platform — Step Pipeline — Helper Functions
 *
 * Stage bodies of Jenkinsfile-platform-step, extracted to stay under the
 * "Method too large" limit of the Jenkins CPS engine.
 *
 * Loaded with : def helpers = load('platform-step-helpers.groovy')
 */

import groovy.transform.Field

// Lock of the reports between the parallel branches of a verification
@Field def reportLock = [busy: false]

// Only hosts the credentials are sent to, and the only repository hosts a plan may name
@Field final String GITHUB_HOST = 'github.com'
@Field final List GITLAB_HOSTS = ['dev.lutece.paris.fr']
// Only repositories a plan may name : the Lutece organizations and the Lutece GitLab (same list as releaser.allowedRepositoryUrls)
@Field final List ALLOWED_REPOSITORY_PREFIXES = ['https://github.com/lutece-platform/', 'https://github.com/lutece-secteur-public/',
                                                 'https://github.com/citelibre/', 'https://dev.lutece.paris.fr/gitlab/']

// Accepted shapes of the plan values interpolated into shell commands
@Field final String SAFE_NAME = '[A-Za-z0-9][A-Za-z0-9._-]*'
@Field final String SAFE_BRANCH = '[A-Za-z0-9][A-Za-z0-9._/-]*'
@Field final String SAFE_COMMIT = '[0-9a-f]{7,40}'
// Only this server id of the Maven settings, toward these hosts : its credentials must never be sent anywhere else
@Field final String SAFE_DEPLOY_REPOSITORY = 'lutece_releases_private_repository::default::https://(piclutece-p30\\.apps\\.paris\\.mdp)/[A-Za-z0-9._/~-]+'

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
 * Whether the plan asks for a verification : every check that needs a build runs on each resource, nothing is committed nor pushed. The
 * test of the SNAPSHOT of a campaign (step 0) is a verification too.
 */
def isVerify() {
    return env.STEP_MODE in ['VERIFY', 'PRECHECK']
}

/**
 * Whether the plan asks for the test of every SNAPSHOT of a campaign before it starts (step 0) : the head of each branch is tested as it
 * is, no version set and no tag checked.
 */
def isPrecheck() {
    return env.STEP_MODE == 'PRECHECK'
}

/**
 * Mode of the plan : RELEASE, DRY_RUN, VERIFY or PRECHECK. A plan without mode (releaser before the VERIFY mode) is read from its dryRun flag.
 */
def planMode(thePlan) {
    def mode = (thePlan.mode ?: (thePlan.dryRun ? 'DRY_RUN' : 'RELEASE')).toString().toUpperCase()
    if (!(mode in ['RELEASE', 'DRY_RUN', 'VERIFY', 'PRECHECK'])) {
        error("Unknown plan mode '${mode}' : RELEASE, DRY_RUN, VERIFY or PRECHECK expected.")
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
    // -x : ignored files too (target/, a planted source) ; .mvn : loaded by Maven before any goal, never part of a Lutece repository
    dir(workDir) {
        sh 'git reset -q --hard HEAD && git clean -fdxq && rm -rf .mvn'
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
    def host = repositoryHost(scmUrl)
    def github = host == GITHUB_HOST
    return [host : github ? 'GitHub' : (host in GITLAB_HOSTS ? 'GitLab' : "unknown host '${host}'".toString()),
            login: github ? params.GITHUB_LOGIN : params.GITLAB_LOGIN,
            token: (github ? params.GITHUB_TOKEN : params.GITLAB_TOKEN)?.toString()]
}

/**
 * Host of an https repository URL ("scm:git:" prefix accepted), empty when the URL is not of that shape.
 */
def repositoryHost(String scmUrl) {
    def url = (scmUrl ?: '').replaceFirst('^scm:git:', '')
    return url ==~ '^https://[A-Za-z0-9.-]+/.*' ? url.substring(8, url.indexOf('/', 8)).toLowerCase() : ''
}

/**
 * Writes the script git calls for a username or a password : it answers with the GITHUB_* or GITLAB_* build parameters, read from the
 * environment, only for the exact hosts GITHUB_HOST and GITLAB_HOSTS named in the prompt ("Username for 'https://host': ", "Password for
 * 'https://login@host': "), nothing for any other host. The token thus never appears in a command line, a URL or the clone
 * configuration ; GIT_ASKPASS points to this script and GIT_TERMINAL_PROMPT=0 forbids any interactive prompt.
 */
def writeAskpassScript() {
    def gitlabPatterns = GITLAB_HOSTS.collect { "*\"'https://${it}'\"*|*\"'https://\"*\"@${it}'\"*" }.join('|')
    writeFile file: env.GIT_ASKPASS, text: """#!/bin/sh
case "\$1" in
  *"'https://${GITHUB_HOST}'"*|*"'https://"*"@${GITHUB_HOST}'"*)[ "\${1#Username}" != "\$1" ] && printf '%s\\n' "\$GITHUB_LOGIN" || printf '%s\\n' "\$GITHUB_TOKEN" ;;
  ${gitlabPatterns}) [ "\${1#Username}" != "\$1" ] && printf '%s\\n' "\$GITLAB_LOGIN" || printf '%s\\n' "\$GITLAB_TOKEN" ;;
  *) printf '\\n' ;;
esac
"""
    sh "chmod 700 '${env.GIT_ASKPASS}'"
}

/**
 * Writes a "mvn" wrapper, first on the PATH (RELEASER_BIN, see the Jenkinsfile), that runs Maven without the login and token parameters
 * in its environment : Maven builds the POM of a repository as it is, and a plugin or a test of that POM must never read them. git keeps
 * them through GIT_ASKPASS, and is never run by this wrapper.
 */
def writeMavenWrapper() {
    // Maven is resolved by its Jenkins tool, by absolute path : a tools { maven } block would put the real mvn before the wrapper on the PATH
    sh "rm -rf '${env.RELEASER_BIN}'"
    // A parameter added to the Jenkinsfile is null in the first build that declares it
    def mavenTool = params.MAVEN_TOOL ?: 'maven-3.9.12'
    def real = "${tool(name: mavenTool, type: 'maven')}/bin/mvn".toString()
    if (!fileExists(real)) {
        error("mvn not found in the Maven tool '${mavenTool}' (${real})")
    }
    sh "mkdir -p '${env.RELEASER_BIN}'"
    writeFile file: "${env.RELEASER_BIN}/mvn", text: """#!/bin/sh
exec env -u GITHUB_TOKEN -u GITLAB_TOKEN -u GITHUB_LOGIN -u GITLAB_LOGIN -u GIT_ASKPASS '${real}' "\$@"
"""
    sh "chmod 700 '${env.RELEASER_BIN}/mvn'"
    // Self-check : every later "mvn" must be the wrapper, otherwise the tokens would reach Maven
    def resolved = sh(script: "command -v mvn", returnStdout: true).trim()
    if (resolved != "${env.RELEASER_BIN}/mvn".toString()) {
        error("mvn resolves to '${resolved}' instead of the wrapper : the PATH of the job must not put another Maven first (no tools { maven } block)")
    }
}

/**
 * Fails when the files staged for a release commit are not the ones a release changes : the build of the previous resource ran the code
 * of its repository, which could have altered this work tree. The POMs, the plugin descriptors and the AppInfo classes are the only files
 * the version update touches.
 */
def checkStagedFiles(String workDir, resource) {
    def staged = ''
    def diff = ''
    dir(workDir) {
        staged = sh(script: "git diff --cached --name-only", returnStdout: true).trim()
        diff = sh(script: "git diff --cached -U0 --no-color", returnStdout: true)
    }
    def unexpected = staged.readLines().findAll { f ->
        !(f ==~ /(.*\/)?pom\.xml/ || f ==~ /.*\/WEB-INF\/plugins\/[^\/]+\.xml/ || f ==~ /.*\/WEB-INF\/conf\/plugins\/[^\/]+\.properties/
          || f ==~ /.*\/AppInfo\.java/ || f ==~ /(.*\/)?core\.xml/)
    }
    if (unexpected) {
        error("${coordinates(resource)} : files other than the version files are modified in the work tree, release refused : ${unexpected.join(', ')}")
    }
    // The content too : only version lines may change, a plugin or a dependency added by a tampered work tree must not be released
    def properties = ((resource.versionUpdates?.properties ?: [:]).keySet() as List).collect { java.util.regex.Pattern.quote(it.toString()) }
    // A trailing XML comment on a version line is tolerated : some POMs annotate their version
    def allowed = [/^\s*<version>[^<]*<\/version>\s*(<!--.*-->)?\s*$/, /^\s*<[A-Za-z0-9._-]*[Vv]ersion>[^<]*<\/[A-Za-z0-9._-]*[Vv]ersion>\s*(<!--.*-->)?\s*$/,
                   /^\s*(private\s+|public\s+)?(static\s+)?(final\s+)?String\s+APP_VERSION\s*=\s*"[^"]*"\s*;\s*$/, /^\s*[A-Za-z0-9._-]*version\s*=[^;{}]*$/]
    properties.each { allowed << ("^\\s*<" + it + ">[^<]*</" + it + ">\\s*\$") }
    def bad = diff.readLines().findAll { it ==~ /^[+-](?![+-]{2}).*/ }.collect { it.substring(1) }.findAll { l -> !allowed.any { l ==~ it } }
    if (bad) {
        error("${coordinates(resource)} : lines other than versions are modified in the work tree, release refused : ${bad.take(5).join(' | ')}")
    }
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
 * Refuses a plan holding a value of an unexpected shape, before any clone : every value the pipeline puts in a shell command (names,
 * versions, branches, tags, commits, repository URLs under ALLOWED_REPOSITORY_PREFIXES only, deploy repository) must match a strict pattern.
 */
def validatePlan(thePlan) {
    def prefixes = ALLOWED_REPOSITORY_PREFIXES.collect { it.replace('.', '\\.') }.join('|')
    // Plain path segments only : "..", "." or an empty segment would leave the organization once git normalizes the URL
    def scmPattern = "(scm:git:)?(${prefixes})([A-Za-z0-9_-][A-Za-z0-9._-]*/)*[A-Za-z0-9_-][A-Za-z0-9._-]*/?".toString()
    def coordinatesPattern = "${SAFE_NAME}:${SAFE_NAME}".toString()
    def problems = []
    def check = { String what, value, String pattern, boolean required ->
        def text = value == null ? '' : value.toString()
        if (!text) {
            if (required) {
                problems << "${what} missing".toString()
            }
        } else if (!(text ==~ pattern)) {
            problems << "${what} '${text}'".toString()
        }
    }
    def resources = (thePlan.components ?: []) + (thePlan.aggregate ? [thePlan.aggregate] : [])
    for (def r : resources) {
        def who = (r.artifactId ?: '?').toString()
        check('artifactId', r.artifactId, SAFE_NAME, true)
        check("groupId of ${who}", r.groupId, SAFE_NAME, true)
        check("scmUrl of ${who}", r.scmUrl, scmPattern, true)
        check("branch of ${who}", r.branch, SAFE_BRANCH, true)
        check("masterBranch of ${who}", r.masterBranch, SAFE_BRANCH, false)
        check("targetVersion of ${who}", r.targetVersion, SAFE_NAME, !isPrecheck())
        // The current version is put in a shell command by the precheck only (tags of the untested commits)
        check("currentVersion of ${who}", r.currentVersion, SAFE_NAME, isPrecheck())
        for (def field : ['nextSnapshotVersion', 'parentVersion', 'versionProperty', 'sourceTag']) {
            check("${field} of ${who}", r[field], SAFE_NAME, false)
        }
        check("verifiedCommit of ${who}", r.verifiedCommit, SAFE_COMMIT, false)
        check("deployRepository of ${who}", r.deployRepository, SAFE_DEPLOY_REPOSITORY, false)
        def updates = r.versionUpdates ?: [:]
        (updates.properties ?: [:]).each { name, version ->
            check('version property', name, SAFE_NAME, true)
            check("value of ${name}", version, SAFE_NAME, true)
        }
        (updates.dependencies ?: [:]).each { coords, version ->
            check('dependency', coords, coordinatesPattern, true)
            check("version of ${coords}", version, SAFE_NAME, true)
        }
    }
    for (def coords : (thePlan.campaignCoordinates ?: [])) {
        check('campaign coordinates', coords, coordinatesPattern, true)
    }
    check('GIT_USER_NAME', params.GIT_USER_NAME, '[\\p{L}0-9 ._@-]+', false)
    check('GIT_USER_EMAIL', params.GIT_USER_EMAIL, '[A-Za-z0-9._@+-]+', false)
    if (problems) {
        appendReport("Plan rejected before any change, unexpected values : ${problems.join(' ; ')}")
        error("Plan rejected, unexpected values : ${problems.join(' ; ')}")
    }
}

/**
 * Fails when a tag name read from a repository has an unexpected shape : it is then put in shell commands.
 */
def checkTagName(String tag) {
    if (tag && !(tag ==~ SAFE_NAME)) {
        error("Unexpected tag name '${tag}' in the repository : refused")
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
            rethrowIfAborted(e)
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
        rethrowIfAborted(e)
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
                sed -i -E '/private static final String APP_VERSION[[:space:]]*=/ s|"[^"]*"|"${version}"|' src/java/fr/paris/lutece/portal/service/init/AppInfo.java
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
    return repositoryHost(resource.scmUrl) == GITHUB_HOST ? 'public repository' : 'private repository NOT CONFIGURED'
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
            // A failed ls-remote must fail here, not read as a missing branch
            sha = sh(script: "out=\$(git ls-remote \"${authUrl}\" 'refs/heads/${branch}') || exit 1; printf '%s\\n' \"\$out\" | cut -f1", returnStdout: true).trim()
        }
    }
    return sha
}

/**
 * Checks out, detached, the beta or RC tag a resource is released from and returns its commit. The tag must be a pre-release of the target
 * version ("<artifactId>-<target>-beta-NN", "-RC-NN", or "v<target>-..."), otherwise the release is refused before any change.
 */
def checkoutSourceTag(String workDir, resource) {
    def sourceTag = resource.sourceTag.toString()
    def prefixes = ["${resource.artifactId}-${resource.targetVersion}", "v${resource.targetVersion}"].collectMany { ["${it}-beta-".toString(), "${it}-RC-".toString()] }
    if (!prefixes.any { sourceTag.startsWith(it) }) {
        error("${coordinates(resource)} : tag ${sourceTag} is not a beta or RC of ${resource.targetVersion}, release refused before any change.")
    }
    def sha = ''
    withRepositoryUrl(resource.scmUrl) { authUrl ->
        dir(workDir) {
            runLogged(resource, "Checkout of tag ${sourceTag}", """
                git fetch -q "${authUrl}" 'refs/tags/${sourceTag}:refs/tags/${sourceTag}'
                git checkout -q --detach 'refs/tags/${sourceTag}^{commit}'
            """)
            sha = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
        }
    }
    return sha
}

/**
 * Refuses to release a resource whose POM, versions set, still declares SNAPSHOT artifacts : a SNAPSHOT parent, or a dependency whose
 * declared version (properties resolved, every module of a multi-module build) is a SNAPSHOT. Declared versions, not resolved ones : a
 * Lutece range such as [7.0.0,8.0.0) may resolve to a SNAPSHOT of the core without the POM declaring one, exactly as the maven-release-plugin
 * of the classic release judges it. A stable version of a component is refused on a beta or RC parent or dependency too. Runs before any
 * commit, so nothing is to roll back.
 */
def checkReleaseDependencies(String workDir, resource, boolean isAggregate) {
    def declared = declaredVersions(workDir)
    def problems = snapshotProblems(declared)
    if (!problems.isEmpty()) {
        appendReport("${coordinates(resource)} : release refused, the POM rests on SNAPSHOT artifacts : ${problems.join(' ; ')}")
        error("${coordinates(resource)} rests on SNAPSHOT artifacts : ${problems.join(' ; ')}")
    }
    def prereleases = mustCheckPrereleases(isAggregate) ? prereleaseProblems(declared) : []
    if (!prereleases.isEmpty()) {
        appendReport("${coordinates(resource)} : release refused, a stable version rests on beta or RC artifacts : ${prereleases.join(' ; ')}")
        error("${coordinates(resource)} rests on beta or RC artifacts : ${prereleases.join(' ; ')}")
    }
}

/**
 * Whether the beta and RC artifacts are refused : for a component of a stable step, not for the aggregate.
 */
def mustCheckPrereleases(boolean isAggregate) {
    return !isAggregate && !isPrecheck() && plan().releaseType == 'STABLE'
}

/**
 * The versions the POM in a directory declares : its parent, then each dependency once ("parent x.y.z", "groupId:artifactId:version").
 */
def declaredVersions(String workDir) {
    def versions = []
    dir(workDir) {
        def parentVersion = ''
        def declared = ''
        // A Maven failure must fail the check : an empty answer would let every SNAPSHOT, beta or RC through
        try {
            parentVersion = sh(script: "out=\$(mvn -s ${env.MAVEN_SETTINGS_XML} -N -q help:evaluate -Dexpression=project.parent.version -DforceStdout 2>/dev/null) || exit 1; printf '%s\\n' \"\$out\" | tail -1", returnStdout: true).trim()
            declared = sh(script: "mvn -s ${env.MAVEN_SETTINGS_XML} -q help:evaluate -Dexpression=project.dependencies -DforceStdout 2>/dev/null", returnStdout: true)
        } catch (Throwable e) {
            rethrowIfAborted(e)
            error("Maven could not read the declared versions of the POM (help:evaluate failed) : SNAPSHOT, beta and RC dependencies not checked")
        }
        if (parentVersion.startsWith('[ERROR]')) {
            error("Maven could not read the parent of the POM : ${parentVersion}")
        }
        def dependencies = [] as Set
        def current = [:]
        declared.readLines().each { line ->
            def m = (line =~ /<(groupId|artifactId|version)>([^<]*)<\/\1>/)
            if (m.find()) {
                current[m.group(1)] = m.group(2)
                if (m.group(1) == 'version') {
                    dependencies.add("${current.groupId}:${current.artifactId}:${m.group(2)}".toString())
                }
            }
        }
        if (parentVersion && !parentVersion.startsWith('null object')) {
            versions.add("parent ${parentVersion}".toString())
        }
        versions.addAll(dependencies)
    }
    return versions
}

/**
 * The SNAPSHOT artifacts among the declared versions. Empty when the POM declares none.
 */
def snapshotProblems(List declared) {
    return declared.findAll { it.endsWith('-SNAPSHOT') }
}

/**
 * The beta and RC artifacts among the declared versions (x.y.z-beta-NN, x.y.z-RC-NN, a bare -beta / -RC, or the -RC1 / -beta.2 forms of
 * other projects). Empty when the POM declares none.
 */
def prereleaseProblems(List declared) {
    return declared.findAll { it ==~ /(?i).*[-.](beta|rc)([-.]?\d+)?/ }
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
 * tag merged into the master branch for a stable version, next snapshot pushed), the Nexus deploy from the tag LAST (deployAtEnd). Any
 * failure rolls back what this build pushed (see rollbackResource) : as nothing is in Nexus before the last command, the same version can
 * be released again. The master branch is checked before any push. The release commit is compiled
 * before any push. The resource is recorded in the report once deployed. In dry run the local part runs, compilation included, and the
 * publication is only logged. A resource with a sourceTag is released from that beta or RC tag : only the release tag is pushed, then the
 * branch gets the next snapshot and an "ours" merge of the release tag, so that its later releases merge into master without conflict.
 * An aggregate released from a tag is already on it (see updateAggregatePom) ; its branch also gets the version updates.
 */
def releaseResource(String workDir, resource, boolean isAggregate) {
    def tag = "${resource.artifactId}-${resource.targetVersion}".toString()
    def sourceTag = resource.sourceTag ? resource.sourceTag.toString() : ''
    echo "=== Releasing ${coordinates(resource)} ${resource.currentVersion} -> ${resource.targetVersion} (branch ${resource.branch}, tag ${tag}${sourceTag ? ', from ' + sourceTag : ''}) ==="

    // In dry run, what a real release would refuse is reported and the resource is not counted as publishable
    def dryRunRefusals = []
    if (!resource.deployRepository && repositoryHost(resource.scmUrl) != GITHUB_HOST) {
        if (!isDryRun()) {
            error("${coordinates(resource)} : no private Nexus repository in the plan for a GitLab resource, release refused before any change.")
        }
        dryRunRefusals << 'no private Nexus repository in the plan for a GitLab resource'
    }
    if (resource.masterBranch && !remoteBranchSha(workDir, resource.scmUrl, resource.masterBranch)) {
        if (!isDryRun()) {
            error("Branch ${resource.masterBranch} not found on ${resource.scmUrl} : cannot merge ${tag} into it, release refused before any change.")
        }
        dryRunRefusals << "branch ${resource.masterBranch} not found, ${tag} could not be merged into it"
    }
    if (sourceTag) {
        if (!isAggregate) {
            checkoutSourceTag(workDir, resource)
        }
        appendReport("${coordinates(resource)} : released from ${sourceTag}")
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
    checkReleaseDependencies(workDir, resource, isAggregate)
    // In a release the tag must not exist yet : a push rejected for that reason would otherwise be read as a tag of this build
    if (!isDryRun()) {
        checkTagAbsent(workDir, resource)
    }
    def releaseSha = ''
    def releaseMessage = sourceTag ? "release: ${tag} (from ${sourceTag})" : "release: ${tag}"
    dir(workDir) {
        sh 'git add -u'
    }
    checkStagedFiles(workDir, resource)
    dir(workDir) {
        sh """
            git diff --cached --quiet && echo 'Version already at ${resource.targetVersion} — nothing to commit' || git commit -m "${releaseMessage}"
            git tag -fa '${tag}' -m "Release ${resource.artifactId} ${resource.targetVersion}"
        """
        releaseSha = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
    }
    checkCompiles(workDir, resource)

    if (isDryRun()) {
        echo sourceTag ? "[DRY-RUN] Would push tag ${tag} only" : "[DRY-RUN] Would push ${resource.branch} and tag ${tag}"
        if (resource.masterBranch) {
            echo "[DRY-RUN] Would merge ${tag} into ${resource.masterBranch}"
        }
        echo "[DRY-RUN] Would set the next development version ${resource.nextSnapshotVersion}"
        if (sourceTag && isAggregate) {
            echo "[DRY-RUN] Would apply the version updates on ${resource.branch}"
        }
        if (sourceTag) {
            echo "[DRY-RUN] Would merge ${tag} into ${resource.branch} keeping its content (ours), then push ${resource.branch}"
        }
        echo "[DRY-RUN] Would deploy ${coordinates(resource)}:${resource.targetVersion} to Nexus (${deployTarget(resource)}) from ${tag}"
        if (dryRunRefusals) {
            error("[DRY-RUN] a real release would be refused : ${dryRunRefusals.join(' ; ')}")
        }
        recordReleased(resource, isAggregate)
        return
    }

    def originSha = readFile("${workDir}/.git/releaser-origin-sha").trim()
    // What this build actually pushed : the rollback only undoes that, and only if nobody pushed since
    def published = [branch: '', master: '', masterBefore: '', tag: false, deploy: false]

    try {
        withRepositoryUrl(resource.scmUrl) { authUrl ->
            dir(workDir) {
                if (sourceTag) {
                    runLogged(resource, "Push of tag ${tag}", "git push \"${authUrl}\" 'refs/tags/${tag}'")
                } else {
                    runLogged(resource, "Push of ${resource.branch} and tag ${tag} (atomic)", "git push --atomic \"${authUrl}\" '${resource.branch}' 'refs/tags/${tag}'")
                    published.branch = sh(script: "git rev-parse 'refs/heads/${resource.branch}'", returnStdout: true).trim()
                }
                published.tag = true
            }
        }

        if (resource.masterBranch) {
            withRepositoryUrl(resource.scmUrl) { authUrl ->
                dir(workDir) {
                    runLogged(resource, "Fetch of ${resource.masterBranch}", "git fetch \"${authUrl}\" '${resource.masterBranch}:${resource.masterBranch}'")
                    published.masterBefore = sh(script: "git rev-parse 'refs/heads/${resource.masterBranch}'", returnStdout: true).trim()
                    runLogged(resource, "Merge of ${tag} into ${resource.masterBranch}", """
                        git checkout '${resource.masterBranch}'
                        git merge -m "Merge ${tag} into ${resource.masterBranch}" '${tag}^{commit}'
                        git push "${authUrl}" '${resource.masterBranch}'
                    """)
                    published.master = sh(script: "git rev-parse 'refs/heads/${resource.masterBranch}'", returnStdout: true).trim()
                    sh "git checkout -q '${resource.branch}'"
                }
            }
        }

        if (sourceTag) {
            dir(workDir) {
                sh "git checkout -q '${resource.branch}'"
            }
            if (isAggregate) {
                applyVersionUpdates(workDir, resource, " on ${resource.branch}")
                dir(workDir) {
                    sh 'git add -u'
                }
                checkStagedFiles(workDir, resource)
                dir(workDir) {
                    sh """
                        git diff --cached --quiet && echo '${resource.branch} already up to date — nothing to commit' || git commit -m "chore: update versions on ${resource.branch} after the release of ${tag}"
                    """
                }
            }
        }
        if (resource.nextSnapshotVersion) {
            setResourceVersion(workDir, resource, resource.nextSnapshotVersion)
            dir(workDir) {
                sh 'git add -u'
            }
            checkStagedFiles(workDir, resource)
            withRepositoryUrl(resource.scmUrl) { authUrl ->
                dir(workDir) {
                    runLogged(resource, "Next development version ${resource.nextSnapshotVersion}", """
                        git diff --cached --quiet && echo 'Version already at ${resource.nextSnapshotVersion} — nothing to commit' || git commit -m "chore: prepare next development iteration ${resource.artifactId}-${resource.nextSnapshotVersion}"
                        git push "${authUrl}" '${resource.branch}'
                    """)
                    published.branch = sh(script: "git rev-parse 'refs/heads/${resource.branch}'", returnStdout: true).trim()
                }
            }
        }
        if (sourceTag) {
            withRepositoryUrl(resource.scmUrl) { authUrl ->
                dir(workDir) {
                    runLogged(resource, "Merge of ${tag} into ${resource.branch}, content of ${resource.branch} kept", """
                        git merge -s ours -m "Merge ${tag} into ${resource.branch} (content of ${resource.branch} kept)" '${tag}^{commit}'
                        git push "${authUrl}" '${resource.branch}'
                    """)
                    published.branch = sh(script: "git rev-parse 'refs/heads/${resource.branch}'", returnStdout: true).trim()
                }
            }
        }

        dir(workDir) {
            sh "git checkout -q 'refs/tags/${tag}'"
        }
        // The deployed artifact must be the exact content of the tag : nothing untracked, ignored or planted by an earlier build
        cleanWorkTree(workDir)
        dir(workDir) {
            def altRepository = resource.deployRepository ? " -DaltReleaseDeploymentRepository='${resource.deployRepository}'" : ''
            published.deploy = true
            // deployAtEnd : nothing reaches Nexus until every module is built
            runLogged(resource, "Nexus deploy from ${tag} (${deployTarget(resource)})", "mvn -s ${env.MAVEN_SETTINGS_XML} clean deploy -DskipTests -DperformRelease=true -DdeployAtEnd=true${altRepository}")
        }
    } catch (Throwable e) {
        // The rollback runs to its end even on an abort, but the abort then wins over the original failure
        def toThrow = e
        try {
            reconcilePublished(workDir, resource, tag, originSha, published)
            rollbackResource(workDir, resource, tag, releaseSha, originSha, published)
        } catch (Throwable r) {
            echo "Rollback of ${coordinates(resource)} interrupted : ${r.message ?: r.class.simpleName}"
            if (r instanceof InterruptedException) {
                toThrow = r
            }
        }
        if (published.deploy) {
            appendReport("${coordinates(resource)} : the Nexus deploy failed, artifacts already sent, if any, must be removed by hand before releasing ${resource.targetVersion} again")
        }
        throw toThrow
    }

    recordReleased(resource, isAggregate)
    cleanWorkTree(workDir)
    dir(workDir) {
        sh "git checkout -q '${resource.branch}'"
    }
    echo "Released ${coordinates(resource)} ${resource.targetVersion}"
}

/**
 * Completes what this build pushed after a failed push : the server may have accepted a push before the connection was lost. A remote
 * branch on the local commit of this build, or the release tag on the remote (absent before, see checkTagAbsent), was pushed by it.
 */
def reconcilePublished(String workDir, resource, String tag, String originSha, Map published) {
    def localSha = { String branch ->
        def sha = ''
        dir(workDir) {
            sha = sh(script: "git rev-parse --verify -q 'refs/heads/${branch}' || true", returnStdout: true).trim()
        }
        return sha
    }
    try {
        def branchLocal = localSha(resource.branch)
        // A new local commit present on the remote can only have been pushed by this build, also after a first push of the branch
        if (branchLocal && branchLocal != originSha && branchLocal != published.branch && remoteBranchSha(workDir, resource.scmUrl, resource.branch) == branchLocal) {
            published.branch = branchLocal
        }
        if (resource.masterBranch && published.masterBefore && !published.master) {
            def masterLocal = localSha(resource.masterBranch)
            if (masterLocal && masterLocal != published.masterBefore && remoteBranchSha(workDir, resource.scmUrl, resource.masterBranch) == masterLocal) {
                published.master = masterLocal
            }
        }
        if (!published.tag) {
            withRepositoryUrl(resource.scmUrl) { authUrl ->
                dir(workDir) {
                    def remoteTag = sh(script: "out=\$(git ls-remote \"${authUrl}\" 'refs/tags/${tag}') || exit 1; printf '%s\\n' \"\$out\" | cut -f1", returnStdout: true).trim()
                    published.tag = remoteTag as boolean
                }
            }
        }
    } catch (Throwable r) {
        rethrowIfAborted(r)
        appendReport("${coordinates(resource)} : remote not readable after the failure, check by hand whether ${resource.branch}, ${resource.masterBranch ?: '-'} and ${tag} were pushed")
    }
}

/**
 * Rolls a failed release back, as GitResourceService.rollbackRelease does in the releaser, but only what this build pushed : the release
 * branch and the master branch go back to their commits of before the release by a protected force push (--force-with-lease on the commit
 * this build pushed : a commit pushed by someone else since is never erased), the tag is deleted when it still points to the release
 * commit. Every command is attempted even if a previous one fails, and the outcome goes to the report.
 */
def rollbackResource(String workDir, resource, String tag, String releaseSha, String originSha, Map published) {
    echo "=== Rolling back ${coordinates(resource)} ${resource.targetVersion} ==="
    def done = []
    def failed = []
    withRepositoryUrl(resource.scmUrl) { authUrl ->
        dir(workDir) {
            if (published.branch) {
                if (sh(script: "git push --force-with-lease='refs/heads/${resource.branch}:${published.branch}' \"${authUrl}\" '${originSha}:refs/heads/${resource.branch}'", returnStatus: true) == 0) {
                    done << "${resource.branch} reset to ${shortSha(originSha)}"
                } else {
                    failed << "${resource.branch} NOT reset to ${shortSha(originSha)} (changed on the remote since the release, or push refused)"
                }
            }
            if (published.master) {
                if (sh(script: "git push --force-with-lease='refs/heads/${resource.masterBranch}:${published.master}' \"${authUrl}\" '${published.masterBefore}:refs/heads/${resource.masterBranch}'", returnStatus: true) == 0) {
                    done << "${resource.masterBranch} reset to ${shortSha(published.masterBefore)}"
                } else {
                    failed << "${resource.masterBranch} NOT reset to ${shortSha(published.masterBefore)} (changed on the remote since the release, or push refused)"
                }
            }
            if (published.tag) {
                def remoteTagSha = null
                try {
                    remoteTagSha = sh(script: "out=\$(git ls-remote \"${authUrl}\" 'refs/tags/${tag}^{}') || exit 1; printf '%s\\n' \"\$out\" | cut -f1", returnStdout: true).trim()
                } catch (Throwable e) {
                    failed << "tag ${tag} : remote not readable, check it by hand"
                }
                if (remoteTagSha == null) {
                    // already reported
                } else if (!remoteTagSha) {
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
            if (!published.branch && !published.master && !published.tag) {
                done << 'nothing was pushed'
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
 * Lets an abort of the build (or a timeout) go through a catch that would otherwise go on with the next resource.
 */
def rethrowIfAborted(Throwable e) {
    if (e instanceof InterruptedException) {
        throw e
    }
}

/**
 * Runs one check and returns its result : OK, or the given failure status (TO_FIX or WARNING) with the error lines. A failed check never
 * stops the verification of the other checks nor of the other resources, an abort of the build does.
 */
def runCheck(String check, String failureStatus, Closure body) {
    try {
        body()
        return [check: check, status: 'OK', details: '']
    } catch (Throwable e) {
        rethrowIfAborted(e)
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
 * Fails when the POM of a stable version declares beta or RC artifacts, as listed by prereleaseProblems.
 */
def checkNoPrerelease(List problems) {
    if (!problems.isEmpty()) {
        error("A stable version rests on beta or RC artifacts : ${problems.join(' ; ')}")
    }
}

/**
 * The SNAPSHOT problems that the campaign will not release (step 0) : a SNAPSHOT parent or dependency whose "groupId:artifactId" is not in
 * the campaign coordinates of the plan.
 */
def outsideCampaign(String workDir, List problems) {
    def campaign = (plan().campaignCoordinates ?: []) as Set
    def outside = []
    for (def problem : problems) {
        def parts = problem.split(':')
        def coords = problem.startsWith('parent ') ? parentCoordinates(workDir) : "${parts[0]}:${parts[1]}".toString()
        if (!campaign.contains(coords)) {
            outside.add(problem)
        }
    }
    return outside
}

/**
 * The "groupId:artifactId" of the parent of the POM in a directory, empty when it has none.
 */
def parentCoordinates(String workDir) {
    def id = ''
    dir(workDir) {
        id = sh(script: "mvn -s ${env.MAVEN_SETTINGS_XML} -N -q help:evaluate -Dexpression=project.parent.id -DforceStdout 2>/dev/null | tail -1 || true", returnStdout: true).trim()
    }
    def parts = id.split(':')
    return parts.size() >= 2 ? "${parts[0]}:${parts[1]}".toString() : ''
}

/**
 * Fails when the head of the branch has no commit since the last stable release tag of the resource but the ones written by the releases
 * (maven-release-plugin, releaser, step pipeline) : the release would publish the same code again. The beta and RC tags are skipped, a
 * stable released from a beta without change is expected. Only a warning. No tag : first release.
 */
def checkSomethingToRelease(String workDir, resource) {
    dir(workDir) {
        def exclude = "--exclude '*-beta-*' --exclude '*-RC-*'"
        def tag = sh(script: "git describe --tags --abbrev=0 --match '${resource.artifactId}-[0-9]*' ${exclude} 2>/dev/null || git describe --tags --abbrev=0 --match 'v[0-9]*' ${exclude} 2>/dev/null || true", returnStdout: true).trim()
        if (!tag) {
            return
        }
        checkTagName(tag)
        if (realCommitsSince(tag).isEmpty()) {
            error("Nothing to release : no commit since ${tag}")
        }
    }
}

/**
 * Fails when a stable release would publish commits made after the last beta or RC tag of its version : code never tested in a pre-release.
 * No beta nor RC tag of the version : nothing to check. The tag is kept in result.untestedSinceTag, for the releaser to offer a release from it.
 */
def checkUntestedCommits(String workDir, resource, String version, result) {
    def base = version.replaceFirst(/-SNAPSHOT$/, '')
    dir(workDir) {
        def patterns = ["${resource.artifactId}-${base}", "v${base}"].collect { "'refs/tags/${it}-beta-*' 'refs/tags/${it}-RC-*'" }.join(' ')
        def tag = sh(script: "git for-each-ref --sort=-creatordate --count=1 --format='%(refname:lstrip=2)' ${patterns}", returnStdout: true).trim()
        if (!tag) {
            return
        }
        checkTagName(tag)
        def commits = realCommitsSince(tag)
        if (!commits.isEmpty()) {
            result.untestedSinceTag = tag
            error("${commits.size()} commit(s) after ${tag}, never tested in a beta or RC : release the component apart, or from ${tag}, or from the SNAPSHOT anyway\n" +
                    commits.take(5).collect { "- ${it}" }.join('\n'))
        }
    }
}

/**
 * The subjects of the commits since a tag, in the current directory, but the merges and the ones written by the releases : the
 * releaser, the step pipeline and the release pipeline. Other "chore: ..." commits are real commits.
 */
def realCommitsSince(String tag) {
    def log = sh(script: "git log --no-merges --format=%s '${tag}..HEAD' | grep -v -E '^(\\[(maven-release-plugin|site-release|release-from-tag)\\]|release: |chore: (prepare next development iteration|update versions|restore SNAPSHOT))' || true", returnStdout: true)
    return log.readLines().findAll { it.trim() }
}

/**
 * Fails when the resolved dependency tree holds SNAPSHOT artifacts the POM does not declare itself : a direct dependency whose version
 * range resolved to a SNAPSHOT (a Lutece range such as [7.0.0,8.0.0) may resolve to a SNAPSHOT of the core), or a dependency of a
 * dependency. The artifacts of the campaign (step 0) are ignored. Only a warning.
 */
def checkNoTransitiveSnapshot(String workDir, resource, List directProblems, Set ignoredCoordinates) {
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
            if (!direct.contains(gav) && !ignoredCoordinates.contains("${m.group(2)}:${m.group(3)}".toString())) {
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
            sha = sh(script: "out=\$(git ls-remote \"${authUrl}\" 'refs/tags/${tag}') || exit 1; printf '%s\\n' \"\$out\" | cut -f1", returnStdout: true).trim()
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
 * WARNING), the commit verified (head of the branch at clone time, or commit of the sourceTag the resource is released from) and the result
 * of each check.
 */
def verifyResource(resource, boolean isAggregate) {
    def precheck = isPrecheck()
    def result = [status: 'OK', version: precheck ? resource.currentVersion : resource.targetVersion, commit: '', checks: []]
    if (isAggregate) {
        result.note = 'verified before the version updates'
    }
    if (precheck) {
        echo "=== Testing ${coordinates(resource)} ${resource.currentVersion} as it is on the head of ${resource.branch} ==="
    } else {
        echo "=== Verifying ${coordinates(resource)} ${resource.currentVersion} -> ${resource.targetVersion} (branch ${resource.branch}) ==="
    }
    def workDir = null
    def clone = runCheck('clone', 'TO_FIX') {
        workDir = cloneResource(resource)
        result.commit = readFile("${workDir}/.git/releaser-origin-sha").trim()
    }
    if (clone.status == 'OK' && resource.sourceTag && !precheck) {
        result.sourceTag = resource.sourceTag.toString()
        echo "Verifying from tag ${result.sourceTag}"
        clone = runCheck('source-tag', 'TO_FIX') { result.commit = checkoutSourceTag(workDir, resource) }
    }
    if (clone.status != 'OK') {
        result.checks << clone
    } else {
        withJdk(detectTargetJdk(workDir)) {
            if (mustRunTests(resource)) {
                result.checks << runCheck('tests', 'TO_FIX') { runTests(workDir, resource) }
                cleanWorkTree(workDir)
            }
            def versions = [status: 'OK']
            if (!precheck) {
                versions = runCheck('versions', 'TO_FIX') {
                    if (resource.parentVersion && !isAggregate) {
                        setParentVersion(workDir, resource.parentVersion)
                    }
                    if (!isAggregate) {
                        setResourceVersion(workDir, resource, resource.targetVersion)
                    }
                }
                result.checks << versions
            }
            if (versions.status == 'OK') {
                if (!isAggregate) {
                    def declared = []
                    def directProblems = []
                    def ignored = precheck ? ((plan().campaignCoordinates ?: []) as Set) : ([] as Set)
                    result.checks << runCheck('snapshot-dependencies', 'TO_FIX') {
                        declared = declaredVersions(workDir)
                        directProblems = snapshotProblems(declared)
                        if (precheck) {
                            def outside = outsideCampaign(workDir, directProblems)
                            if (!outside.isEmpty()) {
                                error("SNAPSHOT artifacts outside the campaign, never released by it : ${outside.join(' ; ')}")
                            }
                        } else {
                            checkNoDirectSnapshot(directProblems)
                        }
                    }
                    if (mustCheckPrereleases(false)) {
                        result.checks << runCheck('prerelease-dependencies', 'TO_FIX') { checkNoPrerelease(prereleaseProblems(declared)) }
                    }
                    result.checks << runCheck('transitive-snapshot-dependencies', 'WARNING') { checkNoTransitiveSnapshot(workDir, resource, directProblems, ignored) }
                }
                result.checks << runCheck('build', 'TO_FIX') {
                    dir(workDir) {
                        runLogged(resource, 'Build of the release profile, nothing pushed (mvn clean verify)', "mvn -s ${env.MAVEN_SETTINGS_XML} -q clean verify -DperformRelease=true -DskipTests")
                    }
                }
            }
            if (precheck) {
                result.checks << runCheck('something-to-release', 'WARNING') { checkSomethingToRelease(workDir, resource) }
            } else {
                result.checks << runCheck('tag', 'TO_FIX') { checkTagAbsent(workDir, resource) }
            }
            if (plan().releaseType == 'STABLE') {
                result.checks << runCheck('untested-commits', 'TO_FIX') { checkUntestedCommits(workDir, resource, result.version, result) }
            }
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
    def line = "${result.status == 'OK' ? 'VERIFIED' : (result.status == 'TO_FIX' ? 'TO FIX' : 'WARNING')} ${coordinates(resource)} ${result.version}" +
            (result.commit ? " (commit ${shortSha(result.commit)})" : '') + (result.note ? " — ${result.note}" : '')
    def details = result.checks.findAll { it.status != 'OK' }.collect { check ->
        def lines = check.details.readLines().findAll { it.trim() }
        "  - ${check.check} : ${lines ? lines[0] : ''}\n" + formatDetails(lines).readLines().take(15).collect { "  ${it}\n" }.join('')
    }.join('')
    appendReport((line + '\n' + details).trim())
}

/**
 * Number of resources verified at the same time, sent by the releaser in the plan : 1 when absent.
 */
def verifyParallelism() {
    def value = plan().parallelism
    return value ? Math.max(1, value as int) : 1
}

/**
 * Runs a body alone among the parallel branches. Jenkins only switches branches on a step : the test and the set of the flag cannot be
 * split, the sleep is the only step of the wait.
 */
def withReportLock(Closure body) {
    while (reportLock.busy) {
        sleep(time: 1, unit: 'SECONDS')
    }
    reportLock.busy = true
    try {
        body()
    } finally {
        reportLock.busy = false
    }
}

/**
 * Result of a resource whose verification failed outside its checks (clone, JDK, work tree) : to fix, with the error.
 */
def verificationError(resource, Throwable e) {
    return [status: 'TO_FIX', version: isPrecheck() ? resource.currentVersion : resource.targetVersion, commit: '',
            checks: [[check: 'verification', status: 'TO_FIX', details: "Verification interrupted : ${e.message ?: e.class.simpleName}".toString()]]]
}

/**
 * Verifies the components, by N workers taking the next one from a shared queue. The verification report and the step report are
 * written under the lock, as several branches end at the same time.
 */
def verifyComponents(components, report) {
    def queue = new ArrayList(components)
    def workerCount = Math.min(verifyParallelism(), Math.max(1, queue.size()))
    def worker = {
        while (!queue.isEmpty()) {
            def component = queue.remove(0)
            def result
            try {
                result = verifyResource(component, false)
            } catch (Throwable e) {
                rethrowIfAborted(e)
                // An error outside the checks still gives the component a result, and the other workers go on
                result = verificationError(component, e)
            }
            withReportLock {
                report.components[coordinates(component)] = result
                writeVerifyReport(report)
                reportVerification(component, result)
            }
        }
    }
    if (workerCount == 1) {
        worker()
        return
    }
    echo "Verifying ${queue.size()} component(s), ${workerCount} at a time"
    def branches = [:]
    for (int i = 1; i <= workerCount; i++) {
        branches["worker-${i}"] = worker
    }
    // Maven 3.9 : lock the shared local repository while several builds download into it
    withEnv(["MAVEN_OPTS=${env.MAVEN_OPTS ?: ''} -Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav"]) {
        parallel branches
    }
}

/**
 * Stage 2 (mode VERIFY) — Verify the components, then the aggregate, the verification report growing after each one.
 */
def stageVerifyComponents() {
    def thePlan = plan()
    def report = [mode: env.STEP_MODE, components: [:], aggregate: null]
    writeVerifyReport(report)
    verifyComponents(thePlan.components, report)
    if (thePlan.aggregate) {
        def result
        try {
            result = verifyResource(thePlan.aggregate, true)
        } catch (Throwable e) {
            rethrowIfAborted(e)
            result = verificationError(thePlan.aggregate, e)
        }
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
 * references versions that exist. The suffix tells in the report where they are applied, for an aggregate released from a tag.
 */
def applyVersionUpdates(String workDir, aggregate, String suffix = '') {
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
            appendReport("${coordinates(aggregate)} : parent POM set to ${aggregate.parentVersion}${suffix}")
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
    if ((!thePlan.stepCode && planMode(thePlan) != 'PRECHECK') || thePlan.components == null) {
        error('RELEASE_PLAN is not a step plan : stepCode and components are required.')
    }
    env.PLAN_JSON = params.RELEASE_PLAN
    env.STEP_MODE = planMode(thePlan)
    env.DRY_RUN = env.STEP_MODE == 'DRY_RUN' ? 'true' : 'false'
    sh "rm -rf '${env.WORK_DIR}' && mkdir -p '${env.WORK_DIR}'"
    writeReport([releasedVersions: [:], failedVersions: [:], notProcessed: [], pomUpdated: false, aggregateVersion: null, report: ''])
    validatePlan(thePlan)
    try {
        checkCredentials(thePlan)
    } catch (e) {
        appendReport("Plan rejected before any change : ${e.message}")
        throw e
    }
    writeAskpassScript()
    writeMavenWrapper()

    configFileProvider([configFile(fileId: params.MAVEN_SETTINGS_ID, variable: 'MVN_SETTINGS_TMP')]) {
        sh "cp \${MVN_SETTINGS_TMP} ${WORKSPACE}/maven-settings.xml"
    }
    env.MAVEN_SETTINGS_XML = "${WORKSPACE}/maven-settings.xml"

    echo "=========================================="
    echo " Lutece Platform Step Pipeline"
    echo " Campaign    : #${thePlan.campaignId} ${thePlan.campaignName}"
    echo " Step        : ${thePlan.step} ${thePlan.stepCode ?: 'test of the SNAPSHOT of the campaign'}"
    echo " Type        : ${thePlan.releaseType} (core ${thePlan.coreMajor})"
    echo " Mode        : ${env.STEP_MODE}"
    echo " Components  : ${thePlan.components.size()}"
    echo " Aggregate   : ${thePlan.aggregate ? coordinates(thePlan.aggregate) + ' ' + thePlan.aggregate.currentVersion + ' -> ' + thePlan.aggregate.targetVersion : '(none)'}"
    echo "=========================================="
    if (isPrecheck()) {
        appendReport("Step 0 — test of the SNAPSHOT of campaign #${thePlan.campaignId} ${thePlan.campaignName} — ${thePlan.components.size()} resource(s), core ${thePlan.coreMajor}")
    } else {
        appendReport("Step ${thePlan.step} ${thePlan.stepCode} of campaign #${thePlan.campaignId} ${thePlan.campaignName} — ${thePlan.releaseType} — ${isVerify() ? 'verification' : 'dry run : ' + env.DRY_RUN}")
    }
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
            rethrowIfAborted(e)
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
 * An aggregate released from a tag (the core) gets them on top of that tag, the branch getting them after the release.
 */
def updateAggregatePom(aggregate) {
    def workDir = cloneResource(aggregate)
    env.AGGREGATE_DIR = workDir
    if (aggregate.sourceTag && isAggregateReleased() && !hasFailures()) {
        checkoutSourceTag(workDir, aggregate)
        appendReport("${coordinates(aggregate)} : version updates applied on ${aggregate.sourceTag}")
    }
    applyVersionUpdates(workDir, aggregate)

    dir(workDir) {
        sh 'git add -u'
    }
    checkStagedFiles(workDir, aggregate)
    dir(workDir) {
        sh """
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
    lines.add('  Failed (rolled back in git ; error lines, rollback and Nexus state above) :')
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
        if (isVerify()) {
            appendReport("FAILED at ${new Date()} : verification interrupted, the resources missing from the verification report were not verified. Nothing was committed nor pushed.")
            return
        }
        def report = readReport()
        if (!report.notProcessed) {
            def released = report.releasedVersions ?: [:]
            def failed = report.failedVersions ?: [:]
            report.notProcessed = plan().components.findAll { !released.containsKey(coordinates(it)) && !failed.containsKey(coordinates(it)) }
                    .collect { coordinates(it) }
            writeReport(report)
        }
        if (!hasFailures()) {
            def released = report.releasedVersions ?: [:]
            appendReport(released ? "FAILED at ${new Date()} : the resources listed in releasedVersions were released, the others were not. Prepare the step again in the releaser to release the remaining ones."
                                  : "FAILED at ${new Date()} : nothing was released. See the lines above for the cause.")
        }
    } catch (Throwable e) {
        echo "Could not complete the report : ${e.message}"
    }
}

// Required : return 'this' so that load() can assign the script to a variable
return this
