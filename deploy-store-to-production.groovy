node {
  def SCM_VARS;

  timestamps {

    currentBuild.displayName = "v${VERSION_TAG}"

    stage('Checkout') {
      SCM_VARS = checkout([
          $class                           : 'GitSCM',
          branches                         : [[name: 'stage']],
          doGenerateSubmoduleConfigurations: false,
          extensions                       : [
              [$class: 'CleanBeforeCheckout'],
              [$class: 'PreBuildMerge', options: [mergeRemote: 'origin', mergeTarget: 'master']]
          ],
          submoduleCfg                     : [],
          userRemoteConfigs                : [
              [url: SSH_WEBSTORE_REPO]
          ]
      ])

      sh "git fetch --tags"
      def isGitTagExists = sh(returnStdout: true, script: "git tag -l \"v${VERSION_TAG}\"").trim()

      if (isGitTagExists) {
        error("Tag v${VERSION_TAG} is already exists")
      }
    }

    stage('Update detection databases') {

      withCredentials([file(credentialsId: 'GeoIP.conf', variable: 'MAXMIND_CONF_FILE')]) {
        sh """
                geoipupdate -f ${MAXMIND_CONF_FILE} -d .
                rm .geoipupdate.lock -f
                
                mv GeoIP2-Connection-Type.mmdb var/geoip-databases/GeoIP2-Connection-Type.mmdb -f
                mv GeoIP2-Country.mmdb var/geoip-databases/GeoIP2-Country.mmdb -f
                mv GeoIP2-ISP.mmdb var/geoip-databases/GeoIP2-ISP.mmdb -f
                """
      }

      withCredentials([string(credentialsId: 'AtlasDB', variable: 'ATLAS_KEY')]) {
        sh """
                wget -O tmp.gz "https://deviceatlas.com/getJSON.php?licencekey=${ATLAS_KEY}&format=gzip"
                
                gunzip -c ./tmp.gz > db.json
                rm tmp.gz -f
                
                mv db.json ./var/devicedetection/database/db.json -f
                """
      }
    }
    stage('Generate production configs') {
      configFileProvider([
          configFile(fileId: 'store-prod-ebextension-2.php', targetLocation: '.ebextensions/2-php.config'),
          configFile(fileId: 'store-prod-ebextension-5.logs', targetLocation: '.ebextensions/5-log.config')
      ]) {
        print "Adding files"
      }

    }
    stage('Build') {

      runComposerInstall(WORKSPACE)
      docker.image('playwing/php:latest').inside("-v $WORKSPACE:/workspace") {
        sh '''
                    php /workspace/bin/console development:generate_app_version_hash || true
                    php /workspace/vendor/sensio/distribution-bundle/Resources/bin/build_bootstrap.php /workspace/var/ /workspace/app/ 
                    php /workspace/bin/console assets:install /workspace/web/ -v
                    php /workspace/bin/console assetic:dump /workspace/web/ -v --no-debug
                '''
      }
      dir('web/css') {
        sh '''
            for f in *.css; do short=${f%.css}; yui-compressor $f -o $f; done;
             '''
      }
      dir('web/js') {
        sh '''
            for f in *.js; do short=${f%.js}; java -jar /var/lib/jenkins/closure-compiler-v20181210.jar --js $f --js_output_file $f.min && mv $f.min $f; done;
            '''
      }


    }
    stage('Apply Migrations') {

      withCredentials([string(credentialsId: 'StoreProductionDBPassword', variable: 'WEBSTORE_PRODUCTION_DB_PASS')]) {

        def live = """
                    -e "DATABASE_HOST=$WEBSTORE_PRODUCTION_DB_HOST" \
                    -e "DATABASE_PORT=3306" \
                    -e "DATABASE_NAME=webstore" \
                    -e "DATABASE_USER=webstore" \
                    -e "DATABASE_PASSWORD=$WEBSTORE_PRODUCTION_DB_PASS" \
                    -v $WORKSPACE:/workspace
                """;

        sh "rm var/cache/* -rf"

        docker.image('playwing/php:latest').inside(live) {
          sh "php /workspace/bin/console doctrine:migrations:migrate"
        }
      }
    }
    stage('Deploy') {


      sh 'rm -rf app.zip && zip -rq app.zip . --exclude ".git/*"'
      step([
          $class                  : 'AWSEBDeploymentBuilder',
          zeroDowntime            : false,
          awsRegion               : 'EU-Central-1',
          applicationName         : 'webstore',
          environmentName         : 'Webstore-Production-Private',
          rootObject              : "app.zip",
          bucketName              : 'elasticbeanstalk-eu-central-1-024590668976',
          versionLabelFormat      : "v${VERSION_TAG}-with-vendor-${BUILD_ID}",
          versionDescriptionFormat: "${SCM_VARS.GIT_COMMIT}",
          sleepTime               : 15,
          checkHealth             : true,
          maxAttempts             : 120
      ])
    }
    stage('Clear cache') {
      build job: 'clean-redis-cache', propagate: false
      if (INVALIDATE_CDN == 'true') {
        build job: 'clean-cdn-cache', propagate: false
      }
    }
    stage('Git push') {
      sh """
            git tag -a v${VERSION_TAG} -m 'From Jenkins build #${BUILD_ID}'
            git push origin HEAD:master --tags
            """
    }
  }
}