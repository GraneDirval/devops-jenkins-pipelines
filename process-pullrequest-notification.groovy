#!/usr/bin/env groovy

pipeline {
    agent any
    options { timestamps() }
    stages {
        stage('Parse Notification') {
            steps {
                script {
                    prInfo = getPullRequestInfoByNotification sqs_body;

                    env.NOTIFICATION_TYPE = prInfo.NOTIFICATION_TYPE

                    env.PULL_REQUEST_STATUS = prInfo.PULL_REQUEST_STATUS
                    env.PULL_REQUEST_TITLE = prInfo.PULL_REQUEST_TITLE
                    env.PULL_REQUEST_STATUS = prInfo.PULL_REQUEST_STATUS
                    env.PULL_REQUEST_SOURCE_COMMIT = prInfo.PULL_REQUEST_SOURCE_COMMIT
                    env.PULL_REQUEST_SOURCE_REFERENCE = prInfo.PULL_REQUEST_SOURCE_REFERENCE
                    env.PULL_REQUEST_DESTINATION_COMMIT = prInfo.PULL_REQUEST_DESTINATION_COMMIT
                    env.PULL_REQUEST_DESTINATION_REFERENCE = prInfo.PULL_REQUEST_DESTINATION_REFERENCE
                    env.PULL_REQUEST_IS_MERGED = prInfo.PULL_REQUEST_IS_MERGED

                    env.APP_ID = "PR-" + prInfo.PULL_REQUEST_ID

                    currentBuild.displayName = "PR ${prInfo.PULL_REQUEST_ID} ${prInfo.NOTIFICATION_TYPE}D"

                    print prInfo
                }
                echo "Successfully parsed."

                script {
                    env.JIRA_ISSUE_KEY = getJiraIssueKeyFromPullRequestReference PULL_REQUEST_SOURCE_REFERENCE

                    def JIRA_ISSUE_FIELDS = getJiraFieldsByIssueKey JIRA_ISSUE_KEY
                    env.JIRA_ISSUE_ASSIGNEE_EMAIL = JIRA_ISSUE_FIELDS.assignee.emailAddress
                }
            }
        }
        stage('Build') {
            when {
                anyOf {
                    expression { NOTIFICATION_TYPE == 'CREATE' }
                    expression { NOTIFICATION_TYPE == 'UPDATE' && PULL_REQUEST_STATUS == 'OPEN' }
                }

            }
            steps {
                script {
                    currentBuild.description = "<i>$PULL_REQUEST_TITLE</i>"

                    def appConfigFile = getAppConfigFilePath(APP_ID)
                    def appDirectory = getAppWorkspacePath(APP_ID)

                    sh "mkdir /var/app/${APP_ID}/config -p"
                    sh "mkdir $appDirectory/html/web -p"
                    // TODO `build in progress` screen

                    def buildVariables = resolveBuildVariables(appConfigFile)

                    print buildVariables;

                    def stageBuild = doBuild(APP_ID, PULL_REQUEST_SOURCE_COMMIT, appDirectory, buildVariables)

                    if (stageBuild.result == 'FAILURE' || stageBuild.result == 'ABORTED') {
                        withCredentials([string(credentialsId: 'jenkins-bot-oauth-key', variable: 'TOKEN')]) {
                            def failedDownstreamBuilds = getJenkinsDownstreamBuilds(stageBuild, true)
                            def url = stageBuild.getAbsoluteUrl()
                            for (failedBuild in failedDownstreamBuilds) {
                                url = failedBuild.getAbsoluteUrl();
                                break;
                            }
                            failedDownstreamBuilds = null

                            def comment = "Build for ${APP_ID} (${JIRA_ISSUE_KEY}) has been failed.\nSee ${url}console for details."
                            def profiles = slackGetUserList(TOKEN)

                            if (profiles.containsKey(JIRA_ISSUE_ASSIGNEE_EMAIL)) {
                                def item = profiles[JIRA_ISSUE_ASSIGNEE_EMAIL]
                                slackSend color: '#FF0000', message: comment, channel: "@${item.name}"
                            }

                            build job: 'ci-change-status-of-webserver', parameters: [
                                    string(name: 'UPSTREAM_BUILD_STATUS', value: 'FAILURE'),
                                    string(name: 'DIRECTORY_NAME', value: appDirectory),
                                    string(name: 'UPSTREAM_BUILD_URL', value: url)
                            ]
                        }
                        sh "exit 143";
                    } else {

                        build job: 'ci-change-status-of-webserver', parameters: [
                                string(name: 'UPSTREAM_BUILD_STATUS', value: 'SUCCESS'),
                                string(name: 'DIRECTORY_NAME', value: appDirectory)
                        ]

                        echo "Successfully built."
                    }

                    setupNginxVirtualHost(APP_ID);
                }
            }
        }
        stage("Remove") {
            when {
                expression { NOTIFICATION_TYPE == 'UPDATE' && PULL_REQUEST_STATUS == 'CLOSED' }
            }
            steps {
                script {
                    currentBuild.description = "PR is closed.<br>Removing stage"
                }

                build job: 'ci-remove-stage-server', parameters: [
                        string(name: 'APP_ID', value: APP_ID)
                ]
                echo "Successfully removed"


                script{
                    if(PULL_REQUEST_IS_MERGED){
                        sh "git push $SSH_WEBSTORE_REPO :$PULL_REQUEST_SOURCE_REFERENCE";
                        echo "Removed branch";
                    }
                }


            }
        }
        stage('Send notifications') {
            when {
                anyOf {
                    expression { NOTIFICATION_TYPE == 'CREATE' }
                    expression { NOTIFICATION_TYPE == 'UPDATE' && PULL_REQUEST_STATUS == 'OPEN' }
                }
            }
            steps {
                script {

                    def comment;

                    if (NOTIFICATION_TYPE == 'CREATE') {
                        comment = "Stage has been succesfully created.\nhttp://${APP_ID}.jenkins.playwing.com";
                        echo "Adding comment to $JIRA_ISSUE_KEY"
                        jiraComment body: comment, issueKey: JIRA_ISSUE_KEY
                    } else {
                        comment = "Stage for ${APP_ID} ($JIRA_ISSUE_KEY) has been successfully updated.";
                    }


                    withCredentials([string(credentialsId: 'jenkins-bot-oauth-key', variable: 'TOKEN')]) {

                        def profiles = slackGetUserList(TOKEN)
                        if (profiles.containsKey(JIRA_ISSUE_ASSIGNEE_EMAIL)) {
                            def item = profiles[JIRA_ISSUE_ASSIGNEE_EMAIL]
                            slackSend color: 'good', message: comment, channel: "@${item.name}"
                        } else {
                            println "Not able to resolve $JIRA_ISSUE_ASSIGNEE_EMAIL"
                        }
                    }
                }
            }
        }
    }
}