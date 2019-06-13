package io.quarkus.maven;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

import io.quarkus.bootstrap.model.AppArtifact;
import io.quarkus.bootstrap.model.AppModel;
import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.bootstrap.resolver.BootstrapAppModelResolver;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.creator.AppCreator;
import io.quarkus.creator.AppCreatorException;
import io.quarkus.creator.phase.augment.AugmentPhase;
import io.quarkus.creator.phase.curate.CurateOutcome;
import io.quarkus.creator.phase.runnerjar.RunnerJarOutcome;
import io.quarkus.creator.phase.runnerjar.RunnerJarPhase;

/**
 * Build the application.
 * <p>
 * You can build a native application runner with {@code native-image}
 *
 * @author Alexey Loubyansky
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class BuildMojo extends AbstractMojo {

    /**
     * The entry point to Aether, i.e. the component doing all the work.
     *
     * @component
     */
    @Component
    private RepositorySystem repoSystem;

    @Component
    private MavenProjectHelper projectHelper;

    /**
     * The current repository/network configuration of Maven.
     *
     * @parameter default-value="${repositorySystemSession}"
     * @readonly
     */
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    /**
     * The project's remote repositories to use for the resolution of artifacts and their dependencies.
     *
     * @parameter default-value="${project.remoteProjectRepositories}"
     * @readonly
     */
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> repos;

    /**
     * The project's remote repositories to use for the resolution of plugins and their dependencies.
     *
     * @parameter default-value="${project.remotePluginRepositories}"
     * @readonly
     */
    @Parameter(defaultValue = "${project.remotePluginRepositories}", readonly = true, required = true)
    private List<RemoteRepository> pluginRepos;

    /**
     * The directory for compiled classes.
     */
    @Parameter(readonly = true, required = true, defaultValue = "${project.build.outputDirectory}")
    private File outputDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    /**
     * The directory for application classes transformed by processing.
     */
    @Parameter(defaultValue = "${project.build.directory}/transformed-classes")
    private File transformedClassesDirectory;

    /**
     * The directory for classes generated by processing.
     */
    @Parameter(defaultValue = "${project.build.directory}/wiring-classes")
    private File wiringClassesDirectory;

    /**
     * The directory for generated source files.
     */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources")
    private File generatedSourcesDirectory;

    @Parameter(defaultValue = "${project.build.directory}")
    private File buildDir;

    /**
     * The directory for library jars
     */
    @Parameter(defaultValue = "${project.build.directory}/lib")
    private File libDir;

    @Parameter(defaultValue = "${project.build.finalName}")
    private String finalName;

    @Parameter(defaultValue = "io.quarkus.runner.GeneratedMain")
    private String mainClass;

    @Parameter(defaultValue = "true")
    private boolean useStaticInit;

    @Parameter(property = "uberJar", defaultValue = "false")
    private boolean uberJar;

    /**
     * When using the uberJar option, this array specifies entries that should
     * be excluded from the final jar. The entries are relative to the root of
     * the file. An example of this configuration could be:
     * <code><pre>
     * &#x3C;configuration&#x3E;
     *   &#x3C;uberJar&#x3E;true&#x3C;/uberJar&#x3E;
     *   &#x3C;ignoredEntries&#x3E;
     *     &#x3C;ignoredEntry&#x3E;META-INF/BC2048KE.SF&#x3C;/ignoredEntry&#x3E;
     *     &#x3C;ignoredEntry&#x3E;META-INF/BC2048KE.DSA&#x3C;/ignoredEntry&#x3E;
     *     &#x3C;ignoredEntry&#x3E;META-INF/BC1024KE.SF&#x3C;/ignoredEntry&#x3E;
     *     &#x3C;ignoredEntry&#x3E;META-INF/BC1024KE.DSA&#x3C;/ignoredEntry&#x3E;
     *   &#x3C;/ignoredEntries&#x3E;
     * &#x3C;/configuration&#x3E;
     * </pre></code>
     */
    @Parameter(property = "ignoredEntries")
    private String[] ignoredEntries;

    public BuildMojo() {
        MojoLogger.logSupplier = this::getLog;
    }

    @Override
    public void execute() throws MojoExecutionException {

        if (project.getPackaging().equals("pom")) {
            getLog().info("Type of the artifact is POM, skipping build goal");
            return;
        }

        final Artifact projectArtifact = project.getArtifact();
        final AppArtifact appArtifact = new AppArtifact(projectArtifact.getGroupId(), projectArtifact.getArtifactId(),
                projectArtifact.getClassifier(), projectArtifact.getArtifactHandler().getExtension(),
                projectArtifact.getVersion());
        final AppModel appModel;
        final BootstrapAppModelResolver modelResolver;
        try {
            modelResolver = new BootstrapAppModelResolver(
                    MavenArtifactResolver.builder()
                            .setRepositorySystem(repoSystem)
                            .setRepositorySystemSession(repoSession)
                            .setRemoteRepositories(repos)
                            .build());
            appModel = modelResolver.resolveModel(appArtifact);
        } catch (AppModelResolverException e) {
            throw new MojoExecutionException("Failed to resolve application model " + appArtifact + " dependencies", e);
        }
        try (AppCreator appCreator = AppCreator.builder()
                // configure the build phases we want the app to go through
                .addPhase(new AugmentPhase()
                        .setAppClassesDir(outputDirectory.toPath())
                        .setConfigDir(outputDirectory.toPath())
                        .setTransformedClassesDir(transformedClassesDirectory.toPath())
                        .setWiringClassesDir(wiringClassesDirectory.toPath())
                        .setGeneratedSourcesDir(generatedSourcesDirectory.toPath()))
                .addPhase(new RunnerJarPhase()
                        .setLibDir(libDir.toPath())
                        .setFinalName(finalName)
                        .setMainClass(mainClass)
                        .setUberJar(uberJar)
                        .setUserConfiguredIgnoredEntries(
                                this.ignoredEntries == null ? Collections.emptySet() : Arrays.asList(this.ignoredEntries)))
                .setWorkDir(buildDir.toPath())
                .build()) {

            // push resolved application state
            appCreator.pushOutcome(CurateOutcome.builder()
                    .setAppModelResolver(modelResolver)
                    .setAppModel(appModel)
                    .build());

            // resolve the outcome we need here
            RunnerJarOutcome result = appCreator.resolveOutcome(RunnerJarOutcome.class);
            Artifact original = project.getArtifact();
            original.setFile(result.getOriginalJar().toFile());
            if (uberJar) {
                projectHelper.attachArtifact(project, result.getRunnerJar().toFile(), "runner");
            }

        } catch (AppCreatorException e) {
            throw new MojoExecutionException("Failed to build a runnable JAR", e);
        }
    }
}
