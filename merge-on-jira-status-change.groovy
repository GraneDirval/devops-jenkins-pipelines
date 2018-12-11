def isTimeoutException(Exception err) {
  return 'SYSTEM' == err.getCauses()[0].getUser().toString()
}

timestamps {
  node {

    currentBuild.displayName = "Processing $JIRA_ISSUE_KEY"

    def builds = getJenkinsBuilds(JOB_NAME, true)
    for (build in builds) {
      if (build != currentBuild.getRawBuild()) {
        def parameters = getJenkinsBuildParameters(build);
        if (JIRA_ISSUE_KEY == parameters['JIRA_ISSUE_KEY']) {
          println "Issue $JIRA_ISSUE_KEY is already in process";
          currentBuild.description += "Issue is already in process - aborting.<br>";
          currentBuild.result = 'ABORTED'
          return;
        }
      }
    }
    builds = null; // SerializationException fix

    def IS_MATCHED;
    def PULL_REQUEST_ID;
    def SOURCE_COMMIT;
    def SOURCE_REFERENCE;
    def IS_PRE_MERGE_COMMIT_CREATED;

    stage('Finding of proper pull request') {
      script {
        currentBuild.description = "Received notification about changed status.<br>"
        def pullRequestData = getMatchPullRequestsByJiraIssueKey(JIRA_ISSUE_KEY, ALLOWED_DESTINATION)

        IS_MATCHED = pullRequestData.result

        print pullRequestData;

        if (pullRequestData.result == true) {
          PULL_REQUEST_ID = pullRequestData.PULL_REQUEST_ID
          SOURCE_COMMIT = pullRequestData.SOURCE_COMMIT
          SOURCE_REFERENCE = pullRequestData.SOURCE_REFERENCE
        }
      }
    }

    if (!IS_MATCHED) {
      println "No matching pull requests found for issue $JIRA_ISSUE_KEY"
      currentBuild.description += "No matching pull requests found - aborting.<br>"
      currentBuild.result = 'ABORTED'
      return;
    }


    def SLACK_USER_NAME;
    withCredentials([string(credentialsId: 'jenkins-bot-oauth-key', variable: 'TOKEN')]) {
      def profiles = slackGetUserList(TOKEN)
      println JIRA_ISSUE_ASSIGNEE_EMAIL
      if (profiles.containsKey(JIRA_ISSUE_ASSIGNEE_EMAIL)) {
        def item = profiles[JIRA_ISSUE_ASSIGNEE_EMAIL]
        SLACK_USER_NAME = item.name
      }
    }


    stage('Pre-review merging') {
      checkout changelog: true, poll: false, scm: [
          $class                           : 'GitSCM',
          branches                         : [[name: SOURCE_REFERENCE]],
          doGenerateSubmoduleConfigurations: false,
          extensions                       : [
              [$class: 'CleanBeforeCheckout'],
              [$class: 'ChangelogToBranch', options: [compareRemote: 'origin', compareTarget: 'stage']]

          ],
          submoduleCfg                     : [],
          userRemoteConfigs                : [[url: SSH_WEBSTORE_REPO]]
      ]

      try {
        sh "git merge origin/stage --no-commit";
      } catch (Exception e) {
        jiraComment body: "Cannot merge PR-${PULL_REQUEST_ID}.\nPlease resolve branch conflicts.", issueKey: JIRA_ISSUE_KEY
        jiraTransitionIssueByName(JIRA_ISSUE_KEY, "Merge Failed")

        def slackComment = "Cannot merge PR-${PULL_REQUEST_ID} (${JIRA_ISSUE_KEY}).\nPlease resolve branch conflicts."
        slackSend color: 'FF0000', message: slackComment, channel: "@${SLACK_USER_NAME}"
        return;
      }

      try {
        sh "git commit --author 'Jenkins-CI <jenkins@playwing.com>' -m 'Merge `PR-$PULL_REQUEST_ID` ($JIRA_ISSUE_KEY) into `stage`'"
        IS_PRE_MERGE_COMMIT_CREATED = true;
        println "Pre-review merge was successful";

      } catch (Exception e) {
        IS_PRE_MERGE_COMMIT_CREATED = false;
        println "Pre-review merge was skipped - nothing changed";

      }

      sh "git push origin HEAD:${SOURCE_REFERENCE}"


    }

    def reviewerSlackName;
    def reviewerJenkinsName;

    if (JIRA_ISSUE_PROJECT_KEY == "ST") {
      reviewerSlackName = "dmitriy.bichenko"
      reviewerJenkinsName = "dmitriy.bichenko"
    } else {
      reviewerSlackName = REVIEWER_SLACK_USER_NAME;
      reviewerJenkinsName = REVIEWER_JENKINS_USER_NAME;
    }


    if (reviewerSlackName != SLACK_USER_NAME) {
      /*  if (true) {*/

      def codecommitLink = "https://eu-west-1.console.aws.amazon.com/codecommit/home"
      def prLink = "<${codecommitLink}?region=eu-west-1&status=OPEN#/repository/webstore/pull-request/$PULL_REQUEST_ID/changes|PR-${PULL_REQUEST_ID}>"

      try {

        stage('Waiting for Approval') {

          def prResolutionLink = "<${BUILD_URL}input|here>"

          slackSend color: 'C0C0C0', message: "$prLink (${JIRA_ISSUE_KEY}) waiting for your approval ${prResolutionLink}.", channel: "@${reviewerSlackName}"
          slackSend color: 'C0C0C0', message: "$prLink (${JIRA_ISSUE_KEY}) have no conflicts.\nWaiting for approval of reviewer.", channel: "@${SLACK_USER_NAME}"

          while (true) {
            try {
              timeout(time: 60, unit: 'MINUTES') {
                input message: "Is PR-$PULL_REQUEST_ID ok?", submitter: reviewerJenkinsName, id: 'code-review-input'
              }
              break;
            } catch (Exception err) {

              if (!isTimeoutException(err)) {
                throw err
              }

              def response = executeAWSCliCommand("codecommit", "get-pull-request", ["pull-request-id": PULL_REQUEST_ID])
              if (response.pullRequest.pullRequestStatus == 'CLOSED') {
                slackSend color: 'C0C0C0', message: "$prLink (${JIRA_ISSUE_KEY}) is already closed. No need to review.\nProbably it was manually merged by someone or JIRA issue status change was triggered multiple times.", channel: "@${reviewerSlackName}"
                currentBuild.result = 'ABORTED'
                break;
              } else {

                if (isWorkingTime()) {
                  slackSend color: 'C0C0C0', message: "Reminder: ${prLink} (${JIRA_ISSUE_KEY}) is waiting for your approval ${prResolutionLink}.", channel: "@${reviewerSlackName}"
                }
              }
            }
          }
        }

      } catch (Exception e) {
        slackSend color: 'FF0000', message: "Your $prLink (${JIRA_ISSUE_KEY}) is declined by reviewer.", channel: "@${SLACK_USER_NAME}"
        jiraTransitionIssueByName(JIRA_ISSUE_KEY, "Changes Requested")
        jiraComment body: "Changes has been requested for PR-${PULL_REQUEST_ID}.", issueKey: JIRA_ISSUE_KEY

        return
      }

      if (currentBuild.result == 'ABORTED') {
        return;
      }

    } else {
      println "Reviewer ($reviewerSlackName) and commit author ($SLACK_USER_NAME) are same - skipping step"
    }


    stage("Pushing to remote") {

      try {
        sh "git merge origin/stage --no-commit";
      } catch (Exception e) {
        jiraComment body: "Cannot merge PR-${PULL_REQUEST_ID}.\nPlease resolve branch conflicts.", issueKey: JIRA_ISSUE_KEY
        jiraTransitionIssueByName(JIRA_ISSUE_KEY, "Merge Failed")

        def slackComment = "Cannot merge PR-${PULL_REQUEST_ID} (${JIRA_ISSUE_KEY}).\nPlease resolve branch conflicts."
        slackSend color: 'FF0000', message: slackComment, channel: "@${SLACK_USER_NAME}"
        return;
      }


      try {

        /*if (IS_PRE_MERGE_COMMIT_CREATED) {
          sh "git commit --amend --no-edit"
        } else {*/
        sh "git commit --author 'Jenkins-CI <jenkins@playwing.com>' -m 'Merge `PR-$PULL_REQUEST_ID` ($JIRA_ISSUE_KEY) into `stage`'"
        /*}*/

        println "Post-review merge was successful";
      } catch (Exception e) {

      }

      sh "git push origin HEAD:stage"
      println "Pushed successfully";


      jiraComment body: "Successfully merged PR-${PULL_REQUEST_ID}.", issueKey: JIRA_ISSUE_KEY
      def slackComment = "Successfully merged PR-${PULL_REQUEST_ID} (${JIRA_ISSUE_KEY})."
      slackSend color: 'good', message: slackComment, channel: "@${SLACK_USER_NAME}"
    }

  }
}