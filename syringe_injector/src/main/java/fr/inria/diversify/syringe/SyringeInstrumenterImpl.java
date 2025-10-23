package fr.inria.diversify.syringe;

import fr.inria.diversify.buildSystem.maven.MavenBuilder;
import fr.inria.diversify.syringe.dependencies.DependencyResolver;
import fr.inria.diversify.syringe.detectors.Detector;
import fr.inria.diversify.syringe.events.DetectionListener;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.eclipse.jdt.internal.compiler.problem.AbortCompilation;
import spoon.Launcher;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * The Process class is the central manager in charge
 * <p/>
 * Created by marodrig on 08/12/2014.
 */
public class SyringeInstrumenterImpl implements SyringeInstrumenter {

    final static Logger logger = Logger.getLogger(SyringeInstrumenterImpl.class);

    private String pomPath;

    /**
     * Some jars could not be resolved by the maven resolver.
     */
    private List<String> classPath;

    //Indicates that we must initialize the output folder
    private boolean outPutDirReady = false;

    //Parent folder of the project
    private String projectDir;

    //Output where the instrumented code is going to be stored
    private String outputDir;

    //Directory where the production code is
    private String productionDir;

    //MavenDependencyResolver resolver = null;

    //Map with all the IDs of the detected elements
    private IdMap idMap;

    //Properties to be written to the property file of the logger
    private Properties loggerProperties;
    private String loggerPropertiesFile;

    //Time out of the maven build
    private int buildTimeOut;

    private boolean useClasspath;

    /**
     * Java version
     */
    private int complianceLevel;

    private boolean fileByFile;

    private HashSet<String> failedDirs;


    public SyringeInstrumenterImpl() {
        complianceLevel = 8;
        useClasspath = true;
        idMap = new IdMap();
        failedDirs = new HashSet<>();
    }

    /**
     * Creates the LightInstru object
     *
     * @param projectDir    Directory of the project being instrumented
     * @param productionDir Production dir of the project. Is a RELATIVE path to the project.
     * @param outputDir     Output directory where the instrumented code is going to be stored
     */
    public SyringeInstrumenterImpl(String projectDir, String productionDir, String outputDir) {
        this();
        this.projectDir = projectDir;
        this.productionDir = productionDir;
        this.outputDir = outputDir;
    }

    /**
     * Updates the logger files in the output
     *
     * @param configuration Configuration holding all logger files
     * @throws IOException
     */
    public void updateLogger(Configuration configuration) throws IOException {
        //Copy the logger files given in the configuration to the output directory
        for (Configuration.LoggerPath p : configuration.getLoggerClassFiles()) {
            p.copyTo(getOutputDir());
        }
    }

     /* public void resolveDependencies(String pomPath) {

        //Auto imports in case dependencies could not be properly found
        //boolean autoImports = false;
        if (resolver == null && (useClasspath || fileByFile)) {
            try {
                //Resolve dependencies in order to build as much AST as possible
                resolver = new MavenDependencyResolver();
                resolver.setManualClassPath(classPath);
                resolver.resolveDependencies(projectDir + "/pom.xml");
            } catch (FileNotFoundException e) {
                logger.warn("Could not found POM file. Using auto imports");
                throw e;
            }

    }}*/

    /**
     * Instrument the code and stores a local copy of it
     */
    @Override
    public void instrument(Configuration configuration) throws Exception {
        logger.info("Instrumenting " + configuration.getDescription());
        logger.info("Project Dir " + projectDir);
        logger.info("Output Dir " + outputDir);

        //Updates the logger files in the output
        updateLogger(configuration);

        //Resolve dependencies of the project
        if ( useClasspath ) {
            DependencyResolver resolver = new DependencyResolver();
            resolver.resolve(configuration, getPomPath(), getManualClassPath());
        }

        System.out.println("@@@@@@@@@@@@@@@@ output dir: " + getOutputDir());
        System.out.println("@@@@@@@@@@@@@@@@ project dir: " + getProjectDir());
        //This is done only once.
        // if (outPutDirReady == false) {
        //     File dir = new File(getOutputDir());
        //     dir.mkdirs();
        //     FileUtils.copyDirectory(new File(getProjectDir()), dir);
        // }
        //Don't initialize twice
        outPutDirReady = true;
        System.out.println("&&&&&&&&&&&&&&&&&&&&&&&&& is file by file: " + isFileByFile());


        //Build the factory for the part of the project being instrumented
        ArrayList<String> src = new ArrayList<>();
        src.add(projectDir + configuration.getSourceDir()); //Always add all the source code in the production dir.
        // src.add(configuration.getSourceDir()); //Always add all the source code in the production dir.
        System.out.println("source dir: " + configuration.getSourceDir());
        System.out.println("dectors: " + configuration.getDetectors());
        //If the configuration source dir don't lies within the production, then add it:
        //String s = projectDir + configuration.getSourceDir();
        //if (!src.get(0).startsWith(s) && !s.startsWith(src.get(0))) src.add(s);

        //Link the detectors with the injectors listening to their particular events
        for (Detector d : configuration.getDetectors()) {
            d.setIdMap(idMap);
            //Collect relevant injectors to the events this detector will detect
            for (Map.Entry<String, Collection<DetectionListener>> e : configuration.getInjectors().entrySet())
                for (DetectionListener eventListener : e.getValue())
                    d.addListener(e.getKey(), eventListener);
        }

        //Detect and listen
        if (isFileByFile()) {
            setUseClassPath(true);
            walk(configuration, src, null);
            setUseClassPath(false);
            if ( failedDirs.size() > 0 ) walk(configuration, src, failedDirs);
            setUseClassPath(useClasspath);
        }
        else {
            launchInjection(configuration, src);
        }


    }

    /**
     * Inject a given configuration and a set of sources, launchInjection them
     */
    private void launchInjection(Configuration configuration, List<String> src) {

        if (logger.isInfoEnabled()) for (String s : src) logger.info("Processing: " + s);

        final Launcher launcher = LauncherBuilder.build(src, outputDir + configuration.getSourceDir());
        // Environment env = launcher.getEnvironment();
        // env.setComplianceLevel(getComplianceLevel());
        // env.setNoClasspath(!useClasspath);
        // env.setAutoImports(true);
        // java.util.List<String> cp = new java.util.ArrayList<>();
        String cp_str = "/home/jovyan/.m2/repository/com/google/code/gson/gson/2.8.9/gson-2.8.9.jar:/home/jovyan/.m2/repository/com/squareup/moshi/moshi/1.11.0/moshi-1.11.0.jar:/home/jovyan/.m2/repository/com/esotericsoftware/kryo/5.0.3/kryo-5.0.3.jar:/home/jovyan/.m2/repository/com/esotericsoftware/reflectasm/1.11.9/reflectasm-1.11.9.jar:/home/jovyan/.m2/repository/org/objenesis/objenesis/3.1/objenesis-3.1.jar:/home/jovyan/.m2/repository/com/esotericsoftware/minlog/1.3.1/minlog-1.3.1.jar:/home/jovyan/.m2/repository/org/openjdk/jmh/jmh-core/1.37-SNAPSHOT/jmh-core-1.37-SNAPSHOT.jar:/home/jovyan/.m2/repository/net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar:/home/jovyan/.m2/repository/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar:/home/jovyan/.m2/repository/org/openjdk/jmh/jmh-generator-annprocess/1.37-SNAPSHOT/jmh-generator-annprocess-1.37-SNAPSHOT.jar:/home/jovyan/.m2/repository/com/google/protobuf/protobuf-java/3.14.0/protobuf-java-3.14.0.jar:/home/jovyan/.m2/repository/io/zipkin/zipkin-server/2.24.0-SNAPSHOT/zipkin-server-2.24.0-SNAPSHOT.jar:/home/jovyan/.m2/repository/org/springframework/boot/spring-boot-starter/2.5.14/spring-boot-starter-2.5.14.jar:/home/jovyan/.m2/repository/org/springframework/boot/spring-boot/2.5.14/spring-boot-2.5.14.jar:/home/jovyan/.m2/repository/org/springframework/spring-context/5.3.20/spring-context-5.3.20.jar:/home/jovyan/.m2/repository/org/springframework/spring-aop/5.3.20/spring-aop-5.3.20.jar:/home/jovyan/.m2/repository/org/springframework/spring-beans/5.3.20/spring-beans-5.3.20.jar:/home/jovyan/.m2/repository/org/springframework/spring-expression/5.3.20/spring-expression-5.3.20.jar:/home/jovyan/.m2/repository/org/springframework/boot/spring-boot-autoconfigure/2.5.14/spring-boot-autoconfigure-2.5.14.jar:/home/jovyan/.m2/repository/jakarta/annotation/jakarta.annotation-api/1.3.5/jakarta.annotation-api-1.3.5.jar:/home/jovyan/.m2/repository/org/springframework/spring-core/5.3.20/spring-core-5.3.20.jar:/home/jovyan/.m2/repository/org/springframework/spring-jcl/5.3.20/spring-jcl-5.3.20.jar:/home/jovyan/.m2/repository/org/springframework/boot/spring-boot-starter-actuator/2.5.14/spring-boot-starter-actuator-2.5.14.jar:/home/jovyan/.m2/repository/org/springframework/boot/spring-boot-actuator-autoconfigure/2.5.14/spring-boot-actuator-autoconfigure-2.5.14.jar:/home/jovyan/.m2/repository/org/springframework/boot/spring-boot-actuator/2.5.14/spring-boot-actuator-2.5.14.jar:/home/jovyan/.m2/repository/com/fasterxml/jackson/datatype/jackson-datatype-jsr310/2.14.0/jackson-datatype-jsr310-2.14.0.jar:/home/jovyan/.m2/repository/org/yaml/snakeyaml/1.33/snakeyaml-1.33.jar:/home/jovyan/.m2/repository/org/apache/logging/log4j/log4j-api/2.17.1/log4j-api-2.17.1.jar:/home/jovyan/.m2/repository/org/apache/logging/log4j/log4j-core/2.17.1/log4j-core-2.17.1.jar:/home/jovyan/.m2/repository/org/apache/logging/log4j/log4j-slf4j-impl/2.17.1/log4j-slf4j-impl-2.17.1.jar:/home/jovyan/.m2/repository/org/apache/logging/log4j/log4j-jul/2.17.1/log4j-jul-2.17.1.jar:/home/jovyan/.m2/repository/org/springframework/boot/spring-boot-starter-log4j2/2.5.14/spring-boot-starter-log4j2-2.5.14.jar:/home/jovyan/.m2/repository/com/linecorp/armeria/armeria-spring-boot2-autoconfigure/1.17.2/armeria-spring-boot2-autoconfigure-1.17.2.jar:/home/jovyan/.m2/repository/javax/inject/javax.inject/1/javax.inject-1.jar:/home/jovyan/.m2/repository/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar:/home/jovyan/.m2/repository/com/linecorp/armeria/armeria-brave/1.17.2/armeria-brave-1.17.2.jar:/home/jovyan/.m2/repository/io/zipkin/brave/brave/5.13.9/brave-5.13.9.jar:/home/jovyan/.m2/repository/io/zipkin/reporter2/zipkin-reporter-brave/2.16.3/zipkin-reporter-brave-2.16.3.jar:/home/jovyan/.m2/repository/io/zipkin/reporter2/zipkin-reporter/2.16.3/zipkin-reporter-2.16.3.jar:/home/jovyan/.m2/repository/io/zipkin/brave/brave-instrumentation-http/5.13.9/brave-instrumentation-http-5.13.9.jar:/home/jovyan/.m2/repository/com/linecorp/armeria/armeria-grpc-protocol/1.17.2/armeria-grpc-protocol-1.17.2.jar:/home/jovyan/.m2/repository/io/micrometer/micrometer-registry-prometheus/1.9.3/micrometer-registry-prometheus-1.9.3.jar:/home/jovyan/.m2/repository/io/prometheus/simpleclient_common/0.15.0/simpleclient_common-0.15.0.jar:/home/jovyan/.m2/repository/io/prometheus/simpleclient/0.15.0/simpleclient-0.15.0.jar:/home/jovyan/.m2/repository/io/prometheus/simpleclient_tracer_otel/0.15.0/simpleclient_tracer_otel-0.15.0.jar:/home/jovyan/.m2/repository/io/prometheus/simpleclient_tracer_common/0.15.0/simpleclient_tracer_common-0.15.0.jar:/home/jovyan/.m2/repository/io/prometheus/simpleclient_tracer_otel_agent/0.15.0/simpleclient_tracer_otel_agent-0.15.0.jar:/home/jovyan/.m2/repository/com/netflix/concurrency-limits/concurrency-limits-core/0.3.6/concurrency-limits-core-0.3.6.jar:/home/jovyan/.m2/repository/io/micrometer/micrometer-core/1.9.3/micrometer-core-1.9.3.jar:/home/jovyan/.m2/repository/org/latencyutils/LatencyUtils/2.0.3/LatencyUtils-2.0.3.jar:/home/jovyan/.m2/repository/io/zipkin/zipkin2/zipkin/2.24.0-SNAPSHOT/zipkin-2.24.0-SNAPSHOT.jar:/home/jovyan/.m2/repository/io/zipkin/zipkin2/zipkin-collector/2.24.0-SNAPSHOT/zipkin-collector-2.24.0-SNAPSHOT.jar:/home/jovyan/.m2/repository/org/slf4j/slf4j-api/1.7.30/slf4j-api-1.7.30.jar:/home/jovyan/.m2/repository/org/slf4j/jul-to-slf4j/1.7.30/jul-to-slf4j-1.7.30.jar:/home/jovyan/.m2/repository/io/zipkin/zipkin-lens/2.24.0-SNAPSHOT/zipkin-lens-2.24.0-SNAPSHOT.jar:/home/jovyan/.m2/repository/io/zipkin/zipkin2/zipkin-storage-elasticsearch/2.24.0-SNAPSHOT/zipkin-storage-elasticsearch-2.24.0-SNAPSHOT.jar:/home/jovyan/.m2/repository/com/linecorp/armeria/armeria/1.17.2/armeria-1.17.2.jar:/home/jovyan/.m2/repository/com/fasterxml/jackson/core/jackson-annotations/2.14.0/jackson-annotations-2.14.0.jar:/home/jovyan/.m2/repository/io/netty/netty-transport/4.1.78.Final/netty-transport-4.1.78.Final.jar:/home/jovyan/.m2/repository/io/netty/netty-common/4.1.78.Final/netty-common-4.1.78.Final.jar:/home/jovyan/.m2/repository/io/netty/netty-buffer/4.1.78.Final/netty-buffer-4.1.78.Final.jar:/home/jovyan/.m2/repository/io/netty/netty-resolver/4.1.78.Final/netty-resolver-4.1.78.Final.jar:/home/jovyan/.m2/repository/io/netty/netty-codec-http2/4.1.78.Final/netty-codec-http2-4.1.78.Final.jar:/home/jovyan/.m2/repository/io/netty/netty-codec/4.1.78.Final/netty-codec-4.1.78.Final.jar:/home/jovyan/.m2/repository/io/netty/netty-handler/4.1.78.Final/netty-handler-4.1.78.Final.jar:/home/jovyan/.m2/repository/io/netty/netty-codec-http/4.1.78.Final/netty-codec-http-4.1.78.Final.jar:/home/jovyan/.m2/repository/io/netty/netty-codec-haproxy/4.1.78.Final/netty-codec-haproxy-4.1.78.Final.jar:/home/jovyan/.m2/repository/io/netty/netty-resolver-dns/4.1.78.Final/netty-resolver-dns-4.1.78.Final.jar:/home/jovyan/.m2/repository/io/netty/netty-codec-dns/4.1.78.Final/netty-codec-dns-4.1.78.Final.jar:/home/jovyan/.m2/repository/io/netty/netty-resolver-dns-native-macos/4.1.78.Final/netty-resolver-dns-native-macos-4.1.78.Final-osx-x86_64.jar:/home/jovyan/.m2/repository/io/netty/netty-resolver-dns-classes-macos/4.1.78.Final/netty-resolver-dns-classes-macos-4.1.78.Final.jar:/home/jovyan/.m2/repository/io/netty/netty-resolver-dns-native-macos/4.1.78.Final/netty-resolver-dns-native-macos-4.1.78.Final-osx-aarch_64.jar:/home/jovyan/.m2/repository/io/netty/netty-transport-native-unix-common/4.1.78.Final/netty-transport-native-unix-common-4.1.78.Final-linux-x86_64.jar:/home/jovyan/.m2/repository/io/netty/netty-transport-native-epoll/4.1.78.Final/netty-transport-native-epoll-4.1.78.Final-linux-x86_64.jar:/home/jovyan/.m2/repository/io/netty/netty-transport-native-unix-common/4.1.78.Final/netty-transport-native-unix-common-4.1.78.Final.jar:/home/jovyan/.m2/repository/io/netty/netty-transport-classes-epoll/4.1.78.Final/netty-transport-classes-epoll-4.1.78.Final.jar:/home/jovyan/.m2/repository/io/netty/netty-tcnative-boringssl-static/2.0.53.Final/netty-tcnative-boringssl-static-2.0.53.Final-linux-x86_64.jar:/home/jovyan/.m2/repository/io/netty/netty-tcnative-classes/2.0.53.Final/netty-tcnative-classes-2.0.53.Final.jar:/home/jovyan/.m2/repository/io/netty/netty-tcnative-boringssl-static/2.0.53.Final/netty-tcnative-boringssl-static-2.0.53.Final-linux-aarch_64.jar:/home/jovyan/.m2/repository/io/netty/netty-tcnative-boringssl-static/2.0.53.Final/netty-tcnative-boringssl-static-2.0.53.Final-osx-x86_64.jar:/home/jovyan/.m2/repository/io/netty/netty-tcnative-boringssl-static/2.0.53.Final/netty-tcnative-boringssl-static-2.0.53.Final-osx-aarch_64.jar:/home/jovyan/.m2/repository/io/netty/netty-tcnative-boringssl-static/2.0.53.Final/netty-tcnative-boringssl-static-2.0.53.Final-windows-x86_64.jar:/home/jovyan/.m2/repository/io/netty/netty-handler-proxy/4.1.78.Final/netty-handler-proxy-4.1.78.Final.jar:/home/jovyan/.m2/repository/io/netty/netty-codec-socks/4.1.78.Final/netty-codec-socks-4.1.78.Final.jar:/home/jovyan/.m2/repository/com/aayushatharva/brotli4j/brotli4j/1.7.1/brotli4j-1.7.1.jar:/home/jovyan/.m2/repository/com/aayushatharva/brotli4j/native-linux-x86_64/1.7.1/native-linux-x86_64-1.7.1.jar:/home/jovyan/.m2/repository/com/google/auto/value/auto-value-annotations/1.7.4/auto-value-annotations-1.7.4.jar:/home/jovyan/.m2/repository/com/squareup/wire/wire-runtime/3.0.2/wire-runtime-3.0.2.jar:/home/jovyan/.m2/repository/org/jetbrains/kotlin/kotlin-stdlib/1.3.50/kotlin-stdlib-1.3.50.jar:/home/jovyan/.m2/repository/org/jetbrains/annotations/13.0/annotations-13.0.jar:/home/jovyan/.m2/repository/org/jetbrains/kotlin/kotlin-stdlib-common/1.3.50/kotlin-stdlib-common-1.3.50.jar:/home/jovyan/.m2/repository/com/squareup/okio/okio/2.4.1/okio-2.4.1.jar:/home/jovyan/.m2/repository/io/zipkin/proto3/zipkin-proto3/1.0.0/zipkin-proto3-1.0.0.jar:/home/jovyan/.m2/repository/io/zipkin/zipkin2/zipkin-tests/2.24.0-SNAPSHOT/zipkin-tests-2.24.0-SNAPSHOT.jar:/home/jovyan/.m2/repository/io/zipkin/zipkin2/zipkin-storage-cassandra/2.24.0-SNAPSHOT/zipkin-storage-cassandra-2.24.0-SNAPSHOT.jar:/home/jovyan/.m2/repository/com/datastax/oss/java-driver-core/4.11.3/java-driver-core-4.11.3.jar:/home/jovyan/.m2/repository/com/datastax/oss/native-protocol/1.5.0/native-protocol-1.5.0.jar:/home/jovyan/.m2/repository/com/datastax/oss/java-driver-shaded-guava/25.1-jre-graal-sub-1/java-driver-shaded-guava-25.1-jre-graal-sub-1.jar:/home/jovyan/.m2/repository/com/typesafe/config/1.4.1/config-1.4.1.jar:/home/jovyan/.m2/repository/com/github/jnr/jnr-posix/3.1.5/jnr-posix-3.1.5.jar:/home/jovyan/.m2/repository/com/github/jnr/jnr-ffi/2.2.2/jnr-ffi-2.2.2.jar:/home/jovyan/.m2/repository/com/github/jnr/jffi/1.3.1/jffi-1.3.1.jar:/home/jovyan/.m2/repository/com/github/jnr/jffi/1.3.1/jffi-1.3.1-native.jar:/home/jovyan/.m2/repository/org/ow2/asm/asm/9.1/asm-9.1.jar:/home/jovyan/.m2/repository/org/ow2/asm/asm-commons/9.1/asm-commons-9.1.jar:/home/jovyan/.m2/repository/org/ow2/asm/asm-analysis/9.1/asm-analysis-9.1.jar:/home/jovyan/.m2/repository/org/ow2/asm/asm-tree/9.1/asm-tree-9.1.jar:/home/jovyan/.m2/repository/org/ow2/asm/asm-util/9.1/asm-util-9.1.jar:/home/jovyan/.m2/repository/com/github/jnr/jnr-a64asm/1.0.0/jnr-a64asm-1.0.0.jar:/home/jovyan/.m2/repository/com/github/jnr/jnr-x86asm/1.0.2/jnr-x86asm-1.0.2.jar:/home/jovyan/.m2/repository/com/github/jnr/jnr-constants/0.10.1/jnr-constants-0.10.1.jar:/home/jovyan/.m2/repository/io/dropwizard/metrics/metrics-core/4.1.18/metrics-core-4.1.18.jar:/home/jovyan/.m2/repository/org/hdrhistogram/HdrHistogram/2.1.12/HdrHistogram-2.1.12.jar:/home/jovyan/.m2/repository/com/esri/geometry/esri-geometry-api/1.2.1/esri-geometry-api-1.2.1.jar:/home/jovyan/.m2/repository/org/json/json/20090211/json-20090211.jar:/home/jovyan/.m2/repository/org/codehaus/jackson/jackson-core-asl/1.9.12/jackson-core-asl-1.9.12.jar:/home/jovyan/.m2/repository/com/fasterxml/jackson/core/jackson-core/2.14.0/jackson-core-2.14.0.jar:/home/jovyan/.m2/repository/com/fasterxml/jackson/core/jackson-databind/2.14.0/jackson-databind-2.14.0.jar:/home/jovyan/.m2/repository/org/reactivestreams/reactive-streams/1.0.3/reactive-streams-1.0.3.jar:/home/jovyan/.m2/repository/com/github/stephenc/jcip/jcip-annotations/1.0-1/jcip-annotations-1.0-1.jar:/home/jovyan/.m2/repository/com/github/spotbugs/spotbugs-annotations/3.1.12/spotbugs-annotations-3.1.12.jar:/home/jovyan/.m2/repository/io/zipkin/zipkin2/zipkin-storage-mysql-v1/2.24.0-SNAPSHOT/zipkin-storage-mysql-v1-2.24.0-SNAPSHOT.jar:/home/jovyan/.m2/repository/org/jooq/jooq/3.14.4/jooq-3.14.4.jar:/home/jovyan/.m2/repository/javax/xml/bind/jaxb-api/2.3.1/jaxb-api-2.3.1.jar:/home/jovyan/.m2/repository/javax/activation/javax.activation-api/1.2.0/javax.activation-api-1.2.0.jar:/home/jovyan/.m2/repository/org/mariadb/jdbc/mariadb-java-client/2.7.1/mariadb-java-client-2.7.1.jar:/home/jovyan/.m2/repository/com/zaxxer/HikariCP/3.4.5/HikariCP-3.4.5.jar:/home/jovyan/.m2/repository/org/testcontainers/testcontainers/1.15.1/testcontainers-1.15.1.jar:/home/jovyan/.m2/repository/org/apache/commons/commons-compress/1.20/commons-compress-1.20.jar:/home/jovyan/.m2/repository/org/rnorth/duct-tape/duct-tape/1.0.8/duct-tape-1.0.8.jar:/home/jovyan/.m2/repository/org/rnorth/visible-assertions/visible-assertions/2.1.2/visible-assertions-2.1.2.jar:/home/jovyan/.m2/repository/net/java/dev/jna/jna/5.2.0/jna-5.2.0.jar:/home/jovyan/.m2/repository/com/github/docker-java/docker-java-api/3.2.7/docker-java-api-3.2.7.jar:/home/jovyan/.m2/repository/com/github/docker-java/docker-java-transport-zerodep/3.2.7/docker-java-transport-zerodep-3.2.7.jar:/home/jovyan/.m2/repository/com/github/docker-java/docker-java-transport/3.2.7/docker-java-transport-3.2.7.jar:/home/jovyan/.m2/repository/junit/junit/4.13.1/junit-4.13.1.jar:/home/jovyan/.m2/repository/org/junit/jupiter/junit-jupiter/5.7.0/junit-jupiter-5.7.0.jar:/home/jovyan/.m2/repository/org/junit/jupiter/junit-jupiter-params/5.7.0/junit-jupiter-params-5.7.0.jar:/home/jovyan/.m2/repository/org/junit/vintage/junit-vintage-engine/5.7.0/junit-vintage-engine-5.7.0.jar:/home/jovyan/.m2/repository/org/apiguardian/apiguardian-api/1.1.0/apiguardian-api-1.1.0.jar:/home/jovyan/.m2/repository/org/junit/platform/junit-platform-engine/1.7.0/junit-platform-engine-1.7.0.jar:/home/jovyan/.m2/repository/org/junit/jupiter/junit-jupiter-api/5.7.0/junit-jupiter-api-5.7.0.jar:/home/jovyan/.m2/repository/org/opentest4j/opentest4j/1.2.0/opentest4j-1.2.0.jar:/home/jovyan/.m2/repository/org/junit/platform/junit-platform-commons/1.7.0/junit-platform-commons-1.7.0.jar:/home/jovyan/.m2/repository/org/junit/jupiter/junit-jupiter-engine/5.7.0/junit-jupiter-engine-5.7.0.jar:/home/jovyan/.m2/repository/org/assertj/assertj-core/3.18.1/assertj-core-3.18.1.jar:/home/jovyan/.m2/repository/org/mockito/mockito-core/3.6.28/mockito-core-3.6.28.jar:/home/jovyan/.m2/repository/net/bytebuddy/byte-buddy/1.10.18/byte-buddy-1.10.18.jar:/home/jovyan/.m2/repository/net/bytebuddy/byte-buddy-agent/1.10.18/byte-buddy-agent-1.10.18.jar:/home/jovyan/.m2/repository/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar:/usr/lib/jvm/java-1.8.0-openjdk-amd64/jre/lib/rt.jar";

        // launcher.getEnvironment().setComplianceLevel(getComplianceLevel());
        // launcher.getEnvironment().setComplianceLevel(8);
        // Path jmods = Paths.get("/usr/lib/jvm/java-11-openjdk-amd64/jmods");
        // Path jmods = Paths.get("/usr/lib/jvm/java-1.8.0-openjdk-amd64/")
        // List<String> cp = new ArrayList<>();
        // try {
        //     DirectoryStream<Path> ds = Files.newDirectoryStream(jmods, "*.jmod");
        //     for (Path p : ds) {
        //         cp.add(p.toString());
        //     }
        // } catch (Exception ex) {
        // }
        // String cp2 = String.join(":", cp);
        // System.out.println("cp2: " + cp2);
        // String cp3 = cp_str + ":" + cp2;
        String cp3 = cp_str ;
        launcher.getEnvironment().setNoClasspath(false);
        launcher.getEnvironment().setAutoImports(true);
        launcher.getEnvironment().setSourceClasspath(cp3.split(":"));
        System.out.println("Dectors: " + configuration.getDetectors());
        int fragmentsInserted = 0;
        for (Detector d : configuration.getDetectors()) launcher.addProcessor(d);

        //Try to compile using the classpath first
        try {
            launcher.buildModel();
            launcher.process();
        } catch (Exception ex) {
            failedDirs.add(src.get(0));
            logger.warn("Error: " + ex.getMessage());
            throw  ex;
        }

        for (Detector d : configuration.getDetectors())
            fragmentsInserted += d.getElementsDetectedCount();

        //If no new fragments where inserted no need for printing
        if (fragmentsInserted > 0) {
            launcher.prettyprint();
            logger.info("Elements detected: " + fragmentsInserted);
        } else {
            logger.info("No fragments. Sources unmodified");
        }

        for (Detector d : configuration.getDetectors()) d.reset();
    }

    /**
     * Walks file by file in in a given sources, injecting them
     */
    private void walk(final Configuration configuration, ArrayList<String> src,
                      final HashSet<String> failed) throws IOException {
        for (String s : src)
            Files.walkFileTree(Paths.get(s), new FileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    try {
                        String filePath = file.toAbsolutePath().toString();
                        if (failed == null || failed.contains(filePath))
                            launchInjection(configuration, Arrays.asList(filePath));
                    } catch (Exception ex) {
                        logger.warn("Error: " + ex.getMessage() + " at " + file.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.TERMINATE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });
    }

    /**
     * Writes the Id map to file
     */
    @Override
    public void writeIdFile(String idName) throws IOException {
        File file = new File(getOutputDir() + "/log");
        file.mkdirs();
        FileWriter fw = new FileWriter(getOutputDir() + "/log/" + idName);
        for (String s : idMap.keySet()) fw.write(idMap.get(s) + " " + s + "\n");
        fw.close();
    }

    /**
     * Return all names of files containing a java class
     *
     * @param dir Dir to find for java files
     * @return A list of java files
     */
    /*
    protected List<String> allClassesName(File dir) {
        List<String> list = new ArrayList<>();

        for (File file : dir.listFiles())
            if (file.isDirectory())
                list.addAll(allClassesName(file));
            else {
                String name = file.getName();
                if (name.endsWith(".java")) {
                    String[] tmp = name.substring(0, name.length() - 5).split("/");
                    list.add(tmp[tmp.length - 1]);
                }
            }
        return list;
    }*/

    /**
     * Applies an spoon processor
     *
     * @param factory   Factory to apply the processor
     * @param processor Abstract processor

    protected void applyProcessor(Factory factory, Processor processor) {
    ProcessingManager pm = new QueueProcessingManager(factory);
    pm.addProcessor(processor);
    pm.process(processor);
    }*/

    /**
     * Indicates that we may initialize the output dir
     */
    @Override
    public void clean() {
        outPutDirReady = false;
    }

    public void runTests(boolean verbose, String[] phases) throws Exception {
        MavenBuilder rb = new MavenBuilder(getOutputDir(), getProductionDir());
        rb.setTimeOut(buildTimeOut);
        rb.setVerbose(verbose);
        rb.setPhase(phases);
        rb.setTimeOut(0);
        System.out.println("pom file: " + getOutputDir() + "/pom.xml");
        // rb.initPom(getOutputDir() + "/pom.xml");
        // rb.runBuilder();

        if (rb.getStatus() == -2) {
            throw new RuntimeException("The instrumented source failed to compile");
        }

        if (rb.getStatus() == -1) {
            throw new RuntimeException("The instrumented source failed to pass all tests");
        }
    }

    @Override
    public void runTests(boolean verbose) throws Exception {
        runTests(verbose, new String[]{"clean", "test"});
    }

    /**
     * Sets the parent directory of the project being instrumented
     *
     * @param projectDir
     */
    @Override
    public void setProjectDir(String projectDir) {
        this.projectDir = projectDir;
    }

    @Override
    public String getProjectDir() {
        return projectDir;
    }

    /**
     * Sets the parent directory where the instrumented code is going to be stored
     *
     * @param outputDir
     */
    @Override
    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    @Override
    public String getOutputDir() {
        return outputDir;
    }


    @Override
    public String getProductionDir() {
        return productionDir;
    }

    @Override
    public void setProductionDir(String productionDir) {
        this.productionDir = productionDir;
    }

    @Override
    @Deprecated
    public boolean isOnlyCopyLogger() {
        return false;
    }

    @Override
    @Deprecated
    public void setOnlyCopyLogger(boolean onlyCopyLogger) {
        //this.onlyCopyLogger = onlyCopyLogger;
    }

    @Override
    public void setBuildTimeOut(int seconds) {
        this.buildTimeOut = seconds;
    }

    @Override
    public void writeLoggerProperties(String fileName, Properties properties) throws IOException {
        loggerPropertiesFile = fileName;
        loggerProperties = properties;
        //finally copy the property file of the logger if any
        if (loggerPropertiesFile != null && !loggerPropertiesFile.isEmpty()) {
            File logFile = new File(getOutputDir() + "/log/");
            if (!logFile.exists()) logFile.mkdir();
            loggerProperties.store(new BufferedWriter(
                    new FileWriter(logFile.getAbsolutePath() + "/" + loggerPropertiesFile)), "");
        }
    }

    @Override
    public void setUseClassPath(boolean use) {
        useClasspath = use;
    }

    public int getComplianceLevel() {
        return complianceLevel;
    }

    public void setComplianceLevel(int complianceLevel) {
        this.complianceLevel = complianceLevel;
    }

    /**
     * Compiles, detect and launchInjection file by file in a project. Much more slower, use only when injecting the whole
     * project don't works
     */
    public boolean isFileByFile() {
        return fileByFile;
    }

    public void setFileByFile(boolean fileByFile) {
        this.fileByFile = fileByFile;
    }

    /**
     * Manual classpath that the maven resolver cannot resolve because is so stupid.
     */
    public List<String> getManualClassPath() {
        return classPath;
    }

    public void setManualClassPath(List<String> classPath) {
        this.classPath = classPath;
    }

    /**
     * Path to the pom file. Can be null, in that case, it will be equal to "projectDir/pom.xml"
     */
    public String getPomPath() {
        return pomPath == null ? getProductionDir() + "/pom.xml" : pomPath;
    }

    public void setPomPath(String pomPath) {
        this.pomPath = pomPath;
    }
}
