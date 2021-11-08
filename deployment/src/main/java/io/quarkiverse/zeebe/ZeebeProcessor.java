package io.quarkiverse.zeebe;

import static io.quarkus.deployment.annotations.ExecutionTime.RUNTIME_INIT;
import static io.quarkus.deployment.annotations.ExecutionTime.STATIC_INIT;
import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.enterprise.context.ApplicationScoped;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkiverse.zeebe.runtime.ZeebeBuildTimeConfig;
import io.quarkiverse.zeebe.runtime.ZeebeClientService;
import io.quarkiverse.zeebe.runtime.ZeebeRecorder;
import io.quarkiverse.zeebe.runtime.ZeebeRuntimeConfig;
import io.quarkiverse.zeebe.runtime.ZeebeWorkerContainer;
import io.quarkiverse.zeebe.runtime.ZeebeWorkerValue;
import io.quarkiverse.zeebe.runtime.health.ZeebeHealthCheck;
import io.quarkiverse.zeebe.runtime.health.ZeebeTopologyHealthCheck;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanArchiveIndexBuildItem;
import io.quarkus.arc.deployment.BeanDefiningAnnotationBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.AdditionalIndexedClassesBuildItem;
import io.quarkus.deployment.builditem.ExtensionSslNativeSupportBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageConfigBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.smallrye.health.deployment.spi.HealthBuildItem;

public class ZeebeProcessor {

    static final String FEATURE_NAME = "zeebe";

    ZeebeBuildTimeConfig buildConfig;

    private static final Logger log = LoggerFactory.getLogger(ZeebeProcessor.class);

    private static final String JAR_RESOURCE_PROTOCOL = "jar";
    private static final String FILE_RESOURCE_PROTOCOL = "file";

    static final DotName WORKER_ANNOTATION = DotName.createSimple(ZeebeWorker.class.getName());
    static final DotName WORKER_ANNOTATION_SCOPE = DotName.createSimple(ApplicationScoped.class.getName());

    static final DotName CLIENT_INTERCEPTOR_ANNOTATION = DotName.createSimple(ZeebeInterceptor.class.getName());

    @BuildStep
    void addIndex(BuildProducer<AdditionalIndexedClassesBuildItem> add) {
        add.produce(new AdditionalIndexedClassesBuildItem(ZeebeWorker.class.getName()));
        add.produce(new AdditionalIndexedClassesBuildItem(ZeebeWorker.ExponentialBackoff.class.getName()));
    }

    @BuildStep
    void collectJobHandlers(BuildProducer<ZeebeWorkersBuildItem> zeebeWorkers, BeanArchiveIndexBuildItem index) {
        IndexView view = index.getIndex();
        List<ZeebeWorkerValue> workers = view
                .getAnnotations(WORKER_ANNOTATION)
                .stream()
                .map(a -> createValue(view, a))
                .collect(toList());
        zeebeWorkers.produce(new ZeebeWorkersBuildItem(workers));
    }

    @BuildStep
    void build(
            BuildProducer<BeanDefiningAnnotationBuildItem> b,
            BuildProducer<ReflectiveClassBuildItem> reflective,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<NativeImageResourceBuildItem> resource,
            BuildProducer<FeatureBuildItem> feature,
            BuildProducer<ExtensionSslNativeSupportBuildItem> ssl,
            BuildProducer<ZeebeResourcesBuildItem> zeebeResources) throws Exception {

        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(ZeebeWorkerContainer.class));
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(ZeebeClientService.class));
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(ZeebeObjectMapperProducer.class));

        resource.produce(new NativeImageResourceBuildItem("client-java.properties"));

        reflective.produce(new ReflectiveClassBuildItem(true, true, true,
                "io.camunda.zeebe.client.impl.response.CreateProcessInstanceResponseImpl",
                "io.camunda.zeebe.client.impl.response.ActivatedJobImpl",
                "io.camunda.zeebe.client.impl.response.ActivateJobsResponseImpl",
                "io.camunda.zeebe.client.impl.response.BrokerInfoImpl",
                "io.camunda.zeebe.client.impl.response.CreateProcessInstanceResponseImpl",
                "io.camunda.zeebe.client.impl.response.CreateProcessInstanceWithResultResponseImpl",
                "io.camunda.zeebe.client.impl.response.DeploymentEventImpl",
                "io.camunda.zeebe.client.impl.response.PartitionInfoImpl",
                "io.camunda.zeebe.client.impl.response.ProcessImpl",
                "io.camunda.zeebe.client.impl.response.PublishMessageResponseImpl",
                "io.camunda.zeebe.client.impl.response.SetVariablesResponseImpl",
                "io.camunda.zeebe.client.impl.response.TopologyImpl"));

        b.produce(new BeanDefiningAnnotationBuildItem(WORKER_ANNOTATION, WORKER_ANNOTATION_SCOPE));
        b.produce(new BeanDefiningAnnotationBuildItem(CLIENT_INTERCEPTOR_ANNOTATION, WORKER_ANNOTATION_SCOPE));

        Collection<String> resources = discoverResources(buildConfig.resources.location);
        if (!resources.isEmpty()) {
            resource.produce(new NativeImageResourceBuildItem(resources.toArray(new String[0])));
        }
        zeebeResources.produce(new ZeebeResourcesBuildItem(resources));

        ssl.produce(new ExtensionSslNativeSupportBuildItem(FEATURE_NAME));
        feature.produce(new FeatureBuildItem(FEATURE_NAME));
    }

    @BuildStep
    void addHealthCheck(ZeebeBuildTimeConfig config, BuildProducer<HealthBuildItem> healthChecks) {
        healthChecks.produce(new HealthBuildItem(ZeebeHealthCheck.class.getName(), config.health.enabled));
        healthChecks.produce(new HealthBuildItem(ZeebeTopologyHealthCheck.class.getName(), config.health.enabled));
    }

    @BuildStep
    @Record(RUNTIME_INIT)
    void runtimeInitConfiguration(ZeebeRecorder recorder, ZeebeRuntimeConfig runtimeConfig) {
        recorder.init(runtimeConfig);
    }

    @BuildStep
    @Record(STATIC_INIT)
    void staticInitConfiguration(ZeebeRecorder recorder, ZeebeWorkersBuildItem zeebeWorkers,
            ZeebeResourcesBuildItem zeebeResources) {
        recorder.setResources(zeebeResources.getResources(), zeebeWorkers.getWorkers());
    }

    private static ZeebeWorkerValue createValue(IndexView index, AnnotationInstance ai) {
        ZeebeWorkerValue zwv = new ZeebeWorkerValue();
        zwv.clazz = ai.target().toString() + "_ClientProxy";
        zwv.type = ai.valueWithDefault(index, "type").asString();
        zwv.name = ai.valueWithDefault(index, "name").asString();
        zwv.timeout = ai.valueWithDefault(index, "timeout").asInt();
        zwv.maxJobsActive = ai.valueWithDefault(index, "maxJobsActive").asInt();
        zwv.requestTimeout = ai.valueWithDefault(index, "requestTimeout").asLong();
        zwv.pollInterval = ai.valueWithDefault(index, "pollInterval").asLong();
        zwv.fetchVariables = ai.valueWithDefault(index, "fetchVariables").asStringArray();
        AnnotationInstance eb = ai.valueWithDefault(index, "exponentialBackoff").asNested();
        zwv.expBackoffFactor = eb.valueWithDefault(index, "backoffFactor").asDouble();
        zwv.expJitterFactor = eb.valueWithDefault(index, "jitterFactor").asDouble();
        zwv.expMinDelay = eb.valueWithDefault(index, "minDelay").asLong();
        zwv.expMaxDelay = eb.valueWithDefault(index, "maxDelay").asLong();
        return zwv;
    }

    @BuildStep
    NativeImageConfigBuildItem build() {
        NativeImageConfigBuildItem.Builder builder = NativeImageConfigBuildItem.builder();
        builder.addRuntimeInitializedClass("io.netty.handler.ssl.OpenSsl");
        builder.addRuntimeInitializedClass("io.netty.internal.tcnative.SSL");
        builder.addRuntimeInitializedClass("io.camunda.zeebe.client.impl.worker.ExponentialBackoff");
        builder.addRuntimeInitializedClass("io.camunda.zeebe.client.impl.worker.ExponentialBackoffBuilderImpl");
        builder.addRuntimeInitializedClass("io.camunda.zeebe.client.impl.worker.JobWorkerImpl");
        builder.addRuntimeInitializedClass("io.camunda.zeebe.client.impl.worker.JobWorkerBuilderImpl");
        return builder.build();
    }

    private Collection<String> discoverResources(String location) throws IOException, URISyntaxException {
        LinkedHashSet<String> result = new LinkedHashSet<>();

        location = normalizeLocation(location);
        Enumeration<URL> migrations = Thread.currentThread().getContextClassLoader().getResources(location);
        while (migrations.hasMoreElements()) {
            URL path = migrations.nextElement();
            log.debug("Zeebe resource '{}' using protocol '{}'", path.getPath(), path.getProtocol());
            final Set<String> resources;
            if (JAR_RESOURCE_PROTOCOL.equals(path.getProtocol())) {
                try (final FileSystem fileSystem = initFileSystem(path.toURI())) {
                    resources = getApplicationMigrationsFromPath(location, path);
                }
            } else if (FILE_RESOURCE_PROTOCOL.equals(path.getProtocol())) {
                resources = getApplicationMigrationsFromPath(location, path);
            } else {
                log.warn(
                        "Unsupported URL protocol ''{}'' for path ''{}''. Resource files will not be discovered.",
                        path.getProtocol(), path.getPath());
                resources = null;
            }
            if (resources != null) {
                result.addAll(resources);
            }
        }

        return result;
    }

    private String normalizeLocation(String location) {
        if (location == null) {
            throw new IllegalStateException("Flyway migration location may not be null.");
        }
        if (!location.endsWith("/")) {
            location += "/";
        }
        return location;
    }

    private Set<String> getApplicationMigrationsFromPath(final String location, final URL path)
            throws IOException, URISyntaxException {
        Path rootPath = Paths.get(path.toURI());

        try (final Stream<Path> pathStream = Files.walk(rootPath)) {
            return pathStream.filter(Files::isRegularFile)
                    .map(it -> Paths.get(location, rootPath.relativize(it).toString()).normalize().toString())
                    // we don't want windows paths here since the paths are going to be used as classpath paths anyway
                    .map(it -> it.replace('\\', '/'))
                    .peek(it -> log.debug("Discovered path: {}", it))
                    .collect(Collectors.toSet());
        }
    }

    private FileSystem initFileSystem(final URI uri) throws IOException {
        final Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        return FileSystems.newFileSystem(uri, env);
    }
}