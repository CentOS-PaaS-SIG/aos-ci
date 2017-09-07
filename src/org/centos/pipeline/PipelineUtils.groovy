#!/usr/bin/groovy
package org.centos.pipeline

library identifier: "cico-pipeline-library@master",
        retriever: modernSCM([$class: 'GitSCMSource',
                              remote: "https://github.com/CentOS/cico-pipeline-library"])
import org.centos.Utils

import groovy.json.JsonSlurper

utils = new Utils()

/**
 * Library to setup and configure the host the way ci-pipeline requires
 *
 * variables
 *  stage - current stage running
 *  sshKey - ssh file credential name stored in Jenkins credentials
 */
def setupStage(stage, sshKey) {
    echo "Currently in stage: ${stage} in setupStage"

    // TODO: Either remove sshKey arg, or determine how to invoke second credentialsID and variable name based on arg.
    // Currently having an sshKey isn't that useful as we're still hard-coding the public credentialsID entry
    withCredentials([file(credentialsId: sshKey, variable: 'FEDORA_ATOMIC_KEY'), file(credentialsId: 'fedora-atomic-pub-key', variable: 'FEDORA_ATOMIC_PUB_KEY')]) {
        sh '''
            #!/bin/bash
            set -xeuo pipefail

            mkdir -p ~/.ssh
            cp ${FEDORA_ATOMIC_KEY} ~/.ssh/id_rsa
            cp ${FEDORA_ATOMIC_PUB_KEY} ~/.ssh/id_rsa.pub
            chmod 600 ~/.ssh/id_rsa
            chmod 644 ~/.ssh/id_rsa.pub
            
            # Keep compatibility with earlier cciskel-duffy
            if test -f ${ORIGIN_WORKSPACE}/inventory.${ORIGIN_BUILD_TAG}; then
                ln -fs ${ORIGIN_WORKSPACE}/inventory.${ORIGIN_BUILD_TAG} ${WORKSPACE}/inventory
            fi
    
            if test -n "${playbook:-}"; then
                ansible-playbook --private-key=${FEDORA_ATOMIC_KEY} -u root -i ${WORKSPACE}/inventory "${playbook}"
            else
                ansible --private-key=${FEDORA_ATOMIC_KEY} -u root -i ${WORKSPACE}/inventory all -m ping
            fi
            exit
        '''
    }
}

/**
 * Library to execute a task and rsync the logs back to artifacts.ci.centos.org
 *
 * variables
 *  stage - current stage running
 *  duffyKey - duffy file credential name stored in Jenkins credentials
 */
def runTaskAndReturnLogs(stage, duffyKey) {
    echo "Currently in stage: ${stage} in runTaskAndReturnLogs"

    withCredentials([file(credentialsId: duffyKey, variable: 'DUFFY_KEY'), file(credentialsId: 'fedora-keytab', variable: 'FEDORA_KEYTAB')]) {
        sh '''
            #!/bin/bash
            set -xeuo pipefail
    
            cp ${DUFFY_KEY} ~/duffy.key
            chmod 600 ~/duffy.key
    
            cp ${FEDORA_KEYTAB} fedora.keytab
            chmod 0600 fedora.keytab

            source ${ORIGIN_WORKSPACE}/task.env
            (echo -n "export RSYNC_PASSWORD=" && cat ~/duffy.key | cut -c '-13') > rsync-password.sh
            
            rsync -Hrlptv --stats -e ssh ${ORIGIN_WORKSPACE}/task.env rsync-password.sh fedora.keytab builder@${DUFFY_HOST}:${JENKINS_JOB_NAME}
            for repo in ci-pipeline sig-atomic-buildscripts; do
                rsync -Hrlptv --stats --delete -e ssh ${repo}/ builder@${DUFFY_HOST}:${JENKINS_JOB_NAME}/${repo}
            done
            
            # Use the following in ${task} to authenticate.
            #kinit -k -t ${FEDORA_KEYTAB} ${FEDORA_PRINCIPAL}
            build_success=true
            if ! ssh -tt builder@${DUFFY_HOST} "pushd ${JENKINS_JOB_NAME} && . rsync-password.sh && . task.env && ${task}"; then
                build_success=false
            fi
            
            rsync -Hrlptv --stats -e ssh builder@${DUFFY_HOST}:${JENKINS_JOB_NAME}/logs/ ${ORIGIN_WORKSPACE}/logs || true
            # Exit with code from the build
            if test "${build_success}" = "false"; then
                echo 'Build failed, see logs above'; exit 1
            fi
            exit
        '''
    }
}

/**
 * Library to check last image
 *
 * variables
 *  stage - current stage running
 */
def checkLastImage(stage) {
    echo "Currently in stage: ${stage} in checkLastImage"

    sh '''
        prev=$( date --date="$( curl -I --silent ${HTTP_BASE}/${RSYNC_DIR}/${branch}/images/latest-atomic.qcow2 | grep Last-Modified | sed s'/Last-Modified: //' )" +%s )
        cur=$( date +%s )
        
        elapsed=$((cur - prev))
        if [ $elapsed -gt 86400 ]; then
            echo "Time for a new image since time elapsed is ${elapsed}"
            touch ${WORKSPACE}/NeedNewImage.txt
        else
            echo "No need for a new image not time yet since time elapsed is ${elapsed}"
        fi
        exit
    '''
}

/**
 * Library to set message fields to be published
 *
 * variables
 *  messageType - ${MAIN_TOPIC}.ci.pipeline.<defined-in-README>
 */
def setMessageFields(messageType){
    topic = "${env.MAIN_TOPIC}.ci.pipeline.${messageType}"
    messageProperties = "topic=${topic}\n" +
                        "build_url=${env.BUILD_URL}\n" +
                        "build_id=${env.BUILD_ID}\n" +
                        "branch=${env.branch}\n" +
                        "compose_rev=${env.commit}\n" +
                        "namespace=${env.fed_namespace}\n" +
                        "ref=fedora/${env.branch}/${env.basearch}/atomic-host\n" +
                        "repo=${env.fed_repo}\n" +
                        "original_spec_nvr=${env.original_spec_nvr}\n" +
                        "nvr=${env.nvr}\n" +
                        "rev=${env.fed_rev}\n" +
                        "test_guidance=''\n" +
                        "username=${env.RSYNC_USER}\n" +
                        "status=${currentBuild.currentRelease}\n"
    messageContent=''

    if (messageType == 'compose.running') {
        messageProperties = messageProperties +
                "compose_url=${env.HTTP_BASE}/artifacts/${env.RSYNC_DIR}/${env.branch}/ostree\n"
                "compose_rev=''\n"
    } else if ((messageType == 'compose.complete') || (messageType == 'test.integration.queued') ||
            (messageType == 'test.integration.running') || (messageType == 'test.integration.complete')) {
        messageProperties = messageProperties +
            "compose_url=${env.HTTP_BASE}/artifacts/${env.RSYNC_DIR}/${env.branch}/ostree\n"
            "compose_rev=${env.commit}\n"
    } else if (messageType == 'image.running') {
            messageProperties = messageProperties +
                "compose_url=${env.HTTP_BASE}/artifacts/${env.RSYNC_DIR}/${env.branch}/ostree\n"
                "compose_rev=${env.commit}\n" +
                "image_url=''\n" +
                "image_name=''\n" +
                "type=qcow2\n"
    } else if ((messageType == 'image.complete') || (messageType == 'test.smoke.running') ||
            (messageType == 'test.smoke.compelete')) {
        messageProperties = messageProperties +
                "compose_url=${env.HTTP_BASE}/artifacts/${env.RSYNC_DIR}/${env.branch}/ostree\n"
                "compose_rev=${env.commit}\n" +
                "image_url=${env.image2boot}\n" +
                "image_name=${env.image_name}\n" +
                "type=qcow2\n"
    } else {
        return [ topic, messageProperties, messageContent ]
    }
    return [ topic, messageProperties, messageContent ]
}

/**
 * Library to send message
 *
 * variables
 *  msgProps - The message properties
 *  msgContent - The content of the message
 */
def sendMessage(msgProps, msgContent) {
    sendCIMessage messageContent: msgContent,
            messageProperties: msgProps,
            messageType: 'Custom',
            overrides: [topic: "${topic}"],
            providerName: "${MSG_PROVIDER}"
}

/**
 * Library to parse CI_MESSAGE and inject its key/value pairs as env variables.
 *
 */
def injectFedmsgVars() {

    // Parse the CI_MESSAGE into a Map
    def ci_data = new JsonSlurper().parseText(env.CI_MESSAGE)

    // If we have a 'commit' key in the CI_MESSAGE, for each key under 'commit', we
    // * prepend the key name with fed_
    // * replace any '-' with '_'
    // * truncate the value for the key at the first '\n' character
    // * replace any double-quote characters with single-quote characters in the value for the key.

    if (ci_data['commit']) {
        ci_data.commit.each { key, value ->
            env."fed_${key.toString().replaceAll('-', '_')}" =
                    value.toString().split('\n')[0].replaceAll('"', '\'')
        }
        if (env.fed_branch == 'master'){
            env.branch = 'rawhide'
        } else {
            env.branch = env.fedbranch
        }
    }
}

/**
 * Library to set default environmental variables. Performed once at start of Jenkinsfile
 * variables
 *  envMap - A map of key/value pairs which will be set as environmental variables.
 */
def setDefaultEnvVars(envMap=null){

    // Check if we're working with a staging or production instance by
    // evaluating if env.ghprbActual is null, and if it's not, whether
    // it is something other than 'master'
    // If we're working with a staging instance:
    //      We default to an MAIN_TOPIC of 'org.centos.stage'
    // If we're working with a production instance:
    //      We default to an MAIN_TOPIC of 'org.centos.prod'
    // Regardless of whether we're working with staging or production,
    // if we're provided a value for MAIN_TOPIC in the build parameters:

    if (env.ghprbActualCommit != null && env.ghprbActualCommit != "master") {
        env.MAIN_TOPIC = env.MAIN_TOPIC ?: 'org.centos.stage'
    } else {
        env.MAIN_TOPIC = env.MAIN_TOPIC ?: 'org.centos.prod'
    }
    env.MSG_PROVIDER = env.MSG_PROVIDER ?: 'fedora-fedmsg'
    env.HTTP_BASE = env.HTTP_BASE ?: 'http://artifacts.ci.centos.org/artifacts/fedora-atomic'
    env.RSYNC_USER = env.RSYNC_USER ?: 'fedora-atomic'
    env.RSYNC_SERVER = env.RSYNC_SERVER ?: 'artifacts.ci.centos.org'

    // Check if we're working with a staging or production instance by
    // evaluating if env.ghprbActual is null, and if it's not, whether
    // it is something other than 'master'
    // If we're working with a staging instance:
    //      We default to an RSYNC_DIR of fedora-atomic/staging
    // If we're working with a production instance:
    //      We default to an RSYNC_DIR of fedora-atomic
    // Regardless of whether we're working with staging or production,
    // if we're provided a value for RSYNC_DIR in the build parameters:
    //      We set the RSYNC_DIR to the value provided (this overwrites staging or production paths)
    
    if (env.ghprbActualCommit != null && env.ghprbActualCommit != "master") {
        env.MAIN_TOPIC = env.RSYNC_DIR ?: 'fedora-atomic/staging'
    } else {
        env.MAIN_TOPIC = env.RSYNC_DIR ?: 'fedora-atomic'
    }
    env.basearch = env.basearch ?: 'x86_64'
    env.OSTREE_BRANCH = env.OSTREE_BRANCH ?: ''
    env.commit = env.commit ?: ''
    env.image2boot = env.image2boot ?: ''
    env.image_name = env.image_name ?: ''
    env.FEDORA_PRINCIPAL = env.FEDORA_PRINCIPAL ?: 'bpeck/jenkins-continuous-infra.apps.ci.centos.org@FEDORAPROJECT.ORG'
    env.package_url = env.package_url ?: ''
    env.nvr = env.nvr ?: ''
    env.original_spec_nvr = env.original_spec_nvr ?: ''
    env.ANSIBLE_HOST_KEY_CHECKING = env.ANSIBLE_HOST_KEY_CHECKING ?: 'False'

    // If we've been provided an envMap, we set env.key = value
    // Note: This may overwrite above specified values.
    envMap.each { key, value ->
        env."${key.toSTring().trim()}" = value.toString().trim()
    }
}

/**
 * Library to set stage specific environmental variables
 *
 * variables
 *  currentStage - current stage running
 */
def setStageEnvVars(currentStage){
    def stages =
            ["ci-pipeline-rpmbuild"                : [
                    task                     : "./ci-pipeline/tasks/rpmbuild-test",
                    playbook                 : "ci-pipeline/playbooks/setup-rpmbuild-system.yml",
                    ref                      : "fedora/${env.branch}/${env.basearch}/atomic-host",
                    repo                     : "${env.fed_repo}",
                    rev                      : "${env.fed_rev}",
            ],
             "ci-pipeline-ostree-compose"          : [
                     task                     : "./ci-pipeline/tasks/ostree-compose",
                     playbook                 : "ci-pipeline/playbooks/rdgo-setup.yml",
                     ref                      : "fedora/${env.branch}/${env.basearch}/atomic-host",
                     repo                     : "${env.fed_repo}",
                     rev                      : "${env.fed_rev}",
                     basearch                 : "x86_64",
             ],
             "ci-pipeline-ostree-image-compose"    : [
                     task                     : "./ci-pipeline/tasks/ostree-image-compose",
                     playbook                 : "ci-pipeline/playbooks/rdgo-setup.yml",

             ],
             "ci-pipeline-ostree-image-boot-sanity": [
                     task                     : "./ci-pipeline/tasks/ostree-image-compose",
                     playbook                 : "ci-pipeline/playbooks/system-setup.yml",
             ],
             "ci-pipeline-ostree-boot-sanity"      : [
                     task    : "./ci-pipeline/tasks/ostree-boot-image",
                     playbook: "ci-pipeline/playbooks/system-setup.yml",
                     DUFFY_OP: "--allocate"
             ],
             "ci-pipeline-atomic-host-tests"       : [
                     task    : "./ci-pipeline/tasks/atomic-host-tests",
                     playbook: "ci-pipeline/playbooks/system-setup.yml",
             ]
            ]

    // Get the map of env var keys and values and write them to the env global variable
    stages.get(currentStage).each { key, value ->
        env."${key}" = value
    }
}

/**
 * Library to create text and write to file based on current stage and calls runTaskAndReturnLogs() which rsyncs
 * the logs produced from executing a task to artifacts.ci.centos.org
 *
 * variables
 *  currentStage - current stage running
 */
def rsyncData(currentStage){
    def text = "export JENKINS_JOB_NAME=\"${env.JOB_NAME}-${currentStage}\"\n" +
            "export RSYNC_USER=\"${env.RSYNC_USER}\"\n" +
            "export RSYNC_SERVER=\"${env.RSYNC_SERVER}\"\n" +
            "export RSYNC_DIR=\"${env.RSYNC_DIR}\"\n" +
            "export FEDORA_PRINCIPAL=\"${env.FEDORA_PRINCIPAL}\"\n" +
            "export JENKINS_BUILD_TAG=\"${env.BUILD_TAG}-${currentStage}\"\n" +
            "export OSTREE_BRANCH=\"${env.OSTREE_BRANCH}\"\n"

    if (currentStage in ['ci-pipeline-ostree-compose', 'ci-pipeline-ostree-iamge-compose',
                         'ci-pipeline-ostree-image-boot-sanity', 'ci-pipeline-ostree-boot-sanity']) {
        text = text +
                "export HTTP_BASE=\"${env.HTTP_BASE}\"\n" +
                "export branch=\"${env.branch}\"\n"
    }
    if (currentStage == 'ci-pipeline-rpmbuild') {
        text = text +
                "export fed_repo=\"${env.fed_repo}\"\n" +
                "export fed_branch=\"${env.fed_branch}\"\n" +
                "export fed_rev=\"${env.fed_rev}\"\n"

    } else if (currentStage == 'ci-pipeline-ostree-image-boot-sanity') {
        text = text +
                "export ANSIBLE_HOST_KEY_CHECKING=\"False\"\n"
    } else if (currentStage == 'ci-pipeline-ostree-boot-sanity') {
        text = text +
                "export fed_repo=\"${env.fed_repo}\"\n" +
                "export image2boot=\"${env.image2boot}\"\n" +
                "export commit=\"${env.commit}\"\n" +
                "export ANSIBLE_HOST_KEY_CHECKING=\"False\"\n"
    }

    writeFile file: "${env.ORIGIN_WORKSPACE}/task.env",
            text: text
    runTaskAndReturnLogs(currentStage, 'duffy-key')

}

/**
 * Library to provision resources used in the current stage
 *
 * variables
 *  currentStage - current stage running
 */
def provisionResources(currentStage){

    utils.allocateDuffyCciskel()

    echo "Duffy Allocate ran for stage ${currentStage} with option --allocate\r\n" +
            "ORIGIN_WORKSPACE=${env.ORIGIN_WORKSPACE}\r\n" +
            "ORIGIN_BUILD_TAG=${env.ORIGIN_BUILD_TAG}\r\n" +
            "ORIGIN_CLASS=${env.ORIGIN_CLASS}"

    job_props = "${env.ORIGIN_WORKSPACE}/job.props"
    job_props_groovy = "${env.ORIGIN_WORKSPACE}/job.groovy"
    convertProps(job_props, job_props_groovy)
    load(job_props_groovy)

}

/**
 * Library to teardown resources used in the current stage
 *
 * variables
 *   currentStage - current stage running
 */
def teardownResources(currentStage){

    utils.teardownDuffyCciskel()

    echo "Duffy Deallocate ran for stage ${currentStage} with option --teardown\r\n" +
            "DUFFY_HOST=${env.DUFFY_HOST}"
}


def convertProps(file1, file2) {
    def command = $/awk -F'=' '{print "env."$1"=\""$2"\""}' ${file1} > ${file2}/$
    sh command
}