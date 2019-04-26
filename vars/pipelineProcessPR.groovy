def call(CALLBACK_BODY, APP_PREFIX, BILLING_API_HOST, buildCallback, onCloseCallback, awsProfileName, repoName) {
  node {

    def NOTIFICATION_TYPE;
    def PULL_REQUEST_STATUS;
    def PULL_REQUEST_TITLE;
    def PULL_REQUEST_SOURCE_COMMIT;
    def PULL_REQUEST_SOURCE_REFERENCE;
    def PULL_REQUEST_DESTINATION_COMMIT;
    def PULL_REQUEST_DESTINATION_REFERENCE;
    def PULL_REQUEST_IS_MERGED;

    def APP_ID;

    def JIRA_ISSUE_KEY;
    def JIRA_ISSUE_ASSIGNEE_EMAIL;

    def SLACK_USER_NAME;

    stage('Parse Notification') {
      prInfo = getPullRequestInfoByNotification(CALLBACK_BODY, awsProfileName);
      NOTIFICATION_TYPE = prInfo.NOTIFICATION_TYPE

      PULL_REQUEST_STATUS = prInfo.PULL_REQUEST_STATUS
      PULL_REQUEST_TITLE = prInfo.PULL_REQUEST_TITLE
      PULL_REQUEST_SOURCE_COMMIT = prInfo.PULL_REQUEST_SOURCE_COMMIT
      PULL_REQUEST_SOURCE_REFERENCE = prInfo.PULL_REQUEST_SOURCE_REFERENCE
      PULL_REQUEST_DESTINATION_COMMIT = prInfo.PULL_REQUEST_DESTINATION_COMMIT
      PULL_REQUEST_DESTINATION_REFERENCE = prInfo.PULL_REQUEST_DESTINATION_REFERENCE
      PULL_REQUEST_IS_MERGED = prInfo.PULL_REQUEST_IS_MERGED

      APP_ID = "PR-" + prInfo.PULL_REQUEST_ID + "-" + APP_PREFIX

      currentBuild.displayName = "PR ${prInfo.PULL_REQUEST_ID} ${prInfo.NOTIFICATION_TYPE}D ${APP_PREFIX}"

      print prInfo
      println "Successfully parsed."

      JIRA_ISSUE_KEY = getJiraIssueKeyFromPullRequestReference PULL_REQUEST_SOURCE_REFERENCE

      def JIRA_ISSUE_FIELDS = getJiraFieldsByIssueKey JIRA_ISSUE_KEY
      JIRA_ISSUE_ASSIGNEE_EMAIL = JIRA_ISSUE_FIELDS.assignee.emailAddress


      withCredentials([string(credentialsId: 'jenkins-bot-oauth-key', variable: 'TOKEN')]) {
        def profiles = slackGetUserList(TOKEN)
        if (profiles.containsKey(JIRA_ISSUE_ASSIGNEE_EMAIL)) {
          def item = profiles[JIRA_ISSUE_ASSIGNEE_EMAIL]
          SLACK_USER_NAME = item.name;
        } else {
          println "Not able to resolve $JIRA_ISSUE_ASSIGNEE_EMAIL"
        }
      }
    }


    if ((NOTIFICATION_TYPE == 'CREATE') ||
        (NOTIFICATION_TYPE == 'UPDATE' && PULL_REQUEST_STATUS == 'OPEN')) {


      def commitInfo = executeAWSCliCommand("codecommit", "get-commit", [
          "commit-id"      : PULL_REQUEST_SOURCE_COMMIT,
          "repository-name": repoName,
          "profile"        : awsProfileName
      ])

      if (commitInfo.commit.author.email == 'jenkins@playwing.com') {
        currentBuild.result = 'ABORTED'
        currentBuild.description = "Pre-merge commit from jenkins - skipped build."
        return;
      }


      stage('Build') {
        currentBuild.description = "<i>$PULL_REQUEST_TITLE</i>"
        def appConfigFile = getAppConfigFilePath(APP_ID)
        def appDirectory = getAppWorkspacePath(APP_ID)

        sh "mkdir /var/app/${APP_ID}/config -p"
        sh "mkdir $appDirectory/html/web -p"
        // TODO `build in progress` screen
        def buildVariables = resolveBuildVariables(appConfigFile, BILLING_API_HOST)

        print buildVariables;

        def stageBuild = buildCallback(APP_ID, PULL_REQUEST_SOURCE_COMMIT, appDirectory, buildVariables)

        if (stageBuild.result == 'FAILURE' || stageBuild.result == 'ABORTED') {
          def failedDownstreamBuilds = getJenkinsDownstreamBuilds(stageBuild, true)
          def url = stageBuild.getAbsoluteUrl()
          for (failedBuild in failedDownstreamBuilds) {
            url = failedBuild.getAbsoluteUrl();
            break;
          }
          failedDownstreamBuilds = null

          def comment = "Build for ${APP_ID} (${JIRA_ISSUE_KEY}) has been failed.\nSee ${url}console for details."
          slackSend color: '#FF0000', message: comment, channel: "@$SLACK_USER_NAME"

          build job: 'ci-change-status-of-webserver', parameters: [
              string(name: 'UPSTREAM_BUILD_STATUS', value: 'FAILURE'),
              string(name: 'DIRECTORY_NAME', value: appDirectory),
              string(name: 'UPSTREAM_BUILD_URL', value: url)
          ]
          sh "exit 143";
        } else {
          build job: 'ci-change-status-of-webserver', parameters: [
              string(name: 'UPSTREAM_BUILD_STATUS', value: 'SUCCESS'),
              string(name: 'DIRECTORY_NAME', value: appDirectory)
          ]
          println "Successfully built."

          def comment;
          if (NOTIFICATION_TYPE == 'CREATE') {
            comment = "Stage has been succesfully created.\nhttp://${APP_ID}.jenkins.playwing.com";
            println "Adding comment to $JIRA_ISSUE_KEY"
            jiraComment body: comment, issueKey: JIRA_ISSUE_KEY
          } else {
            comment = "Stage for ${APP_ID} ($JIRA_ISSUE_KEY) has been successfully updated.";
          }
          slackSend color: 'good', message: comment, channel: "@$SLACK_USER_NAME"
        }

        setupNginxVirtualHost(APP_ID);
      }
    }

    if ((NOTIFICATION_TYPE == 'UPDATE' && PULL_REQUEST_STATUS == 'CLOSED')) {
      currentBuild.description = "PR is closed.<br>Removing stage"
      build job: 'ci-remove-stage-server', parameters: [
          string(name: 'APP_ID', value: APP_ID)
      ]
      println "Successfully removed"
      if (PULL_REQUEST_IS_MERGED) {
        onCloseCallback(PULL_REQUEST_SOURCE_REFERENCE)
        println "Removed branch";
      }
    }
  }
}
