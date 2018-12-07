timestamps {
  node {
    def IS_MATCHED;
    def PULL_REQUEST_ID;
    def SOURCE_COMMIT;
    def SOURCE_REFERENCE;
    def SLACK_USER_NAME;
    def IS_PRE_MERGE_COMMIT_CREATED;

    stage('Finding of proper pull request') {

      script {
        currentBuild.description = "Received notification about changed status.<br>"
        currentBuild.displayName = "Processing $JIRA_ISSUE_KEY"
        def pullRequestData = getMatchPullRequestsByJiraIssueKey(JIRA_ISSUE_KEY, ALLOWED_DESTINATION)

        IS_MATCHED = pullRequestData.result

        print pullRequestData;

        if (pullRequestData.result == true) {
          PULL_REQUEST_ID = pullRequestData.PULL_REQUEST_ID
          SOURCE_COMMIT = pullRequestData.SOURCE_COMMIT
          SOURCE_REFERENCE = pullRequestData.SOURCE_REFERENCE
        } else {
          currentBuild.description += "No matching pull requests found<br>"
        }

        withCredentials([string(credentialsId: 'jenkins-bot-oauth-key', variable: 'TOKEN')]) {
          def profiles = slackGetUserList(TOKEN)
          println JIRA_ISSUE_ASSIGNEE_EMAIL
          if (profiles.containsKey(JIRA_ISSUE_ASSIGNEE_EMAIL)) {
            def item = profiles[JIRA_ISSUE_ASSIGNEE_EMAIL]
            SLACK_USER_NAME = item.name
          }
        }
      }
    }

    if (IS_MATCHED) {
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
        //if (true) {

        def codecommitLink = "https://eu-west-1.console.aws.amazon.com/codecommit/home"
        def prLink = "<${codecommitLink}?region=eu-west-1&status=OPEN#/repository/webstore/pull-request/$PULL_REQUEST_ID/changes|PR-${PULL_REQUEST_ID}>"

        try {
          stage('Waiting for Approval') {

            def prResolutionLink = "<${BUILD_URL}input|here>"
            slackSend color: 'C0C0C0', message: "$prLink (${JIRA_ISSUE_KEY}) waiting for your approval ${prResolutionLink}.", channel: "@${reviewerSlackName}"
            slackSend color: 'C0C0C0', message: "$prLink (${JIRA_ISSUE_KEY}) have no conflicts.\nWaiting for approval of reviewer.", channel: "@${SLACK_USER_NAME}"
            input message: "Is PR-$PULL_REQUEST_ID ok?", submitter: reviewerJenkinsName, id: 'code-review-input'

          }
        } catch (Exception e) {
          slackSend color: 'FF0000', message: "Your $prLink (${JIRA_ISSUE_KEY}) is declined by reviewer.", channel: "@${SLACK_USER_NAME}"
          return
        }
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

          if (IS_PRE_MERGE_COMMIT_CREATED) {
            sh "git commit --amend --no-edit"
          } else {
            sh "git commit --author 'Jenkins-CI <jenkins@playwing.com>' -m 'Merge `PR-$PULL_REQUEST_ID` ($JIRA_ISSUE_KEY) into `stage`'"
          }

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
}