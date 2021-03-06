#!/usr/bin/env groovy


def get_onmetal_ip() {

    // Get the onmetal host IP address
    if (fileExists('hosts')) {

        String hosts = readFile("hosts")
        String ip = hosts.substring(hosts.indexOf('=')+1).replaceAll("[\n\r]", "")
        return (ip)

    } else {

        return (null)

    }

}


def onmetal_provision(datacenter_tag) {

    String ip

    try {

        // Spin onMetal Server
        echo '<KC-VER> Running the following playbook: build_onmetal'
        ansiblePlaybook playbook: 'build_onmetal.yaml', sudoUser: null, tags: "${datacenter_tag}"

        // Verify onMetal server data
        echo 'Running the following playbook: get_onmetal_facts'
        ansiblePlaybook inventory: 'hosts', playbook: 'get_onmetal_facts.yaml', sudoUser: null, tags: "${datacenter_tag}"
    
        // Get server IP address
        ip = get_onmetal_ip()

        // Prepare OnMetal server, retry up to 5 times for the command to work
        echo 'Running the following playbook: prepare_onmetal'
        retry(5) {
            ansiblePlaybook inventory: 'hosts', playbook: 'prepare_onmetal.yaml', sudoUser: null
        }

        // Apply CPU fix - will restart server (~5 min)
        echo 'Running the following playbook: set_onmetal_cpu'
        ansiblePlaybook inventory: 'hosts', playbook: 'set_onmetal_cpu.yaml', sudoUser: null

    } catch (err) {
        // If there is an error, tear down and re-raise the exception
        delete_onmetal(datacenter_tag)
        throw err
    }

    return (ip)

}


def vm_provision() {

    // Configure VMs onMetal server
    echo 'Running the following playbook: configure_onmetal'
    ansiblePlaybook inventory: 'hosts', playbook: 'configure_onmetal.yaml', sudoUser: null

    // Create VMs where OSA will be deployed
    echo 'Running the following playbook: create_lab'
    ansiblePlaybook inventory: 'hosts', playbook: 'create_lab.yaml', sudoUser: null

}


def vm_preparation_for_osa(release = 'stable/mitaka') {

    try {

        // Prepare each VM for OSA installation
        echo "Running the following playbook: prepare_for_osa, using the following OSA release: ${release}"
        ansiblePlaybook extras: "-e openstack_release=${release}", inventory: 'hosts', playbook: 'prepare_for_osa.yaml', sudoUser: null

    } catch (err) {
    
        // If there is an error, tear down and re-raise the exception
        delete_virtual_resources()
        //delete_onmetal(datacenter_tag)
        throw err

    } 

} 


def deploy_openstack() {
    
    try {

        echo 'Running the following playbook: deploy_osa'
        ansiblePlaybook inventory: 'hosts', playbook: 'deploy_osa.yaml', sudoUser: null

    } catch (err) {

        // If there is an error, tear down and re-raise the exception
        delete_virtual_resources()
        //delete_onmetal(datacenter_tag)
        throw err

    }

}


def upgrade_openstack(release = 'stable/mitaka') {

    // Upgrade OSA to a specific release
    echo "Running the following playbook: upgrade_osa, to upgrade to the following release: ${release}"
    ansiblePlaybook extras: "-e openstack_release=${release}", inventory: 'hosts', playbook: 'upgrade_osa.yaml', sudoUser: null  

}


def configure_tempest() {

    String host_ip = get_onmetal_ip()

    // Install Tempest on the onMetal host
    echo 'Installing Tempest on the onMetal host'
    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    git clone https://github.com/openstack/tempest.git /root/tempest
    cd /root/tempest/
    sudo pip install -r requirements.txt
    testr init
    cd /root/tempest/etc/
    wget https://raw.githubusercontent.com/CasJ/openstack_one_node_ci/master/tempest.conf
    mkdir /root/subunit
    '''
    """

    // Get the tempest config file generated by the OSA deployment
    echo 'Configuring tempest based on the ansible deployment'
    sh """
    # Copy the config file from the infra utility VM to the onMetal host 
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    scp infra01_utility:/opt/tempest_*/etc/tempest.conf /root/tempest/etc/tempest.conf.osa
    ''' 
    """
   
    // Configure tempest based on the OSA deployment
    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    keys="admin_password image_ref image_ref_alt uri uri_v3 public_network_id reseller_admin_role"
    for key in \$keys
    do
        a="\${key} ="
        sed -ir "s|\$a.*|\$a|g" /root/tempest/etc/tempest.conf
        b=`cat /root/tempest/etc/tempest.conf.osa | grep "\$a"`
        sed -ir "s|\$a|\$b|g" /root/tempest/etc/tempest.conf
    done
    '''
    """

}


def run_tempest_smoke_tests(results_file = 'results') {

    String host_ip = get_onmetal_ip()

    // Run the tests and store the results in ~/subunit/before
    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    cd /root/tempest/
    stream_id=`cat .testrepository/next-stream`
    ostestr --no-slowest --regex smoke
    cp .testrepository/\$stream_id /root/subunit/${results_file}
    '''
    """
    
}


def delete_virtual_resources() {

    echo 'Running the following playbook: destroy_virtual_machines'
    ansiblePlaybook inventory: 'hosts', playbook: 'destroy_virtual_machines.yaml', sudoUser: null
    echo 'Running the following playbook: destroy_virtual_networks'
    ansiblePlaybook inventory: 'hosts', playbook: 'destroy_virtual_networks.yaml', sudoUser: null
    echo 'Running the following playbook: destroy_lab_state_file'
    ansiblePlaybook inventory: 'hosts', playbook: 'destroy_lab_state_file.yaml', sudoUser: null

}


def delete_onmetal(datacenter_tag) {

    //echo 'Running the following playbook: destroy_onmetal'
    //ansiblePlaybook inventory: 'hosts', playbook: 'destroy_onmetal.yaml', sudoUser: null, tags: "${datacenter_tag}"

    String host_ip = get_onmetal_ip()
    if (host_ip != null) {
        sh """
        ssh-keygen -R ${host_ip}
        """
    }

}


// The external code must return it's contents as an object
return this;

