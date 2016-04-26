//------------------------------
// GLOBALS
//------------------------------

def stageFailed = false;

//------------------------------
// DATABASE CONFIGS
//------------------------------

def testDatabase = [
    ip        : '192.168.99.100',
    port      : '50000',
    user      : 'db-test',
    password  : 'g6qf98xy',
    instance  :  null
]

def accDatabase = [
    ip        : '192.168.99.100',
    port      : '60000',
    user      : 'db-acc',
    password  : 'asiyat37',
    instance  :  null
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
  testDatabase.instance = docker.image('mysql').run("-it -p $testDatabase.port:3306 -e MYSQL_DATABASE=taskboard -e MYSQL_USER=$testDatabase.user -e MYSQL_PASSWORD=$testDatabase.password -e MYSQL_ALLOW_EMPTY_PASSWORD=true")
}

node ('gradle')
{
  try {
    unstash 'taskboard'
    sh "gradle flywayMigrate -Denv=test"
    sh "gradle repositoryTests -Denv=test"
    step([$class: 'JUnitResultArchiver', testResults: "**/taskboard-domain/build/test-results/TEST-*.xml"])
  } catch (err){
    stageFailed = true
  }
}

node ('docker')
{
    mysqlContainer.stop()
    if (stageFailed){
      error 'Stage failed'
    }
}

//------------------------------
// ACCEPTANCE ENVIRONMENT STAGE
//------------------------------

stage "REST API TESTS"

node ('docker')
{
  accDatabase.instance = docker.image('mysql').run("-it -p $accDatabase.port:3306 -e MYSQL_DATABASE=taskboard -e MYSQL_USER=$accDatabase.user -e MYSQL_PASSWORD=$accDatabase.password -e MYSQL_ALLOW_EMPTY_PASSWORD=true")
}

node ('gradle')
{
    try {
      unstash 'taskboard'
      sh "gradle flywayMigrate -Denv=acc"
      sh "gradle acceptanceTests -Denv=acc"
      step([$class: 'JUnitResultArchiver', testResults: "**/taskboard-rest-api/build/test-results/TEST-*.xml"])
    } catch (err){
      stageFailed = true
    }
}

node ('docker')
{
    if (stageFailed){
      mysqlContainer.stop()
      error 'Stage failed'
    }
}

//------------------------------
// DEPLOY STAGE
//------------------------------

stage "DEPLOY"

node ('docker')
{
  mysqlContainer = mysqlImage.run("-it -p $dbPort:3306")
}

node ('gradle')
{
  unstash 'rest-api'
  sh "gradle build -x test"
  archive '**/taskboard-rest-api/build/libs/*.jar'
  dir ('conf') {
    stash includes: '*', name: 'API-CONFIG'
  }
  dir ('taskboard-rest-api/build/libs/') {
    stash includes: '*', name: 'API'
  }
}

node ('docker && java')
{
  unstash 'API'
  unstash 'API-CONFIG'
  sh 'ls -l'
  //sh 'mv test.properties application.properties'
  //sh 'java -jar -Ddatasource.url=jdbc:mysql://192.168.99.100:2376:3306/taskboard taskboard-rest-api-1.0.jar'
}

node ('docker')
{
    mysqlContainer.stop()
    if (stageFailed){
      error 'Stage failed'
    }
}

// breng web op python docker
// breng rest api op java docker
// breng web tests op java docker