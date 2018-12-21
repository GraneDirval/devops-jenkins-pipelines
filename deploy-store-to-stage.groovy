node {

  def SCM_VARS;

  timestamps {

    stage('Checkout') {
      SCM_VARS = checkout([
          $class                           : 'GitSCM',
          branches                         : [[name: 'stage']],
          doGenerateSubmoduleConfigurations: false,
          extensions                       : [
              [$class: 'CleanBeforeCheckout'],
          ],
          submoduleCfg                     : [],
          userRemoteConfigs                : [
              [url: SSH_WEBSTORE_REPO]
          ]
      ])

      def message = sh(returnStdout: true, script: 'git log -1 --pretty=%B').trim()
      def author = sh(returnStdout: true, script: 'git log -1 --pretty=format:\'%an\'').trim()

      currentBuild.description = "<b>$message</b> <br> by <i>$author</i>"
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
    stage('Install dependencies') {
      runComposerInstall(WORKSPACE)
    }
    stage('Compile assets & apply migrations') {
      def stage = """
            -e "DATABASE_HOST=playgroundireland.c29nznelhfp4.eu-west-1.rds.amazonaws.com" \
            -e "DATABASE_PORT=3306" \
            -e "DATABASE_NAME=webstore_development" \
            -e "DATABASE_USER=webstore_development" \
            -e "DATABASE_PASSWORD=sbVsT6gB4AbvlpQR" \
            -v $WORKSPACE:/workspace
          """;

      docker.image('playwing/php:latest').inside(stage) {
        sh '''
            php /workspace/bin/console development:generate || true
            php /workspace/vendor/sensio/distribution-bundle/Resources/bin/build_bootstrap.php /workspace/var/ /workspace/app/
            php /workspace/bin/console assets:install /workspace/web/
            php /workspace/bin/console assetic:dump /workspace/web/ --no-debug
        '''
        sh "php /workspace/bin/console doctrine:migrations:migrate -vvv"
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
    stage('Deploy') {
      sh 'rm -rf app.zip && zip -rq app.zip . --exclude ".git/*"'
      step([
          $class                  : 'AWSEBDeploymentBuilder',
          zeroDowntime            : false,
          awsRegion               : 'EU-West-1',
          applicationName         : 'store-dev',
          environmentName         : 'webstore-stage',
          rootObject              : "app.zip",
          versionLabelFormat      : "build-${BUILD_ID}",
          versionDescriptionFormat: "${BUILD_ID}",
          sleepTime               : 15,
          checkHealth             : true,
          maxAttempts             : 120
      ])
    }
    stage('Clear cache') {
      build job: 'clear-stage-cache', propagate: false
    }
  }
}