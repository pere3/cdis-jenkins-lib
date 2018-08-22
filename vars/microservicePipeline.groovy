#!groovy

def call(Map config) {
  pipeline {
    agent any
  
    environment {
      QUAY_API = 'https://quay.io/api/v1/repository/cdis/'
    }
  
    stages {
      stage('FetchCode') {
        steps {
          script {
            fetchCode()
            env.service = "$env.JOB_NAME".split('/')[1]
            env.quaySuffix = "$env.GIT_BRANCH".replaceAll("/", "_")
          }
        }
      }
      stage('PrepForTesting') {
        when {
          expression { "$env.JOB_NAME".split('/')[1] == 'cdis-jenkins-lib' }
        }
        steps {
          script {
            env.service = config.JOB_NAME
            env.quaySuffix = config.GIT_BRANCH
            println "set test mock environment variables"
          }
        }
      }
      stage('WaitForQuayBuild') {
        steps {
          script {
            service = "$env.JOB_NAME".split('/')[1]
            if (service == 'cdis-jenkins-lib') {
              service = 'jenkins-lib'
            }
            def timestamp = (("${currentBuild.timeInMillis}".substring(0, 10) as Integer) - 60)
            def timeout = (("${currentBuild.timeInMillis}".substring(0, 10) as Integer) + 3600)
            timeUrl = "$env.QUAY_API"+service+"/build/?since="+timestamp
            timeQuery = "curl -s "+timeUrl+/ | jq '.builds[] | "\(.tags[]),\(.display_name),\(.phase)"'/
            limitUrl = "$env.QUAY_API"+service+"/build/?limit=25"
            limitQuery = "curl -s "+limitUrl+/ | jq '.builds[] | "\(.tags[]),\(.display_name),\(.phase)"'/
            
            def testBool = false
            while(testBool != true) {
              currentTime = new Date().getTime()/1000 as Integer
              println "currentTime is: "+currentTime
  
              if(currentTime > timeout) {
                currentBuild.result = 'ABORTED'
                error("aborting build due to timeout")
              }
  
              sleep(30)
              println "running time query"
              resList = sh(script: timeQuery, returnStdout: true).trim().split('"\n"')
              for (String res in resList) {
                fields = res.replaceAll('"', "").split(',')
  
                if(fields[0].startsWith("$env.GIT_BRANCH".replaceAll("/", "_"))) {
                  if("$env.GIT_COMMIT".startsWith(fields[1])) {
                    testBool = fields[2].endsWith("complete")
                    break
                  } else {
                    currentBuild.result = 'ABORTED'
                    error("aborting build due to out of date git hash\npipeline: $env.GIT_COMMIT\nquay: "+fields[1])
                  }
                }
              }

              println "time query failed, running limit query"
              resList = sh(script: limitQuery, returnStdout: true).trim().split('"\n"')
              for (String res in resList) {
                fields = res.replaceAll('"', "").split(',')
  
                if(fields[0].startsWith("$env.GIT_BRANCH".replaceAll("/", "_"))) {
                  if("$env.GIT_COMMIT".startsWith(fields[1])) {
                    testBool = fields[2].endsWith("complete")
                    break
                  } else {
                    currentBuild.result = 'ABORTED'
                    error("aborting build due to out of date git hash\npipeline: $env.GIT_COMMIT\nquay: "+fields[1])
                  }
                }
              }
            }
          }
        }
      }
      stage('SelectNamespace') {
        steps {
          script {
            String[] namespaces = ['qa-bloodpac', 'qa-brain', 'qa-kidsfirst', 'qa-niaid']
            int modNum = namespaces.length/2
            int randNum = (new Random().nextInt(modNum) + ((env.EXECUTOR_NUMBER as Integer) * 2)) % namespaces.length
  
            env.KUBECTL_NAMESPACE = namespaces[randNum]
            println "selected namespace $env.KUBECTL_NAMESPACE on executor $env.EXECUTOR_NUMBER"
  
            println "attempting to lock namespace with a wait time of 5 minutes"
            uid = env.service+"-"+"$env.GIT_BRANCH".replaceAll("/", "_")+"-"+env.BUILD_NUMBER
            sh("bash cloud-automation/gen3/bin/kube-lock.sh jenkins "+uid+" 3600 -w 300")
          }
        }
      }
      stage('ModifyManifest') {
        steps {
          script {
            dirname = sh(script: "kubectl -n $env.KUBECTL_NAMESPACE get configmap global -o jsonpath='{.data.hostname}'", returnStdout: true)
          }
          dir("cdis-manifest/$dirname") {
            withEnv(["masterBranch=$env.service:master", "targetBranch=$env.service:$env.quaySuffix"]) {
              sh 'sed -i -e "s,'+"$env.masterBranch,$env.targetBranch"+',g" manifest.json'
            }
          }
        }
      }
      stage('K8sDeploy') {
        steps {
          withEnv(['GEN3_NOPROXY=true', "vpc_name=$env.KUBECTL_NAMESPACE", "GEN3_HOME=$env.WORKSPACE/cloud-automation"]) {
            echo "GEN3_HOME is $env.GEN3_HOME"
            echo "GIT_BRANCH is $env.GIT_BRANCH"
            echo "GIT_COMMIT is $env.GIT_COMMIT"
            echo "KUBECTL_NAMESPACE is $env.KUBECTL_NAMESPACE"
            echo "WORKSPACE is $env.WORKSPACE"
            sh "bash cloud-automation/gen3/bin/kube-roll-all.sh"
            sh "bash cloud-automation/gen3/bin/kube-wait4-pods.sh || true"
          }
        }
      }
      stage('RunInstall') {
        steps {
          dir('gen3-qa') {
            withEnv(['GEN3_NOPROXY=true']) {
              sh "bash ./run-install.sh"
            }
          }
        }
      }
      stage('RunTests') {
        steps {
          dir('gen3-qa') {
            withEnv(['GEN3_NOPROXY=true', "vpc_name=$env.KUBECTL_NAMESPACE", "GEN3_HOME=$env.WORKSPACE/cloud-automation"]) {
              sh "bash ./run-tests.sh $env.KUBECTL_NAMESPACE"
            }
          }
        }
      }
    }
    post {
      success {
        echo "https://jenkins.planx-pla.net/ $env.JOB_NAME pipeline succeeded"
      }
      failure {
        echo "Failure!"
        archiveArtifacts artifacts: '**/output/*.png', fingerprint: true
        //slackSend color: 'bad', message: "https://jenkins.planx-pla.net $env.JOB_NAME pipeline failed"
      }
      unstable {
        echo "Unstable!"
        //slackSend color: 'bad', message: "https://jenkins.planx-pla.net $env.JOB_NAME pipeline unstable"
      }
      always {
        script {
          uid = env.service+"-"+"$env.GIT_BRANCH".replaceAll("/", "_")+"-"+env.BUILD_NUMBER
          sh("bash cloud-automation/gen3/bin/kube-unlock.sh jenkins "+uid)
        }
        echo "done"
        junit "gen3-qa/output/*.xml"
      }
    }
  }
}