//------------------------------
// GLOBALS
//------------------------------

// the container id of the jenkns slave and the name of the network used in the environment
def nodeId, network, ip;
def stageFailed   = false

//------------------------------
// DATABASE CONFIGS
//------------------------------

def testDatabase = [
    name      : "$env.BUILD_TAG-test-database",
    user      : 'db-test',
    password  : 'g6qf98xy'
]

def accDatabase = [
    name      : 'acc-database',
    user      : 'db-acc',
    password  : 'asiyat37'
]

def web = [
    name      : 'web',
    port      : '8000'
]

def restApi = [
    name      : 'rest-api',
    port      : '9000'
]

def selenium = [
    name      : 'selenium-node',
    port      : '9999'
]


//------------------------------
// COMMIT STAGE
//------------------------------

stage "COMMIT STAGE"

node('gradle')
{
    git 'https://github.com/DavidOpDeBeeck/taskboard.git'
    dir ('taskboard'){
      stash includes: '**', name: 'taskboard'
    }
}

//------------------------------
// TEST ENVIRONMENT STAGE
//------------------------------

stage 'REPOSITORY TESTS'

node ('docker')
{
  // run database with test environment properties
  runContainer( testDatabase.name, 'mysql', "-e MYSQL_DATABASE=taskboard -e MYSQL_USER=$testDatabase.user -e MYSQL_PASSWORD=$testDatabase.password -e MYSQL_ALLOW_EMPTY_PASSWORD=true" )
  // generate a unique network id so we don't have collision
  network = getNetworkId()
  // create a network (used to connect database and gradle slave)
  createNetwork(network)
  // connect the databe to the network with 'mysql' as his alias
  connect(network, testDatabase.name, 'mysql')
}

node ('gradle')
{
  try
  {
    // get the container id of the gradle node
    nodeId = getNodeContainerId()
    // connect the gradle slave to the network
    node ('docker') { connect(network, nodeId, 'docker') }
    // unstash the 'taskboard' files so we can execute gradle tasks
    unstash 'taskboard'
    // we clean the project to secure new test results
    sh "gradle clean"
    // initialise the database with the specified tables
    sh "gradle flywayMigrate -Denv=test"
    // execute the repository tests
    sh "gradle repositoryTests -Denv=test"
  } catch (err)
  {
    // when a gradle task fails we mark the stage as failed to be able to shutdown the dock containers
    stageFailed = true
  }
  // archive the test results so we can use them at a later time
  step([$class: 'JUnitResultArchiver', testResults: "**/taskboard-domain/build/test-results/TEST-*.xml"])
}

node ('docker')
{
  // we disconnect the gradle slave and the test database from the network
  disconnect(network,nodeId)
  disconnect(network,testDatabase.name)
  // we remove the network and the test database containers
  removeNetwork(network)
  removeContainer(testDatabase.name)
  // if the stage has failed we throw an error so execution is stopped
  if (stageFailed){
    error 'Stage failed'
  }
}

// we ask the user if he wants to continue
input message: 'Do you want to continue to acceptance?', ok: 'yes'

//------------------------------
// ACCEPTANCE ENVIRONMENT STAGE
//------------------------------

stage "REST API TESTS"

node ('docker')
{
  // run the database with the acceptance properties
  runContainer(accDatabase.name,'mysql', "-d -e MYSQL_DATABASE=taskboard -e MYSQL_USER=$accDatabase.user -e MYSQL_PASSWORD=$accDatabase.password -e MYSQL_ALLOW_EMPTY_PASSWORD=true" )
  // generate a unique network id so we don't have collision
  network = getNetworkId()
  // create a network (used to connect database and gradle slave)
  createNetwork(network)
  // connect the database to the network with 'mysql' as his alias
  connect(network, accDatabase.name, 'mysql')
}

node ('gradle')
{
    try
    {
      // get the container id of the gradle node
      nodeId = getNodeContainerId()
      // connect the gradle slave to the network
      node ('docker') { connect(network, nodeId, 'docker') }
      // unstash the 'taskboard' files so we can execute gradle tasks
      unstash 'taskboard'
      // we clean the project to secure new test results
      sh "gradle clean"
      // initialise the database with the specified acceptance tables
      sh "gradle flywayMigrate -Denv=acc"
      // execute the rest api tests
      sh "gradle acceptanceTests -Denv=acc"
    } catch (err)
    {
      // when a gradle task fails we mark the stage as failed to be able to shutdown the dock containers
      stageFailed = true
    }
    // archive the test results so we can use them at a later time
    step([$class: 'JUnitResultArchiver', testResults: "**/taskboard-rest-api/build/test-results/TEST-*.xml"])
}

node ('docker')
{
  // we disconnect the gradle slave and the acceptance database from the network
  disconnect(network, nodeId)
  disconnect(network, accDatabase.name)
  // we remove the network container
  removeNetwork(network)
  // if the stage has failed we throw an error so execution is stopped
  if (stageFailed){
    error 'Stage failed'
  }
}

stage "WEB TESTS"

node ('gradle')
{
  // we unstash the 'taskboard' files to build the rest-api
  unstash 'taskboard'
  // we ignore tests because the slave isn't connected to a database
  sh "gradle build -x test"
  // we archive the rest api jar for later use
  archive '**/taskboard-rest-api/build/libs/*.jar'
  // we stash the acceptance properties to use with the rest api later on
  dir ('conf') { stash includes: 'acc.properties', name: 'config' }
  // we stash the rest api files for later use
  dir ('taskboard-rest-api') { stash includes: '**', name: 'rest-api' }
  // we stash the web files for later use
  dir ('taskboard-web') { stash includes: '**', name: 'web' }
}

// network configuration for 'deploy'
node ('docker')
{
  // generate a unique network id so we don't have collision
  network = getNetworkId()
  // create a network (used to connect web, rest api, database and gradle slave)
  createNetwork(network)
  // connect the database to the network with 'mysql' as his alias
  connect(network, accDatabase.name, 'mysql')
  // clean the former acceptance containers
  removeContainer(web.name);
  removeContainer(restApi.name);
}

parallel (
    webSetup : {
      node ('docker')
      {
        ip = env.DOCKER_HOST.split(":")[0];
        // we unstash the web project so we can build the docker images and run it
        unstash 'web'
        // build the docker image (with the id of the build to make it unique)
        buildImage( web.name , "--build-arg API_HOST=$ip --build-arg API_PORT=$restApi.port" )
        // run the image and expose the web port (8000)
        runContainer( web.name, web.name, "-p $web.port:8000" )
        // connect the web frontend to the network
        connect(network, web.name, 'web')
      }
    },
    apiSetup : {
      node ('docker')
      {
        // we unstash the config file and the rest api files to build the docker images and run it
        unstash 'config'
        unstash 'rest-api'
        // build the docker image (with the id of the build to make it unique)
        buildImage( restApi.name )
        // run the image and expose the web port (8080)
        runContainer( restApi.name, restApi.name, "-p $restApi.port:8080" )
        // connect the rest api to the network
        connect(network, restApi.name, 'rest-api')
      }
    },
    seleniumSetup : {
      node ('docker')
      {
        // run the image and expose the web port (4444)
        runContainer( selenium.name, 'selenium/standalone-firefox', "-p $selenium.port:4444" )
        // connect the selenium node to the network
        connect(network, selenium.name, 'selenium-node')
      }
    }
)

node ('gradle')
{
  try
  {
    // get the container id of the gradle node
    nodeId = getNodeContainerId()
    // connect the gradle slave to the network
    node ('docker') { connect(network, nodeId, 'docker') }
    // unstash the 'taskboard' files
    unstash 'taskboard'
    // we run the web tests
    sh "gradle webTests -Denv=acc-webdriver"
  } catch (err)
  {
    // when a gradle task fails we mark the stage as failed to be able to shutdown the dock containers
    stageFailed = true
  }
  // we record the test result for later use
  step([$class: 'JUnitResultArchiver', testResults: "**/taskboard-web-test/build/test-results/TEST-*.xml"])
}

node ('docker')
{
  // remove the selenium node because we don't need it anymore
  removeContainer( 'selenium-node' )
  // if the stage has failed we throw an error so execution is stopped
  if (stageFailed) {
    error 'Stage failed'
  }
}

input message: "Does http://$ip:$web.port look ok?", ok: 'yes'

//------------------------------
// NODE FUNCTIONS
//------------------------------

def getNodeContainerId()
{
  return env.NODE_NAME.split("-")[1]
}

//------------------------------
// NETWORK
//------------------------------

def getNetworkId()
{
  return "network-$env.BUILD_NUMBER";
}

//------------------------------
// DOCKER FUNCTIONS
//------------------------------

def buildImage( name , def options = "" )
{
  sh "docker build -t $name $options ."
}

def runContainer( name , image , options )
{
  sh "docker run -d $options --name=$name $image || echo 'Container $name does already exist!'"
}

def removeContainer( name )
{
  sh "docker rm -f $name || echo 'Container $name does not exist!'"
}

def createNetwork( name )
{
  sh "docker network create -d bridge $name"
}

def removeNetwork( name )
{
	sh "docker network rm $name || echo 'Network $name does not exist!'"
}

def connect( network , container, alias )
{
  sh "docker network connect --alias $alias $network $container"
}

def disconnect( network , container )
{
  sh "docker network disconnect $network $container"
}
