#!groovy

@Library('cdis-jenkins-lib@feat/performancePipeline') _

def call(Map config) {
  pipeline {
    agent any
  
    environment {
      QUAY_API = 'https://quay.io/api/v1/repository/cdis/'
    }

    stages {
      stage('FetchCode') {
        steps {
          fetchCode([:])
        }
      }

      stage('Install gen3-client') {
        steps {
          installGen3Client()
        }
      }

      stage('SelectNamespace') {
        steps {
          selectNamespace(['jenkins-perf'] as String[])
          dir('gen3-qa') {
            sh "git branch -D feat/regression-pipeline"
            sh "git checkout feat/regression-pipeline"
          }
        }
      }

      stage('WaitForQuayBuild') {
          steps {
            script {
              waitForQuay()
              }
            }
          }

      stage('ModifyManifest') {
        steps {
          script {
            dirname = sh(script: "kubectl -n $env.KUBECTL_NAMESPACE get configmap global -o jsonpath='{.data.hostname}'", returnStdout: true)
          }
          dir("cdis-manifest/$dirname") {
            withEnv(["masterBranch=$env.service:[a-zA-Z0-9._-]*", "targetBranch=$env.service:$env.quaySuffix"]) {
              sh 'sed -i -e "s,' + "$env.masterBranch,$env.targetBranch" + ',g" manifest.json'
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
            // wait for portal to startup ...
            sh "bash cloud-automation/gen3/bin/kube-wait4-pods.sh || true"
          }
        }
      }
      stage('VerifyClusterHealth') {
        steps {
          withEnv(['GEN3_NOPROXY=true', "vpc_name=$env.KUBECTL_NAMESPACE", "GEN3_HOME=$env.WORKSPACE/cloud-automation"]) {
            sh "bash cloud-automation/gen3/bin/kube-wait4-pods.sh"
            sh "bash ./gen3-qa/check-pod-health.sh"
          }
        }
      }
      stage('Download S3 data') {
        steps {
          copyS3('s3://cdis-terraform-state/regressions/psql_dumps/', 'regressions/psql_dumps/')
        }
      }

      stage('Submission') {
        stages {
          stage('RollDBDump10_1') {
            steps {
              restoreDbDump('regressions/psql_dumps/10_psql.sql')
            }
          }
          stage('RunRegressionsTestsOn10') {
            steps {
              dir('gen3-qa') {
                sh "git checkout feat/regression-pipeline"
                sh "aws s3 cp s3://cdis-terraform-state/regressions/subm_100/DataImportOrder.txt DataImportOrder.txt"

                withEnv(['GEN3_NOPROXY=true', "vpc_name=$env.KUBECTL_NAMESPACE", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "NAMESPACE=$env.KUBECTL_NAMESPACE", "TEST_DATA_PATH=$env.WORKSPACE/testData/", "DATAURL=https://cdis-terraform-state.s3.amazonaws.com/regressions/subm_100/core_metadata_collection.json?AWSAccessKeyId=ASIA2JSRVZXPZS6REPE4&Expires=1546457738&x-amz-security-token=FQoGZXIvYXdzEJT%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaDKqxBov7h4Rw7XZrjCL%2FAdrGYv7Mu5j45vfLv8nc5HBNF3xJMn5bOvJrnJ1REPDZcdJUld%2B%2BVfhikiPmluiukwidwTHRbW0EHEdT1HlsPU7JUtNFa7aJkU1e18BH1yuWil4MEScImSgWKND1N4m3it7pejwIjgCdu81fS8xH%2BchaqOr%2BdRIAU%2Bv2ywQTUo6%2FqOpUxpf4Z6dWFJNnsnwB5o24YU%2Fvy%2F0QG9RtgdSUhzkFfyAqzD7BuH2F5rP%2BJ11GJn2aovk51Wjy2fjT5y8tLuhZryGrg2YFrurkkEawNh8Eaq%2B9UzfxfUjxDpY%2BXrmgG0MH%2FFVeo4siyQG8AK98gyhy1PapTyqJ7neXShVCWiiMiLThBQ%3D%3D&Signature=0qH8aivItn2mivra2ETvpVU3bpg%3D"]) {
                  sh "bash ./run-regressions.sh $env.KUBECTL_NAMESPACE --service=$env.service"
                }
              }
            }
          }
          stage('RollDBDump100_1') {
            steps {
              restoreDbDump('regressions/psql_dumps/100_psql.sql')
            }
          }
          stage('RunRegressionsTestsOn100') {
            steps {
              dir('gen3-qa') {
                sh "aws s3 cp s3://cdis-terraform-state/regressions/subm_100/DataImportOrder.txt DataImportOrder.txt"

                withEnv(['GEN3_NOPROXY=true', "vpc_name=$env.KUBECTL_NAMESPACE", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "NAMESPACE=$env.KUBECTL_NAMESPACE", "TEST_DATA_PATH=$env.WORKSPACE/testData/", "DATAURL=https://cdis-terraform-state.s3.amazonaws.com/regressions/subm_100/core_metadata_collection.json?AWSAccessKeyId=ASIA2JSRVZXPZS6REPE4&Expires=1546457738&x-amz-security-token=FQoGZXIvYXdzEJT%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaDKqxBov7h4Rw7XZrjCL%2FAdrGYv7Mu5j45vfLv8nc5HBNF3xJMn5bOvJrnJ1REPDZcdJUld%2B%2BVfhikiPmluiukwidwTHRbW0EHEdT1HlsPU7JUtNFa7aJkU1e18BH1yuWil4MEScImSgWKND1N4m3it7pejwIjgCdu81fS8xH%2BchaqOr%2BdRIAU%2Bv2ywQTUo6%2FqOpUxpf4Z6dWFJNnsnwB5o24YU%2Fvy%2F0QG9RtgdSUhzkFfyAqzD7BuH2F5rP%2BJ11GJn2aovk51Wjy2fjT5y8tLuhZryGrg2YFrurkkEawNh8Eaq%2B9UzfxfUjxDpY%2BXrmgG0MH%2FFVeo4siyQG8AK98gyhy1PapTyqJ7neXShVCWiiMiLThBQ%3D%3D&Signature=0qH8aivItn2mivra2ETvpVU3bpg%3D"]) {
                  sh "bash ./run-regressions.sh $env.KUBECTL_NAMESPACE --service=$env.service"
                }
              }
            }
          }
          stage('RollDBDump1000_1') {
            steps {
              restoreDbDump('regressions/psql_dumps/1000_psql.sql')
            }
          }
          stage('RunRegressionsTestsOn1000') {
            steps {
              dir('gen3-qa') {
                sh "aws s3 cp s3://cdis-terraform-state/regressions/subm_100/DataImportOrder.txt DataImportOrder.txt"

                withEnv(['GEN3_NOPROXY=true', "vpc_name=$env.KUBECTL_NAMESPACE", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "NAMESPACE=$env.KUBECTL_NAMESPACE", "TEST_DATA_PATH=$env.WORKSPACE/testData/", "DATAURL=https://cdis-terraform-state.s3.amazonaws.com/regressions/subm_100/core_metadata_collection.json?AWSAccessKeyId=ASIA2JSRVZXPZS6REPE4&Expires=1546457738&x-amz-security-token=FQoGZXIvYXdzEJT%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaDKqxBov7h4Rw7XZrjCL%2FAdrGYv7Mu5j45vfLv8nc5HBNF3xJMn5bOvJrnJ1REPDZcdJUld%2B%2BVfhikiPmluiukwidwTHRbW0EHEdT1HlsPU7JUtNFa7aJkU1e18BH1yuWil4MEScImSgWKND1N4m3it7pejwIjgCdu81fS8xH%2BchaqOr%2BdRIAU%2Bv2ywQTUo6%2FqOpUxpf4Z6dWFJNnsnwB5o24YU%2Fvy%2F0QG9RtgdSUhzkFfyAqzD7BuH2F5rP%2BJ11GJn2aovk51Wjy2fjT5y8tLuhZryGrg2YFrurkkEawNh8Eaq%2B9UzfxfUjxDpY%2BXrmgG0MH%2FFVeo4siyQG8AK98gyhy1PapTyqJ7neXShVCWiiMiLThBQ%3D%3D&Signature=0qH8aivItn2mivra2ETvpVU3bpg%3D"]) {
                  sh "bash ./run-regressions.sh $env.KUBECTL_NAMESPACE --service=$env.service"
                }
              }
            }
          }
        }
      }

      stage('Query') {
        stages {
          stage('RollDBDump10_2') {
            steps {
              restoreDbDump('regressions/psql_dumps/10_psql.sql')
            }
          }
          stage('RunQueryTestsOn10') {
            steps {
              dir('gen3-qa') {
                withEnv(['GEN3_NOPROXY=true', "vpc_name=$env.KUBECTL_NAMESPACE", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "NAMESPACE=$env.KUBECTL_NAMESPACE", "TEST_DATA_PATH=$env.WORKSPACE/testData/"]) {
                  script {
                    sh "ls"
                  }
                  sh "bash ./run-queries.sh $env.KUBECTL_NAMESPACE --service=$env.service"
                }
              }
            }
          }
          stage('RollDBDump100_2') {
            steps {
              restoreDbDump('regressions/psql_dumps/100_psql.sql')
            }
          }
          stage('RunQueryTestsOn100') {
            steps {
              dir('gen3-qa') {
                withEnv(['GEN3_NOPROXY=true', "vpc_name=$env.KUBECTL_NAMESPACE", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "NAMESPACE=$env.KUBECTL_NAMESPACE", "TEST_DATA_PATH=$env.WORKSPACE/testData/"]) {
                  sh "bash ./run-queries.sh $env.KUBECTL_NAMESPACE --service=$env.service"
                }
              }
            }
          }
          stage('RollDBDump1000_2') {
            steps {
              restoreDbDump('regressions/psql_dumps/1000_psql.sql')
            }
          
          stage('RunQueryTestsOn1000') {
            steps {
              dir('gen3-qa') {
                withEnv(['GEN3_NOPROXY=true', "vpc_name=$env.KUBECTL_NAMESPACE", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "NAMESPACE=$env.KUBECTL_NAMESPACE", "TEST_DATA_PATH=$env.WORKSPACE/testData/"]) {
                  sh "bash ./run-queries.sh $env.KUBECTL_NAMESPACE --service=$env.service"
                }
              }
            }
          }
        }
      }
      
      stage('Export') {
        stages {
            stage('RollDBDump10_3') {
            steps {
              restoreDbDump('regressions/psql_dumps/10_psql.sql')
            }
          }
          stage('RunExportTestsOn10') {
            steps {
              dir('gen3-qa') {
                withEnv(['GEN3_NOPROXY=true', "vpc_name=$env.KUBECTL_NAMESPACE", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "NAMESPACE=$env.KUBECTL_NAMESPACE", "TEST_DATA_PATH=$env.WORKSPACE/testData/", "PROGRAM_SLASH_PROJECT=dev/test"]) {
                  script {
                    sh "ls"
                  }
                  sh "bash ./run-queries.sh $env.KUBECTL_NAMESPACE --service=$env.service"
                }
              }
            }
          }
          stage('RollDBDump100_3') {
            steps {
              restoreDbDump('regressions/psql_dumps/100_psql.sql')
            }
          }
          stage('RunExportTestsOn100') {
            steps {
              dir('gen3-qa') {
                withEnv(['GEN3_NOPROXY=true', "vpc_name=$env.KUBECTL_NAMESPACE", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "NAMESPACE=$env.KUBECTL_NAMESPACE", "TEST_DATA_PATH=$env.WORKSPACE/testData/", "PROGRAM_SLASH_PROJECT=dev/test"]) {
                  sh "bash ./run-queries.sh $env.KUBECTL_NAMESPACE --service=$env.service"
                }
              }
            }
          }
          stage('RollDBDump1000_3') {
            steps {
              restoreDbDump('regressions/psql_dumps/1000_psql.sql')
            }
          }
          stage('RunExportTestsOn1000') {
            steps {
              dir('gen3-qa') {
                withEnv(['GEN3_NOPROXY=true', "vpc_name=$env.KUBECTL_NAMESPACE", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "NAMESPACE=$env.KUBECTL_NAMESPACE", "TEST_DATA_PATH=$env.WORKSPACE/testData/", "PROGRAM_SLASH_PROJECT=dev/test"]) {
                  sh "bash ./run-queries.sh $env.KUBECTL_NAMESPACE --service=$env.service"
                }
              }
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
        // archiveArtifacts artifacts: '**/output/*.png', fingerprint: true
        //slackSend color: 'bad', message: "https://jenkins.planx-pla.net $env.JOB_NAME pipeline failed"
      }
      unstable {
        echo "Unstable!"
        //slackSend color: 'bad', message: "https://jenkins.planx-pla.net $env.JOB_NAME pipeline unstable"
      }
      always {
        script {
          uid = env.service+"-"+env.quaySuffix+"-"+env.BUILD_NUMBER
          withEnv(['GEN3_NOPROXY=true', "GEN3_HOME=$env.WORKSPACE/cloud-automation"]) {         
            sh("bash cloud-automation/gen3/bin/klock.sh unlock jenkins " + uid + " || true")
            sh("bash cloud-automation/gen3/bin/klock.sh unlock reset-lock gen3-reset || true")
          }
        }
        script {
          dir('regressions') {
            sh "ls"
            sh "rm psql_dumps/ -rf"
            sh "ls"
          }
        }
        script {
          uninstallGen3Client()
        }
        echo "done"
        // junit "gen3-qa/output/*.xml"
      }
    }
  }
}