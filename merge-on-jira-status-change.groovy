pipeline {
    agent any
    options { timestamps() }
    stages {
        stage('Find proper pull request') {
            steps {

                script {
                    currentBuild.description = "Received notification about changed status.<br>"
                    currentBuild.displayName = "Processing $JIRA_ISSUE_KEY"
                    def pullRequestData = getMatchPullRequestsByJiraIssueKey(JIRA_ISSUE_KEY, ALLOWED_DESTINATION)

                    env.IS_MATCHED = pullRequestData.result
                    if (pullRequestData.result == true) {
                        env.PULL_REQUEST_ID = pullRequestData.PULL_REQUEST_ID
                        env.SOURCE_COMMIT = pullRequestData.SOURCE_COMMIT
                    } else {
                        currentBuild.description += "No matching pull requests found<br>"
                    }
                }
            }
        }
        stage('Merging branch') {
            when {
                expression { IS_MATCHED == "true" }
            }
            steps {
                script {

                    String slackComment = ''
                    String slackColor = 'good'

                    def data = executeAWSCliCommand("codecommit", "get-pull-request", ["pull-request-id": PULL_REQUEST_ID]);
                    def pullRequest = data.pullRequest

                    def source = pullRequest.pullRequestTargets[0].sourceReference;
                    def sourceCommit = pullRequest.pullRequestTargets[0].sourceCommit;
                    def repositoryName = pullRequest.pullRequestTargets[0].repositoryName;
                    def destination = pullRequest.pullRequestTargets[0].destinationCommit;


                    try {
                        def scm = checkout changelog: false, poll: false, scm: [
                                $class                           : 'GitSCM',
                                branches                         : [[name: source]],
                                doGenerateSubmoduleConfigurations: false,
                                extensions                       : [
                                        [$class: 'CleanBeforeCheckout']

                                ],
                                submoduleCfg                     : [],
                                userRemoteConfigs                : [[url: SSH_WEBSTORE_REPO]]
                        ]

                        sh "git merge origin/stage --no-commit";
                        sh "git commit -m 'Merge `PR-$PULL_REQUEST_ID` ($JIRA_ISSUE_KEY) into `stage`' || true"
                        sh "git push origin HEAD:stage"

                        result executeAWSCliCommand("codecommit", "merge-pull-request-by-fast-forward", [
                            "pull-request-id": PULL_REQUEST_ID,
                            "repository-name": repositoryName
                        ])
                        println result;
                        sh "git push origin :$source";

                        println "Merged successfully";
                        slackComment = "Successfully merged PR-${PULL_REQUEST_ID} (${JIRA_ISSUE_KEY})."
                        jiraComment body: "Successfully merged PR-${PULL_REQUEST_ID}.", issueKey: JIRA_ISSUE_KEY

                    } catch (Exception e) {

                        println "Error has been occured during merge";
                        println e;
                        slackComment = "Cannot merge PR-${PULL_REQUEST_ID} (${JIRA_ISSUE_KEY}).\nPlease resolve branch conflicts."
                        jiraComment body: "Cannot merge PR-${PULL_REQUEST_ID}.\nPlease resolve branch conflicts.", issueKey: JIRA_ISSUE_KEY
                        jiraTransitionIssueByName(JIRA_ISSUE_KEY, "Merge Failed")
                        slackColor = 'FF0000'

                    }

                    if (slackComment != '') {
                        withCredentials([string(credentialsId: 'jenkins-bot-oauth-key', variable: 'TOKEN')]) {
                            def profiles = slackGetUserList(TOKEN)
                            println JIRA_ISSUE_ASSIGNEE_EMAIL
                            if (profiles.containsKey(JIRA_ISSUE_ASSIGNEE_EMAIL)) {
                                def item = profiles[JIRA_ISSUE_ASSIGNEE_EMAIL]
                                slackSend color: slackColor, message: slackComment, channel: "@${item.name}"
                            }
                        }
                    }
                }
            }
        }
    }
}

