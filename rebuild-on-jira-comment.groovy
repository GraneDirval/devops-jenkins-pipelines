pipeline {
    agent any
    options { timestamps() }
    stages {
        stage('Matching pull request with Jira ticket') {
            steps {
                script {

                    currentBuild.displayName = "Processing $JIRA_ISSUE_KEY"
                    def pullRequestData = getMatchPullRequestsByJiraIssueKey(JIRA_ISSUE_KEY, ALLOWED_DESTINATION)

                    env.IS_MATCHED = pullRequestData.result
                    if (pullRequestData.result) {
                        env.PULL_REQUEST_ID = pullRequestData.PULL_REQUEST_ID
                        env.SOURCE_COMMIT = pullRequestData.SOURCE_COMMIT
                    } else {
                        currentBuild.description = "No matching pull requests found"
                    }
                }
            }
        }
        stage('Build server') {
            when {
                expression { IS_MATCHED == "true" }
            }
            steps {
                script {
                    env.APP_ID = "PR-$PULL_REQUEST_ID"
                    env.STAGE_URL = "http://${env.APP_ID}.jenkins.playwing.com"

                    def appConfigFile = getAppConfigFilePath(APP_ID)
                    def appDirectory = getAppWorkspacePath(APP_ID)
                    def buildVariables = resolveBuildVariables(appConfigFile, 'http://billing.playwing.com/api')

                    doBuild(APP_ID, SOURCE_COMMIT, appDirectory, buildVariables)
                    setupNginxVirtualHost(APP_ID);

                }
            }
        }
        stage('Send notifications') {
            when {
                expression { IS_MATCHED == "true" }
            }
            steps {
                script {
                    def response = jiraGetComments idOrKey: JIRA_ISSUE_KEY

                    def pattern = /.*Rebuild this.*/
                    def lastCommentAuthor = null
                    for (comment in response.data.comments) {

                        def expression = (comment.body =~ pattern)
                        if (expression.find()) {
                            lastCommentAuthor = comment.author.emailAddress
                        }
                    }

                    if (lastCommentAuthor) {
                        withCredentials([string(credentialsId: 'jenkins-bot-oauth-key', variable: 'TOKEN')]) {
                            def profiles = slackGetUserList(TOKEN)

                            if (profiles.containsKey(lastCommentAuthor)) {
                                def item = profiles[lastCommentAuthor]
                                slackSend color: 'good', message: "Rebuild of ${JIRA_ISSUE_KEY} was successful.\n$STAGE_URL", channel: "@${item.name}"
                            } else {
                                println "Cannot find slack account for $lastCommentAuthor"
                            }
                        }
                    } else {
                        println "Cannot find comment that matches pattern `$pattern`. Probably misconfiguration issue, or comment is already deleted"
                    }
                }

                jiraComment body: "Rebuild was successful.\n$STAGE_URL", issueKey: JIRA_ISSUE_KEY

            }
        }
    }
}
