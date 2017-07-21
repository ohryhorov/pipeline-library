package com.mirantis.mk
import com.cloudbees.groovy.cps.NonCPS

/**
 *
 * Jenkins common functions
 *
 */

/**
 * Get Jenkins job running builds
 * @param jobName job name
 * @return list of running builds
 */
@NonCPS
def getJobRunningBuilds(jobName){
  def job = Jenkins.instance.items.find{it -> it.name.equals(jobName)}
  if(job){
   return job.builds.findAll{build -> build.isBuilding()}
  }
  return []
}

@NonCPS
def getRunningBuilds(job){
  return job.builds.findAll{build -> build.isBuilding()}
}

@NonCPS
def killStuckBuilds(maxSeconds, job){
  def common = new com.mirantis.mk.Common()
  def result = true
  def runningBuilds = getRunningBuilds(job)
  def jobName = job.name
  for(int j=0; j < runningBuilds.size(); j++){
    int durationInSeconds = (System.currentTimeMillis() - runningBuilds[j].getTimeInMillis())/1000.0
    if(durationInSeconds > maxSeconds){
      result = false
      def buildId = runningBuilds[j].id
      common.infoMsg("Aborting ${jobName}-${buildId} which is running for ${durationInSeconds}s")
      try{
        runningBuilds[j].finish(hudson.model.Result.ABORTED, new java.io.IOException("Aborting build by long running jobs killer"));
        result = true
      }catch(e){
        common.errorMsg("Error occured during aborting build: Exception: ${e}")
      }
    }
  }
  return result
}