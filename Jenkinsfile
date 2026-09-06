pipeline {
    agent { label 'peegeeq-linux' }

    parameters {
        choice(
            name: 'RUN_MODE',
            choices: ['verify', 'postgresql-compatibility', 'recorder-calibration', 'benchmark-characterisation'],
            description: 'Verification is the default. Calibration and benchmark characterisation are explicit, non-concurrent runs.'
        )
        choice(
            name: 'POSTGRES_IMAGE',
            choices: ['postgres:18.3-alpine', 'postgres:17.11-alpine', 'postgres:16.13-alpine', 'postgres:15.17-alpine'],
            description: 'PostgreSQL image for recorder/benchmark work. Compatibility mode always exercises all four images.'
        )
        string(name: 'DEPLOYMENT_TOPOLOGY',
            defaultValue: 'Jenkins peegeeq-linux worker; benchmark JVM and Testcontainers PostgreSQL on the same rootful Docker engine',
            description: 'Truthful deployment/topology description retained with benchmark evidence.')

        string(name: 'CALIBRATION_FORKS', defaultValue: '3', description: 'Sequential fresh-JVM recorder calibration forks (1-20).')
        choice(name: 'CALIBRATION_HEAP', choices: ['64m', '32m', '128m', '256m'], description: 'Maximum heap for each calibration JVM.')
        string(name: 'CALIBRATION_CONCURRENCY', defaultValue: '4', description: 'Shared recorder tasks (1-64).')
        string(name: 'CALIBRATION_BUCKETS', defaultValue: '1024', description: 'Finite latency buckets (1-65536).')
        string(name: 'CALIBRATION_WARMUP_MILLIS', defaultValue: '1000', description: 'Warm-up time per calibration fork.')
        string(name: 'CALIBRATION_MEASUREMENT_MILLIS', defaultValue: '30000', description: 'Measured time per calibration fork.')
        string(name: 'CALIBRATION_WINDOW_MILLIS', defaultValue: '50', description: 'Recorder observation window in milliseconds.')
        string(name: 'CALIBRATION_CHECKPOINT_WINDOWS', defaultValue: '10', description: 'Observation windows per persisted checkpoint (1-128).')
        string(name: 'CALIBRATION_MAX_EVIDENCE_BYTES', defaultValue: '67108864', description: 'Hard maximum bytes for one calibration evidence file.')

        string(name: 'BENCHMARK_RUNS', defaultValue: '3', description: 'Sequential repetitions in the legacy characterisation capture (1-20).')
        string(name: 'BENCHMARK_CONCURRENCY', defaultValue: '8', description: 'Foreground benchmark concurrency.')
        string(name: 'BENCHMARK_POOL_SIZE', defaultValue: '12', description: 'PostgreSQL pool size; must exceed concurrency.')
        string(name: 'BENCHMARK_WARMUP_SECONDS', defaultValue: '5', description: 'Warm-up before each measured workload.')
        string(name: 'BENCHMARK_DURATION_SECONDS', defaultValue: '30', description: 'Measured duration for each workload.')
    }

    environment {
        JAVA_HOME = '/usr/lib/jvm/temurin-25-jdk-amd64'
        MAVEN_HOME = '/opt/maven'
        PATH = "/usr/lib/jvm/temurin-25-jdk-amd64/bin:/opt/maven/bin:${env.PATH}"
        CI = 'true'
        RUN_MODE_EFFECTIVE = "${params.RUN_MODE ?: 'verify'}"
        POSTGRES_IMAGE_EFFECTIVE = "${params.POSTGRES_IMAGE ?: 'postgres:18.3-alpine'}"
        DEPLOYMENT_TOPOLOGY_EFFECTIVE = "${params.DEPLOYMENT_TOPOLOGY ?: 'Jenkins peegeeq-linux worker; benchmark JVM and Testcontainers PostgreSQL on the same rootful Docker engine'}"
    }

    options {
        skipDefaultCheckout(true)
        timeout(time: 12, unit: 'HOURS')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
        timestamps()
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Environment contract') {
            steps {
                sh '''
                    set -u
                    mkdir -p logs benchmark-results

                    status=0
                    (
                        set -eu
                        echo "build_tag=$BUILD_TAG"
                        echo "node_name=$NODE_NAME"
                        echo "workspace=$WORKSPACE"
                        echo "run_mode=$RUN_MODE_EFFECTIVE"
                        echo "deployment_topology=$DEPLOYMENT_TOPOLOGY_EFFECTIVE"
                        date --utc --iso-8601=seconds
                        uname -a
                        java -version
                        javac -version
                        mvn -version
                        git --version
                        id

                        test "$(readlink -f "$(command -v java)")" = \
                          '/usr/lib/jvm/temurin-25-jdk-amd64/bin/java'
                        test "$(readlink -f "$(command -v javac)")" = \
                          '/usr/lib/jvm/temurin-25-jdk-amd64/bin/javac'
                        test -r "$HOME/.m2/toolchains.xml"
                        grep -F '<version>25</version>' "$HOME/.m2/toolchains.xml"
                        grep -F '<jdkHome>/usr/lib/jvm/temurin-25-jdk-amd64</jdkHome>' \
                          "$HOME/.m2/toolchains.xml"

                        id -nG | tr ' ' '\n' | grep -qx docker
                        test -S /var/run/docker.sock
                        test -z "${DOCKER_HOST:-}"
                        stat -c '%A %U %G %n' /var/run/docker.sock
                        docker version --format \
                          'client={{.Client.Version}} client_api={{.Client.APIVersion}} server={{.Server.Version}} server_api={{.Server.APIVersion}} server_min_api={{.Server.MinAPIVersion}}'
                        docker context show
                        docker info --format 'driver={{.Driver}} security={{json .SecurityOptions}}'

                        df -h /
                        free -h
                        swapon --show
                    ) > logs/jenkins-worker-environment.log 2>&1 || status=$?
                    cat logs/jenkins-worker-environment.log
                    exit "$status"
                '''
            }
        }

        stage('Rebuild') {
            steps {
                sh '''
                    set -eu
                    bash -o pipefail -c \
                      'mvn --batch-mode --no-transfer-progress clean install -DskipTests \
                      2>&1 | tee logs/rebuild.log'
                '''
            }
        }

        stage('Reactor verification') {
            when { expression { (params.RUN_MODE ?: 'verify') == 'verify' } }
            options { timeout(time: 150, unit: 'MINUTES') }
            steps {
                sh '''
                    bash -o pipefail -c \
                      'mvn --batch-mode --no-transfer-progress verify \
                      -Dpeegeeq.test.postgres.image="$POSTGRES_IMAGE_EFFECTIVE" \
                      2>&1 | tee logs/reactor-verify.log'
                '''
            }
        }

        stage('PostgreSQL compatibility') {
            when { expression { params.RUN_MODE == 'postgresql-compatibility' } }
            options { timeout(time: 5, unit: 'HOURS') }
            steps {
                script {
                    def images = [
                        '15.17': 'postgres:15.17-alpine',
                        '16.13': 'postgres:16.13-alpine',
                        '17.11': 'postgres:17.11-alpine',
                        '18.3':  'postgres:18.3-alpine'
                    ]
                    images.each { version, image ->
                        stage("PostgreSQL ${version}") {
                            catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                                withEnv(["PG_VERSION=${version}", "PG_IMAGE=${image}"]) {
                                    sh '''
                                        set -eu
                                        find . -type f -not -path './target/jenkins-junit/*' \\( -path '*/target/surefire-reports/*.xml' -o -path '*/target/failsafe-reports/*.xml' \\) -delete
                                        bash -o pipefail -c \
                                          'mvn --batch-mode --no-transfer-progress verify \
                                          -Dpeegeeq.test.postgres.image="$PG_IMAGE" \
                                          2>&1 | tee "logs/postgresql-$PG_VERSION.log"'
                                    '''
                                }
                            }
                            withEnv(["PG_VERSION=${version}"]) {
                                sh '''
                                    set -eu
                                    destination="target/jenkins-junit/postgresql-$PG_VERSION"
                                    mkdir -p "$destination"
                                    find . -type f -not -path './target/jenkins-junit/*' \\( -path '*/target/surefire-reports/*.xml' -o -path '*/target/failsafe-reports/*.xml' \\) \
                                      -exec cp --parents '{}' "$destination" \\;
                                '''
                            }
                        }
                    }
                }
            }
        }

        stage('Recorder calibration') {
            when { expression { params.RUN_MODE == 'recorder-calibration' } }
            options { timeout(time: 6, unit: 'HOURS') }
            steps {
                sh '''
                    set -eu

                    require_uint() {
                        name="$1" value="$2" minimum="$3" maximum="$4"
                        case "$value" in ''|*[!0-9]*) echo "$name must be an integer" >&2; exit 2;; esac
                        if [ "$value" -lt "$minimum" ] || [ "$value" -gt "$maximum" ]; then
                            echo "$name must be between $minimum and $maximum" >&2
                            exit 2
                        fi
                    }

                    require_uint CALIBRATION_FORKS "$CALIBRATION_FORKS" 1 20
                    require_uint CALIBRATION_CONCURRENCY "$CALIBRATION_CONCURRENCY" 1 64
                    require_uint CALIBRATION_BUCKETS "$CALIBRATION_BUCKETS" 1 65536
                    require_uint CALIBRATION_WARMUP_MILLIS "$CALIBRATION_WARMUP_MILLIS" 0 86400000
                    require_uint CALIBRATION_MEASUREMENT_MILLIS "$CALIBRATION_MEASUREMENT_MILLIS" 1 86400000
                    require_uint CALIBRATION_WINDOW_MILLIS "$CALIBRATION_WINDOW_MILLIS" 1 3600000
                    require_uint CALIBRATION_CHECKPOINT_WINDOWS "$CALIBRATION_CHECKPOINT_WINDOWS" 1 128
                    require_uint CALIBRATION_MAX_EVIDENCE_BYTES "$CALIBRATION_MAX_EVIDENCE_BYTES" 131072 2147483647

                    output="$WORKSPACE/benchmark-results/$BUILD_TAG/calibration"
                    mkdir -p "$output"
                    fork=0
                    while [ "$fork" -lt "$CALIBRATION_FORKS" ]; do
                        bash -o pipefail -c \
                          'mvn --batch-mode --no-transfer-progress \
                          -pl peegee-cache-benchmarks -am integration-test \
                          -Pbenchmark-calibration -DskipTests \
                          -Dpeegeeq.calibration.heap="$CALIBRATION_HEAP" \
                          -Dpeegeeq.calibration.concurrency="$CALIBRATION_CONCURRENCY" \
                          -Dpeegeeq.calibration.bucketCount="$CALIBRATION_BUCKETS" \
                          -Dpeegeeq.calibration.warmupMillis="$CALIBRATION_WARMUP_MILLIS" \
                          -Dpeegeeq.calibration.measurementMillis="$CALIBRATION_MEASUREMENT_MILLIS" \
                          -Dpeegeeq.calibration.windowMillis="$CALIBRATION_WINDOW_MILLIS" \
                          -Dpeegeeq.calibration.checkpointWindows="$CALIBRATION_CHECKPOINT_WINDOWS" \
                          -Dpeegeeq.calibration.maximumEvidenceBytes="$CALIBRATION_MAX_EVIDENCE_BYTES" \
                          -Dpeegeeq.calibration.outputDirectory="'"$output"'" \
                          -Dpeegeeq.calibration.forkIndex="'"$fork"'" \
                          2>&1 | tee "logs/recorder-calibration-fork-'"$fork"'.log"'
                        fork=$((fork + 1))
                    done
                '''
            }
        }

        stage('Benchmark characterisation') {
            when { expression { params.RUN_MODE == 'benchmark-characterisation' } }
            options { timeout(time: 6, unit: 'HOURS') }
            steps {
                sh '''
                    set -eu

                    require_uint() {
                        name="$1" value="$2" minimum="$3" maximum="$4"
                        case "$value" in ''|*[!0-9]*) echo "$name must be an integer" >&2; exit 2;; esac
                        if [ "$value" -lt "$minimum" ] || [ "$value" -gt "$maximum" ]; then
                            echo "$name must be between $minimum and $maximum" >&2
                            exit 2
                        fi
                    }

                    require_uint BENCHMARK_RUNS "$BENCHMARK_RUNS" 1 20
                    require_uint BENCHMARK_CONCURRENCY "$BENCHMARK_CONCURRENCY" 1 100000
                    require_uint BENCHMARK_POOL_SIZE "$BENCHMARK_POOL_SIZE" 2 100001
                    require_uint BENCHMARK_WARMUP_SECONDS "$BENCHMARK_WARMUP_SECONDS" 0 86400
                    require_uint BENCHMARK_DURATION_SECONDS "$BENCHMARK_DURATION_SECONDS" 1 86400
                    if [ "$BENCHMARK_POOL_SIZE" -le "$BENCHMARK_CONCURRENCY" ]; then
                        echo 'BENCHMARK_POOL_SIZE must exceed BENCHMARK_CONCURRENCY' >&2
                        exit 2
                    fi

                    output="$WORKSPACE/benchmark-results/$BUILD_TAG/legacy-characterisation"
                    mkdir -p "$output"
                    bash -o pipefail -c \
                      'mvn --batch-mode --no-transfer-progress \
                      -pl peegee-cache-benchmarks -am integration-test \
                      -Pbenchmark-capture -DskipTests \
                      -Dpeegeeq.benchmark.capture.outputRoot="'"$output"'" \
                      -Dpeegeeq.benchmark.capture.runs="$BENCHMARK_RUNS" \
                      -Dpeegeeq.benchmark.capture.requireCleanGit=true \
                      -Dpeegeeq.benchmark.capture.stopOnFailure=false \
                      -Dpeegeeq.benchmark.capture.topology="$DEPLOYMENT_TOPOLOGY" \
                      -Dpeegeeq.test.postgres.image="$POSTGRES_IMAGE" \
                      -Dpeegeeq.benchmark.concurrency="$BENCHMARK_CONCURRENCY" \
                      -Dpeegeeq.benchmark.poolSize="$BENCHMARK_POOL_SIZE" \
                      -Dpeegeeq.benchmark.warmupSeconds="$BENCHMARK_WARMUP_SECONDS" \
                      -Dpeegeeq.benchmark.durationSeconds="$BENCHMARK_DURATION_SECONDS" \
                      -Dpeegeeq.benchmark.minimumThroughput=0.000001 \
                      -Dpeegeeq.benchmark.maximumP99Millis=86400000 \
                      -Dpeegeeq.benchmark.maximumFailoverRecoveryMillis=86400000 \
                      -Dpeegeeq.benchmark.maximumExpiryLagMillis=86400000 \
                      -Dpeegeeq.benchmark.maximumTelemetryOverheadPercent=1000000 \
                      2>&1 | tee logs/benchmark-characterisation.log'
                '''
            }
        }
    }

    post {
        always {
            script {
                try {
                    def junitPattern = params.RUN_MODE == 'postgresql-compatibility'
                        ? 'target/jenkins-junit/**/*.xml'
                        : '**/target/surefire-reports/*.xml,**/target/failsafe-reports/*.xml'
                    junit(
                        testResults: junitPattern,
                        allowEmptyResults: !((params.RUN_MODE ?: 'verify') in ['verify', 'postgresql-compatibility'])
                    )
                } finally {
                    archiveArtifacts(
                        artifacts: 'logs/**,benchmark-results/**,**/target/playwright-evidence.html,**/target/playwright-artifacts/**,**/target/ui-reports/**,**/target/surefire-reports/**,**/target/failsafe-reports/**',
                        allowEmptyArchive: true,
                        fingerprint: true
                    )
                }
            }
        }
        cleanup {
            deleteDir()
        }
    }
}
