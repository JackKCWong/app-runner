package com.danielflower.apprunner.runners;

import com.danielflower.apprunner.problems.ProjectCannotStartException;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationOutputHandler;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.danielflower.apprunner.FileSandbox.dirPath;
import static java.util.Arrays.asList;

public class MavenRunner implements AppRunner {
    private static final Logger log = LoggerFactory.getLogger(MavenRunner.class);
    public static final List<String> CLEAN_AND_PACKAGE = asList("clean", "package");
    private final File projectRoot;
    private final JavaHomeProvider javaHomeProvider;
    private ExecuteWatchdog watchDog;
    private final List<String> goals;

    public MavenRunner(File projectRoot, JavaHomeProvider javaHomeProvider, List<String> goals) {
        this.projectRoot = projectRoot;
        this.javaHomeProvider = javaHomeProvider;
        this.goals = goals;
    }

    public void start(InvocationOutputHandler buildLogHandler, InvocationOutputHandler consoleLogHandler, Map<String, String> envVarsForApp, Waiter startupWaiter) throws ProjectCannotStartException {
        File pomFile = new File(projectRoot, "pom.xml");

        InvocationRequest request = new DefaultInvocationRequest()
            .setPomFile(pomFile)
            .setOutputHandler(buildLogHandler)
            .setErrorHandler(buildLogHandler)
            .setGoals(goals)
            .setBaseDirectory(projectRoot);

        request = javaHomeProvider.mungeMavenInvocationRequest(request);

        log.info("Building maven project at " + dirPath(projectRoot));
        Invoker invoker = new DefaultInvoker();
        try {
            InvocationResult result = invoker.execute(request);
            if (result.getExitCode() != 0) {
                throw new ProjectCannotStartException("Build returned error", result.getExecutionException());
            }
        } catch (Exception e) {
            throw new ProjectCannotStartException("Error while building " + projectRoot.getAbsolutePath(), e);
        }
        log.info("Build successful. Going to start app.");

        Model model = loadPomModel(pomFile);
        String jarName = model.getArtifactId() + "-" + model.getVersion() + ".jar";

        File jar = new File(new File(projectRoot, "target"), jarName);
        if (!jar.isFile()) {
            throw new ProjectCannotStartException("Could not find the jar file at " + dirPath(jar));
        }

        CommandLine command = javaHomeProvider.javaCommandLine();
        command.addArgument("-jar").addArgument("target" + File.separator + jarName);

        watchDog = ProcessStarter.startDaemon(buildLogHandler, consoleLogHandler, envVarsForApp, command, projectRoot, startupWaiter);
    }

    public static Model loadPomModel(File pomFile) {
        Model model;
        try {
            MavenXpp3Reader reader = new MavenXpp3Reader();
            try (FileReader pomReader = new FileReader(pomFile)) {
                model = reader.read(pomReader);
            }
        } catch (Exception e) {
            throw new ProjectCannotStartException("Error while reading maven meta data", e);
        }
        return model;
    }

    public void shutdown() {
        if (watchDog != null) {
            watchDog.destroyProcess();
        }
    }

    public static class Factory implements AppRunner.Factory {

        private final JavaHomeProvider javaHomeProvider;

        public Factory(JavaHomeProvider javaHomeProvider) {
            this.javaHomeProvider = javaHomeProvider;
        }

        @Override
        public Optional<AppRunner> forProject(String appName, File projectRoot) {
            File pom = new File(projectRoot, "pom.xml");
            if (pom.isFile()) {
                AppRunner runner = new MavenRunner(projectRoot, javaHomeProvider, CLEAN_AND_PACKAGE);
                return Optional.of(runner);
            } else {
                return Optional.empty();
            }
        }

        public String toString() {
            return "Maven builder for Java using " + javaHomeProvider.toString();
        }
    }
}
