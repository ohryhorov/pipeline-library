package com.mirantis.mk

import com.cloudbees.groovy.cps.NonCPS
import java.util.stream.Collectors
/**
 * Salt functions
 *
*/

/**
 * Salt connection and context parameters
 *
 * @param url                 Salt API server URL
 * @param credentialsID       ID of credentials store entry
 */
def connection(url, credentialsId = "salt") {
    def common = new com.mirantis.mk.Common()
    params = [
        "url": url,
        "credentialsId": credentialsId,
        "authToken": null,
        "creds": common.getCredentials(credentialsId)
    ]
    params["authToken"] = saltLogin(params)

    return params
}

/**
 * Login to Salt API, return auth token
 *
 * @param master   Salt connection object
 */
def saltLogin(master) {
    def http = new com.mirantis.mk.Http()
    data = [
        'username': master.creds.username,
        'password': master.creds.password.toString(),
        'eauth': 'pam'
    ]
    authToken = http.restGet(master, '/login', data)['return'][0]['token']
    return authToken
}

/**
 * Run action using Salt API
 *
 * @param master   Salt connection object
 * @param client   Client type
 * @param target   Target specification, eg. for compound matches by Pillar
 *                 data: ['expression': 'I@openssh:server', 'type': 'compound'])
 * @param function Function to execute (eg. "state.sls")
 * @param batch    Batch param to salt (integer or string with percents)
 * @param args     Additional arguments to function
 * @param kwargs   Additional key-value arguments to function
 * @param timeout  Additional argument salt api timeout
 * @param read_timeout http session read timeout
 */
@NonCPS
def runSaltCommand(master, client, target, function, batch = null, args = null, kwargs = null, timeout = -1, read_timeout = -1) {
    def http = new com.mirantis.mk.Http()

    data = [
        'tgt': target.expression,
        'fun': function,
        'client': client,
        'expr_form': target.type,
    ]

    if(batch != null && ( (batch instanceof Integer && batch > 0) || (batch instanceof String && batch.contains("%")))){
        data['client']= "local_batch"
        data['batch'] = batch
    }

    if (args) {
        data['arg'] = args
    }

    if (kwargs) {
        data['kwarg'] = kwargs
    }

    if (timeout != -1) {
        data['timeout'] = timeout
    }

    headers = [
      'X-Auth-Token': "${master.authToken}"
    ]

    return http.sendHttpPostRequest("${master.url}/", data, headers, read_timeout)
}

/**
 * Return pillar for given master and target
 * @param master Salt connection object
 * @param target Get pillar target
 * @param pillar pillar name (optional)
 * @return output of salt command
 */
def getPillar(master, target, pillar = null) {
    if (pillar != null) {
        return runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'pillar.get', null, [pillar.replace('.', ':')])
    } else {
        return runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'pillar.data')
    }
}

/**
 * Return grain for given master and target
 * @param master Salt connection object
 * @param target Get grain target
 * @param grain grain name (optional)
 * @return output of salt command
 */
def getGrain(master, target, grain = null) {
    if(grain != null) {
        return runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'grains.item', null, [grain])
    } else {
        return runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'grains.items')
    }
}

/**
 * Enforces state on given master and target
 * @param master Salt connection object
 * @param target State enforcing target
 * @param state Salt state
 * @param output print output (optional, default true)
 * @param failOnError throw exception on salt state result:false (optional, default true)
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @param read_timeout http session read timeout
 * @param retries Retry count for salt state.
 * @return output of salt command
 */
def enforceState(master, target, state, output = true, failOnError = true, batch = null, optional = false, read_timeout=-1, retries=-1) {
    def common = new com.mirantis.mk.Common()
    def run_states

    if (state instanceof String) {
        run_states = state
    } else {
        run_states = state.join(',')
    }

    common.infoMsg("Enforcing state ${run_states} on ${target}")
    def out

    if (optional == false || testTarget(master, target)){
        if (retries != -1){
            retry(retries){
                out = runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'state.sls', batch, [run_states], null, -1, read_timeout)
            }
            }
        else {
            out = runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'state.sls', batch, [run_states], null, -1, read_timeout)
        }
        checkResult(out, failOnError, output)
        return out
    } else {
        common.infoMsg("No Minions matched the target given, but 'optional' param was set to true - Pipeline continues. ")
    }
}

/**
 * Run command on salt minion (salt cmd.run wrapper)
 * @param master Salt connection object
 * @param target Get pillar target
 * @param cmd command
 * @param checkResponse test command success execution (default true)
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @param output do you want to print output
 * @return output of salt command
 */
def cmdRun(master, target, cmd, checkResponse = true, batch=null, output = true) {
    def common = new com.mirantis.mk.Common()
    def originalCmd = cmd
    common.infoMsg("Running command ${cmd} on ${target}")
    if (checkResponse) {
      cmd = cmd + " && echo Salt command execution success"
    }
    def out = runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'cmd.run', batch, [cmd])
    if (checkResponse) {
        // iterate over all affected nodes and check success return code
        if (out["return"]){
            for(int i=0;i<out["return"].size();i++){
                def node = out["return"][i];
                for(int j=0;j<node.size();j++){
                    def nodeKey = node.keySet()[j]
                    if (!node[nodeKey].contains("Salt command execution success")) {
                        throw new Exception("Execution of cmd ${originalCmd} failed. Server returns: ${node[nodeKey]}")
                    }
                }
            }
        }else{
            throw new Exception("Salt Api response doesn't have return param!")
        }
    }
    if (output == true) {
        printSaltCommandResult(out)
    }
    return out
}


/**
 * Run command on salt minion (salt cmd.run wrapper)
 * @param master Salt connection object
 * @param target Get pillar target
 * @param minion_name unique identification of a minion in salt-key command output
 * @param waitUntilPresent return after the minion becomes present (default true)
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @param output print salt command (default true)
 * @return output of salt command
 */
def minionPresent(master, target, minion_name, waitUntilPresent = true, batch=null, output = true) {
    return command_status(master, target, 'salt-key | grep ' + minion_name, minion_name, waitUntilPresent, batch, output)
}

/**
 * Run command on salt minion (salt cmd.run wrapper)
 * @param master Salt connection object
 * @param target Get pillar target
 * @param cmd name of a service
 * @param correct_state string that command must contain if status is in correct state (optional, default 'running')
 * @param waitUntilOk return after the minion becomes present (optional, default true)
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @param output print salt command (default true)
 * @return output of salt command
 */
def commandStatus(master, target, cmd, correct_state='running', waitUntilOk = true, batch=null, output = true, maxRetries = 200) {
    def common = new com.mirantis.mk.Common()
    common.infoMsg("Checking if status of verification command ${cmd} on ${target} is in correct state")
    if (waitUntilOk){
        def count = 0
        while(count < maxRetries) {
            def out = runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'cmd.shell', batch, [cmd])
            def resultMap = out["return"][0]
            def result = resultMap.get(resultMap.keySet()[0])
            if (result.toLowerCase().contains(correct_state.toLowerCase())) {
                if (output) {
                    printSaltCommandResult(out)
                }
                return out
            }
            count++
            sleep(time: 500, unit: 'MILLISECONDS')
        }
    } else {
        def out = runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'cmd.shell', batch, [cmd])
        def resultMap = out["return"][0]
        def result = resultMap.get(resultMap.keySet()[0])
        if (result.toLowerCase().contains(correct_state.toLowerCase())) {
            if (output) {
                printSaltCommandResult(out)
            }
            return out
        }
    }
    // otherwise throw exception
    throw new Exception("${cmd} signals failure of status check!")
}


/**
 * Perform complete salt sync between master and target
 * @param master Salt connection object
 * @param target Get pillar target
 * @return output of salt command
 */
def syncAll(master, target) {
    return runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'saltutil.sync_all')
}

/**
 * Enforce highstate on given targets
 * @param master Salt connection object
 * @param target Highstate enforcing target
 * @param output print output (optional, default true)
 * @param failOnError throw exception on salt state result:false (optional, default true)
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @return output of salt command
 */
def enforceHighstate(master, target, output = false, failOnError = true, batch = null) {
    def out = runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'state.highstate', batch)
    def common = new com.mirantis.mk.Common()

    common.infoMsg("Running step state.highstate on ${target}")

    checkResult(out, failOnError, output)
    return out
}

/**
 * Get running minions IDs according to the target
 * @param master Salt connection object
 * @param target Get minions target
 * @return list of active minions fitin
 */
def getMinions(master, target) {
    def minionsRaw = runSaltCommand(master, 'local', ['expression': target, 'type': 'compound'], 'test.ping')
    return new ArrayList<String>(minionsRaw['return'][0].keySet())
}


/**
 * Test if there are any minions to target
 * @param master Salt connection object
 * @param target Target to test
 * @return bool indicating if target was succesful
 */

def testTarget(master, target) {
    return getMinions(master, target).size() > 0
}

/**
 * Generates node key using key.gen_accept call
 * @param master Salt connection object
 * @param target Key generating target
 * @param host Key generating host
 * @param keysize generated key size (optional, default 4096)
 * @return output of salt command
 */
def generateNodeKey(master, target, host, keysize = 4096) {
    return runSaltCommand(master, 'wheel', target, 'key.gen_accept', [host], ['keysize': keysize])
}

/**
 * Generates node reclass metadata
 * @param master Salt connection object
 * @param target Metadata generating target
 * @param host Metadata generating host
 * @param classes Reclass classes
 * @param parameters Reclass parameters
 * @return output of salt command
 */
def generateNodeMetadata(master, target, host, classes, parameters) {
    return runSaltCommand(master, 'local', target, 'reclass.node_create', [host, '_generated'], ['classes': classes, 'parameters': parameters])
}

/**
 * Run salt orchestrate on given targets
 * @param master Salt connection object
 * @param target Orchestration target
 * @param orchestrate Salt orchestrate params
 * @return output of salt command
 */
def orchestrateSystem(master, target, orchestrate) {
    return runSaltCommand(master, 'runner', target, 'state.orchestrate', [orchestrate])
}

/**
 * Run salt process step
 * @param master Salt connection object
 * @param tgt Salt process step target
 * @param fun Salt process step function
 * @param arg process step arguments (optional, default [])
 * @param batch salt batch parameter integer or string with percents (optional, default null - disable batch)
 * @param output print output (optional, default false)
 * @param timeout  Additional argument salt api timeout
 * @return output of salt command
 */
def runSaltProcessStep(master, tgt, fun, arg = [], batch = null, output = false, timeout = -1) {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    def out

    common.infoMsg("Running step ${fun} on ${tgt}")

    if (batch == true) {
        out = runSaltCommand(master, 'local_batch', ['expression': tgt, 'type': 'compound'], fun, String.valueOf(batch), arg, null, timeout)
    } else {
        out = runSaltCommand(master, 'local', ['expression': tgt, 'type': 'compound'], fun, batch, arg, null, timeout)
    }

    if (output == true) {
        salt.printSaltCommandResult(out)
    }
    return out
}

/**
 * Check result for errors and throw exception if any found
 *
 * @param result    Parsed response of Salt API
 * @param failOnError Do you want to throw exception if salt-call fails (optional, default true)
 * @param printResults Do you want to print salt results (optional, default true)
 * @param printOnlyChanges If true (default), print only changed resources
 */
def checkResult(result, failOnError = true, printResults = true, printOnlyChanges = true) {
    def common = new com.mirantis.mk.Common()
    if(result != null){
        if(result['return']){
            for (int i=0;i<result['return'].size();i++) {
                def entry = result['return'][i]
                if (!entry) {
                    if (failOnError) {
                        throw new Exception("Salt API returned empty response: ${result}")
                    } else {
                        common.errorMsg("Salt API returned empty response: ${result}")
                    }
                }
                for (int j=0;j<entry.size();j++) {
                    def nodeKey = entry.keySet()[j]
                    def node=entry[nodeKey]
                    def outputResources = []
                    common.infoMsg("Node ${nodeKey} changes:")
                    if(node instanceof Map || node instanceof List){
                        for (int k=0;k<node.size();k++) {
                            def resource;
                            def resKey;
                            if(node instanceof Map){
                                resKey = node.keySet()[k]
                            }else if(node instanceof List){
                                resKey = k
                            }
                            resource = node[resKey]
                           // print
                            if(printResults){
                                if(resource instanceof Map && resource.keySet().contains("result")){
                                    //clean unnesaccary fields
                                    if(resource.keySet().contains("__run_num__")){
                                        resource.remove("__run_num__")
                                    }
                                    if(resource.keySet().contains("__id__")){
                                        resource.remove("__id__")
                                    }
                                    if(resource.keySet().contains("pchanges")){
                                        resource.remove("pchanges")
                                    }
                                    if(!resource["result"] || (resource["result"] instanceof String && resource["result"] != "true")){
                                        if(resource["result"] != null){
                                            outputResources.add(String.format("Resource: %s\n\u001B[31m%s\u001B[0m", resKey, common.prettify(resource)))
                                        }else{
                                            outputResources.add(String.format("Resource: %s\n\u001B[33m%s\u001B[0m", resKey, common.prettify(resource)))
                                        }
                                    }else{
                                        if(!printOnlyChanges || resource.changes.size() > 0){
                                            outputResources.add(String.format("Resource: %s\n\u001B[32m%s\u001B[0m", resKey, common.prettify(resource)))
                                        }
                                    }
                                }else{
                                    outputResources.add(String.format("Resource: %s\n\u001B[36m%s\u001B[0m", resKey, common.prettify(resource)))
                                }
                            }
                            common.debugMsg("checkResult: checking resource: ${resource}")
                            if(resource instanceof String || (resource["result"] != null && !resource["result"]) || (resource["result"] instanceof String && resource["result"] == "false")){
                                def prettyResource = common.prettify(resource)
                                if(env["ASK_ON_ERROR"] && env["ASK_ON_ERROR"] == "true"){
                                    timeout(time:1, unit:'HOURS') {
                                       input message: "False result on ${nodeKey} found, resource ${prettyResource}. \nDo you want to continue?"
                                    }
                                }else{
                                    common.errorMsg(String.format("Resource: %s\n%s", resKey, prettyResource))
                                    def errorMsg = "Salt state on node ${nodeKey} failed: ${prettyResource}."
                                    if (failOnError) {
                                        throw new Exception(errorMsg)
                                    } else {
                                        common.errorMsg(errorMsg)
                                    }
                                }
                            }
                        }
                    }else if(node!=null && node!=""){
                        outputResources.add(String.format("Resource: %s\n\u001B[36m%s\u001B[0m", resKey, common.prettify(node)))
                    }
                    if(printResults && !outputResources.isEmpty()){
                        wrap([$class: 'AnsiColorBuildWrapper']) {
                            print outputResources.stream().collect(Collectors.joining("\n"))
                        }
                    }
                }
            }
        }else{
            common.errorMsg("Salt result hasn't return attribute! Result: ${result}")
        }
    }else{
        common.errorMsg("Cannot check salt result, given result is null")
    }
}

/**
 * Print salt command run results in human-friendly form
 *
 * @param result        Parsed response of Salt API
 */
def printSaltCommandResult(result) {
    def common = new com.mirantis.mk.Common()
    if(result != null){
        if(result['return']){
            for (int i=0; i<result['return'].size(); i++) {
                def entry = result['return'][i]
                for (int j=0; j<entry.size(); j++) {
                    common.debugMsg("printSaltCommandResult: printing salt command entry: ${entry}")
                    def nodeKey = entry.keySet()[j]
                    def node=entry[nodeKey]
                    common.infoMsg(String.format("Node %s changes:\n%s",nodeKey, common.prettify(node)))
                }
            }
        }else{
            common.errorMsg("Salt result hasn't return attribute! Result: ${result}")
        }
    }else{
        common.errorMsg("Cannot print salt command result, given result is null")
    }
}


/**
 * Return content of file target
 *
 * @param master    Salt master object
 * @param target    Compound target (should target only one host)
 * @param file      File path to read (/etc/hosts for example)
 */

def getFileContent(master, target, file) {
    result = cmdRun(master, target, "cat ${file}")
    return result['return'][0].values()[0]
}

/**
 * Set override parameters in Salt cluster metadata
 *
 * @param master         Salt master object
 * @param salt_overrides YAML formatted string containing key: value, one per line
 * @param reclass_dir    Directory where Reclass git repo is located
 */

def setSaltOverrides(master, salt_overrides, reclass_dir="/srv/salt/reclass") {
    def mcpcommon = new com.mirantis.mcp.Common()
    def common = new com.mirantis.mk.Common()

    def salt_overrides_map = mcpcommon.loadYAML(salt_overrides)
    for (entry in common.entries(salt_overrides_map)) {
         def key = entry[0]
         def value = entry[1]

         common.debugMsg("Set salt override ${key}=${value}")
         runSaltProcessStep(master, 'I@salt:master', 'reclass.cluster_meta_set', ["${key}", "${value}"], false, debug)
    }
    runSaltProcessStep(master, 'I@salt:master', 'cmd.run', ["git -C ${reclass_dir} update-index --skip-worktree classes/cluster/overrides.yml"])
}
