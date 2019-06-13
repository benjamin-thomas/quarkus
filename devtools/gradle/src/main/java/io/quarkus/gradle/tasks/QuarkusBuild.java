package io.quarkus.gradle.tasks;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.gradle.api.GradleException;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

import io.quarkus.bootstrap.model.AppArtifact;
import io.quarkus.bootstrap.model.AppModel;
import io.quarkus.bootstrap.resolver.AppModelResolver;
import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.creator.AppCreator;
import io.quarkus.creator.AppCreatorException;
import io.quarkus.creator.phase.augment.AugmentPhase;
import io.quarkus.creator.phase.curate.CurateOutcome;
import io.quarkus.creator.phase.runnerjar.RunnerJarOutcome;
import io.quarkus.creator.phase.runnerjar.RunnerJarPhase;

/**
 * @author <a href="mailto:stalep@gmail.com">Ståle Pedersen</a>
 */
public class QuarkusBuild extends QuarkusTask {

    private String transformedClassesDirectory;

    private String wiringClassesDirectory;

    private String libDir;

    private String mainClass = "io.quarkus.runner.GeneratedMain";

    private boolean useStaticInit = true;

    private boolean uberJar = false;

    private List<String> ignoredEntries = new ArrayList<>();

    public QuarkusBuild() {
        super("Quarkus builds a runner jar based on the build jar");
    }

    public File getTransformedClassesDirectory() {
        if (transformedClassesDirectory == null)
            return extension().transformedClassesDirectory();
        else
            return new File(transformedClassesDirectory);
    }

    @Option(description = "The directory for application classes transformed by processing.", option = "transformed-classes-directory")
    public void setTransformedClassesDirectory(String transformedClassesDirectory) {
        this.transformedClassesDirectory = transformedClassesDirectory;
    }

    @Optional
    @Input
    public File getWiringClassesDirectory() {
        if (wiringClassesDirectory == null)
            return extension().wiringClassesDirectory();
        else
            return new File(wiringClassesDirectory);
    }

    @Option(description = "The directory for classes generated by processing", option = "wiring-classes-directory")
    public void setWiringClassesDirectory(String wiringClassesDirectory) {
        this.wiringClassesDirectory = wiringClassesDirectory;
    }

    @Optional
    @Input
    public File getLibDir() {
        if (libDir == null)
            return extension().libDir();
        else
            return new File(libDir);
    }

    @Option(description = "The directory for library jars", option = "lib-dir")
    public void setLibDir(String libDir) {
        this.libDir = libDir;
    }

    @Input
    @Optional
    public String getMainClass() {
        return mainClass;
    }

    @Option(description = "Name of the main class generated by the quarkus build process", option = "main-class")
    public void setMainClass(String mainClass) {
        this.mainClass = mainClass;
    }

    @Optional
    @Input
    public boolean isUseStaticInit() {
        return useStaticInit;
    }

    @Option(description = "", option = "use-static-init")
    public void setUseStaticInit(boolean useStaticInit) {
        this.useStaticInit = useStaticInit;
    }

    @Optional
    @Input
    public boolean isUberJar() {
        return uberJar;
    }

    @Option(description = "Set to true if the build task should build an uberjar", option = "uber-jar")
    public void setUberJar(boolean uberJar) {
        this.uberJar = uberJar;
    }

    @Optional
    @Input
    public List<String> getIgnoredEntries() {
        return ignoredEntries;
    }

    @Option(description = "When using the uber-jar option, this option can be used to "
            + "specify one or more entries that should be excluded from the final jar", option = "ignored-entry")
    public void setIgnoredEntries(List<String> ignoredEntries) {
        this.ignoredEntries.addAll(ignoredEntries);
    }

    @TaskAction
    public void buildQuarkus() {
        getLogger().lifecycle("building quarkus runner");

        final AppArtifact appArtifact = extension().getAppArtifact();
        final AppModel appModel;
        final AppModelResolver modelResolver = extension().resolveAppModel();
        try {
            appModel = modelResolver.resolveModel(appArtifact);
        } catch (AppModelResolverException e) {
            throw new GradleException("Failed to resolve application model " + appArtifact + " dependencies", e);
        }

        try (AppCreator appCreator = AppCreator.builder()
                // configure the build phases we want the app to go through
                .addPhase(new AugmentPhase()
                        .setAppClassesDir(extension().outputDirectory().toPath())
                        .setConfigDir(extension().outputConfigDirectory().toPath())
                        .setTransformedClassesDir(getTransformedClassesDirectory().toPath())
                        .setWiringClassesDir(getWiringClassesDirectory().toPath()))
                .addPhase(new RunnerJarPhase()
                        .setLibDir(getLibDir().toPath())
                        .setFinalName(extension().finalName())
                        .setMainClass(getMainClass())
                        .setUberJar(isUberJar())
                        .setUserConfiguredIgnoredEntries(getIgnoredEntries()))
                .setWorkDir(getProject().getBuildDir().toPath())
                .build()) {

            // push resolved application state
            appCreator.pushOutcome(CurateOutcome.builder()
                    .setAppModelResolver(modelResolver)
                    .setAppModel(appModel)
                    .build());

            // resolve the outcome we need here
            appCreator.resolveOutcome(RunnerJarOutcome.class);

        } catch (AppCreatorException e) {
            throw new GradleException("Failed to build a runnable JAR", e);
        }
    }
}
