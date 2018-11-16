package uchicago.cdis;

import uchicago.cdis.KubeHelper;

/**
 * Groovy helper for deploying services to k8s in Jenkins pipelines.
 *
 * @see https://jenkins.io/doc/book/pipeline/shared-libraries/
 */
class MicroservicePipeline implements Serializable {
  def steps
  def kubeHelper
  
  /**
   * Constructor
   *
   * @param steps injects hook to Jenkins Pipeline runtime
   */
  MicroservicePipeline(steps) {
    this.steps = steps;
    this.kubeHelper = new KubeHelper(steps)
  }

  /**
   * Deploy the current env.GIT_BRANCH to the k8s
   */
  def execute() {
    node('ThisNode') {
      stage('Testing123') {
        step {
          this.kubeHelper.deployBranch('sheepdog')
        }
      }
    }
  }
}
