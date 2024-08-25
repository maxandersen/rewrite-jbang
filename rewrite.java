///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.6
//DEPS org.slf4j:slf4j-nop:2.0.16

//DEPS org.openrewrite:rewrite-core:7.9.0
//DEPS org.openrewrite:rewrite-java:7.9.0
//DEPS org.openrewrite:rewrite-java-8:7.9.0
//DEPS org.openrewrite:rewrite-java-11:7.9.0
//DEPS org.openrewrite:rewrite-xml:7.9.0
//DEPS org.openrewrite:rewrite-maven:7.9.0
//DEPS org.openrewrite:rewrite-properties:7.9.0
//DEPS org.openrewrite:rewrite-yaml:7.9.0



import static java.lang.System.err;
import static java.lang.System.out;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.toList;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.Validated;
import org.openrewrite.config.Environment;
import org.openrewrite.config.OptionDescriptor;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.maven.MavenExecutionContextView;
import org.openrewrite.maven.MavenParser;
import org.openrewrite.maven.MavenSettings;
import org.openrewrite.maven.MavenVisitor;
import org.openrewrite.maven.tree.Maven;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.properties.PropertiesVisitor;
import org.openrewrite.shaded.jgit.util.FileUtils;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.xml.XmlVisitor;
import org.openrewrite.yaml.YamlParser;
import org.openrewrite.yaml.YamlVisitor;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "rewrite", mixinStandardHelpOptions = true, version = "rewrite 0.1", description = "rewrite made with jbang", subcommands = rewrite.rewriteDiscover.class)
class rewrite implements Callable<Integer> {

    private static final String RECIPE_NOT_FOUND_EXCEPTION_MSG = "Could not find recipe '%s' among available recipes";

    @Option(names = "--recipes", split = ",")
    Set<String> activeRecipes = emptySet();

    @Option(names = "--styles", split = ",")
    protected Set<String> activeStyles = Collections.emptySet();

    @Option(names = {"--javaSources", "--java-sources"}, defaultValue = ".", split = ",")
    List<String> javaSourcePaths = emptyList();

    @Option(names = {"--failOnInvalidActiveRecipes", "--fail-on-invalid-recipes"}, defaultValue = "false")
    boolean failOnInvalidActiveRecipes;

    @Option(names = {"--reportOutputDirectory", "--report"}, defaultValue = "./rewrite")
    private File reportOutputDirectory;

    @Option(names = {"--failOnDryRunResults", "--fail-on-dry-run"}, defaultValue = "false")
    boolean failOnDryRunResults;

    @Option(names = "--dry-run", defaultValue = "false")
    boolean dryRun;

    Environment environment() {

        Environment.Builder env = Environment.builder().scanRuntimeClasspath().scanUserHome();

        return env.build();
    }

    /**
     * Maven dependency resolution has a few bugs that lead to the log filling up
     * with (recoverable) errors. While we work on fixing those issues, set this to
     * 'true' during maven parsing to avoid log spam
     */
    protected boolean suppressWarnings;

    protected ExecutionContext executionContext() {
        return new InMemoryExecutionContext(t -> {
            if (!suppressWarnings) {
                warn(t.getMessage());
            }
        });
    }

    protected Maven parseMaven(Path baseDir, ExecutionContext ctx) {
        List<Path> allPoms = new ArrayList<>();
        allPoms.add(baseDir);

        // children
        /*
         * if (project.getCollectedProjects() != null) {
         * project.getCollectedProjects().stream() .filter(collectedProject ->
         * collectedProject != project) .map(collectedProject ->
         * collectedProject.getFile().toPath()) .forEach(allPoms::add); }
         * 
         * MavenProject parent = project.getParent(); while (parent != null &&
         * parent.getFile() != null) { allPoms.add(parent.getFile().toPath()); parent =
         * parent.getParent(); }
         */

        MavenParser.Builder mavenParserBuilder = MavenParser.builder()
                .mavenConfig(baseDir.resolve(".mvn/maven.config"));

        /*
         * if (pomCacheEnabled) { try { if (pomCacheDirectory == null) { //Default
         * directory in the RocksdbMavenPomCache is ".rewrite-cache"
         * mavenParserBuilder.cache(new
         * RocksdbMavenPomCache(Paths.get(System.getProperty("user.home")))); } else {
         * mavenParserBuilder.cache(new
         * RocksdbMavenPomCache(Paths.get(pomCacheDirectory))); } } catch (Exception e)
         * { getLog().
         * warn("Unable to initialize RocksdbMavenPomCache, falling back to InMemoryMavenPomCache"
         * ); getLog().debug(e); mavenParserBuilder.cache(new InMemoryMavenPomCache());
         * } }
         */

        Path mavenSettings = Paths.get(System.getProperty("user.home")).resolve(".m2/settings.xml");
        if (mavenSettings.toFile().exists()) {
            MavenSettings settings = MavenSettings.parse(new org.openrewrite.Parser.Input(mavenSettings, () -> {
                try {
                    return Files.newInputStream(mavenSettings);
                } catch (IOException e) {
                    warn("Unable to load Maven settings from user home directory. Skipping.", e);
                    return null;
                }
            }), ctx);
            if (settings != null) {
                new MavenExecutionContextView(ctx).setMavenSettings(settings);
                if (settings.getActiveProfiles() != null) {
                    mavenParserBuilder
                            .activeProfiles(settings.getActiveProfiles().getActiveProfiles().toArray(new String[]{}));
                }
            }
        }

        try {
            // suppressing warnings down to debug log level is temporary while we work out
            // the kinks in maven dependency resolution
            suppressWarnings = true;
            return mavenParserBuilder.build().parse(allPoms, baseDir, ctx).iterator().next();
        } finally {
            suppressWarnings = false;
        }
    }

    protected static List<Path> listJavaSources(String sourceDirectory) {
        File sourceDirectoryFile = new File(sourceDirectory);
        if (!sourceDirectoryFile.exists()) {
            return emptyList();
        }

        Path sourceRoot = sourceDirectoryFile.toPath();
        try {
            return Files.walk(sourceRoot).filter(f -> !Files.isDirectory(f) && f.toFile().getName().endsWith(".java"))
                    .map(it -> {
                        try {
                            return it.toRealPath();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }).collect(toList());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to list Java source files", e);
        }
    }

    private void discoverRecipeTypes(Recipe recipe, Set<Class<?>> recipeTypes) {
        for (Recipe next : recipe.getRecipeList()) {
            discoverRecipeTypes(next, recipeTypes);
        }

        try {
            Method getVisitor = recipe.getClass().getDeclaredMethod("getVisitor");
            getVisitor.setAccessible(true);
            Object visitor = getVisitor.invoke(recipe);
            if (visitor instanceof MavenVisitor) {
                recipeTypes.add(MavenVisitor.class);
            } else if (visitor instanceof JavaVisitor) {
                recipeTypes.add(JavaVisitor.class);
            } else if (visitor instanceof PropertiesVisitor) {
                recipeTypes.add(PropertiesVisitor.class);
            } else if (visitor instanceof XmlVisitor) {
                recipeTypes.add(XmlVisitor.class);
            } else if (visitor instanceof YamlVisitor) {
                recipeTypes.add(YamlVisitor.class);
            }
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException ignored) {
            // not every recipe will implement getVisitor() directly, e.g. CompositeRecipe.
        }
    }

    public static class ResultsContainer {
        final Path projectRoot;
        final List<Result> generated = new ArrayList<>();
        final List<Result> deleted = new ArrayList<>();
        final List<Result> moved = new ArrayList<>();
        final List<Result> refactoredInPlace = new ArrayList<>();

        public ResultsContainer(Path projectRoot, Collection<Result> results) {
            this.projectRoot = projectRoot;
            for (Result result : results) {
                if (result.getBefore() == null && result.getAfter() == null) {
                    // This situation shouldn't happen / makes no sense, log and skip
                    continue;
                }
                if (result.getBefore() == null && result.getAfter() != null) {
                    generated.add(result);
                } else if (result.getBefore() != null && result.getAfter() == null) {
                    deleted.add(result);
                } else if (result.getBefore() != null
                        && !result.getBefore().getSourcePath().equals(result.getAfter().getSourcePath())) {
                    moved.add(result);
                } else {
                    refactoredInPlace.add(result);
                }
            }
        }

        public Path getProjectRoot() {
            return projectRoot;
        }

        public boolean isNotEmpty() {
            return !generated.isEmpty() || !deleted.isEmpty() || !moved.isEmpty() || !refactoredInPlace.isEmpty();
        }
    }

    rewrite.ResultsContainer listResults() {
        var env = environment();

        var recipe = env.activateRecipes(activeRecipes);

        List<NamedStyles> styles;
        styles = env.activateStyles(activeStyles);

        info(""); // because stupid antlr warning dumps without println

        info("Validating active recipes...");
        Collection<Validated> validated = recipe.validateAll();
        List<Validated.Invalid> failedValidations = validated.stream().map(Validated::failures)
                .flatMap(Collection::stream).collect(toList());
        if (!failedValidations.isEmpty()) {
            failedValidations
                    .forEach(failedValidation -> error("Recipe validation error in " + failedValidation.getProperty()
                            + ": " + failedValidation.getMessage(), failedValidation.getException()));
            if (failOnInvalidActiveRecipes) {
                throw new IllegalStateException(
                        "Recipe validation errors detected as part of one or more activeRecipe(s). Please check error logs.");
            } else {
                error("Recipe validation errors detected as part of one or more activeRecipe(s). Execution will continue regardless.");
            }
        }

        List<Path> javaSources = new ArrayList<>();
        javaSourcePaths.forEach(path -> javaSources.addAll(listJavaSources(path)));

        ExecutionContext ctx = executionContext();
        info("Parsing Java files... in " + javaSources);

        Path baseDir = Path.of(".").toAbsolutePath(); // TODO: proper basedir?

        List<SourceFile> sourceFiles = new ArrayList<>(JavaParser.fromJavaVersion().relaxedClassTypeMatching(true)
                .styles(styles)
                .classpath(new HashSet<String>().stream().distinct().map(java.nio.file.Paths::get).collect(toList()))
                .logCompilationWarningsAndErrors(true).build().parse(javaSources, baseDir, ctx));

        info(sourceFiles.size() + " java files parsed.");

        Set<Path> resources = new HashSet<>();
        // TODO: add resources

        Set<Class<?>> recipeTypes = new HashSet<>();
        discoverRecipeTypes(recipe, recipeTypes);

        if (recipeTypes.contains(YamlVisitor.class)) {
            info("Parsing YAML files...");
            sourceFiles
                    .addAll(new YamlParser().parse(
                            resources.stream()
                                    .filter(it -> it.getFileName().toString().endsWith(".yml")
                                            || it.getFileName().toString().endsWith(".yaml"))
                                    .collect(toList()),
                            baseDir, ctx));
        } else {
            info("Skipping YAML files because there are no active YAML recipes.");
        }

        if (recipeTypes.contains(PropertiesVisitor.class)) {
            info("Parsing properties files...");
            sourceFiles.addAll(new PropertiesParser().parse(resources.stream()
                            .filter(it -> it.getFileName().toString().endsWith(".properties")).collect(toList()), baseDir,
                    ctx));
        } else {
            info("Skipping properties files because there are no active properties recipes.");
        }

        if (recipeTypes.contains(XmlVisitor.class)) {
            info("Parsing XML files...");
            sourceFiles.addAll(new XmlParser().parse(
                    resources.stream().filter(it -> it.getFileName().toString().endsWith(".xml")).collect(toList()),
                    baseDir, ctx));
        } else {
            info("Skipping XML files because there are no active XML recipes.");
        }

        if (recipeTypes.contains(MavenVisitor.class)) {
            info("Parsing POM...");
            Maven pomAst = parseMaven(baseDir, ctx);
            sourceFiles.add(pomAst);
        } else {
            info("Skipping Maven POM files because there are no active Maven recipes.");
        }

        info("Running recipe(s)...");
        List<Result> results = recipe.run(sourceFiles, ctx);

        return new ResultsContainer(baseDir, results);

    }

    rewrite getLog() {
        return this;
    }

    protected void logRecipesThatMadeChanges(Result result) {
        for (Recipe recipe : result.getRecipesThatMadeChanges()) {
            getLog().warn("    " + recipe.getName());
        }
    }

    void dryRun() {
        ResultsContainer results = listResults();

        if (results.isNotEmpty()) {
            for (Result result : results.generated) {
                assert result.getAfter() != null;
                getLog().warn("These recipes would generate new file " + result.getAfter().getSourcePath() + ":");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.deleted) {
                assert result.getBefore() != null;
                getLog().warn("These recipes would delete file " + result.getBefore().getSourcePath() + ":");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.moved) {
                assert result.getBefore() != null;
                assert result.getAfter() != null;
                getLog().warn("These recipes would move file from " + result.getBefore().getSourcePath() + " to "
                        + result.getAfter().getSourcePath() + ":");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.refactoredInPlace) {
                assert result.getBefore() != null;
                getLog().warn("These recipes would make changes to " + result.getBefore().getSourcePath() + ":");
                logRecipesThatMadeChanges(result);
            }

            // noinspection ResultOfMethodCallIgnored
            reportOutputDirectory.mkdirs();

            Path patchFile = reportOutputDirectory.toPath().resolve("rewrite.patch");
            try (BufferedWriter writer = Files.newBufferedWriter(patchFile)) {
                Stream.concat(Stream.concat(results.generated.stream(), results.deleted.stream()),
                        Stream.concat(results.moved.stream(), results.refactoredInPlace.stream())).map(Result::diff)
                        .forEach(diff -> {
                            try {
                                writer.write(diff + "\n");
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });

            } catch (Exception e) {
                throw new IllegalStateException("Unable to generate rewrite result file.", e);
            }
            getLog().warn("Report available:");
            getLog().warn("    " + patchFile.normalize().toString());
            // getLog().warn("Run 'mvn rewrite:run' to apply the recipes.");

            if (failOnDryRunResults) {
                throw new IllegalStateException("Applying recipes would make changes. See logs for more details.");
            }
        }
    }

    void realrun() {
        ResultsContainer results = listResults();

        if (results.isNotEmpty()) {
            for (Result result : results.generated) {
                assert result.getAfter() != null;
                getLog().warn("Generated new file " +
                        result.getAfter().getSourcePath().normalize() +
                        " by:");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.deleted) {
                assert result.getBefore() != null;
                getLog().warn("Deleted file " +
                        result.getBefore().getSourcePath().normalize() +
                        " by:");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.moved) {
                assert result.getAfter() != null;
                assert result.getBefore() != null;
                getLog().warn("File has been moved from " +
                        result.getBefore().getSourcePath().normalize() + " to " +
                        result.getAfter().getSourcePath().normalize() + " by:");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.refactoredInPlace) {
                assert result.getBefore() != null;
                getLog().warn("Changes have been made to " +
                        result.getBefore().getSourcePath().normalize() +
                        " by:");
                logRecipesThatMadeChanges(result);
            }

            getLog().warn("Please review and commit the results.");

            try {
                for (Result result : results.generated) {
                    assert result.getAfter() != null;
                    try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                            results.getProjectRoot().resolve(result.getAfter().getSourcePath()))) {
                        sourceFileWriter.write(result.getAfter().print());
                    }
                }
                for (Result result : results.deleted) {
                    assert result.getBefore() != null;
                    Path originalLocation = results.getProjectRoot().resolve(result.getBefore().getSourcePath()).normalize();
                    boolean deleteSucceeded = originalLocation.toFile().delete();
                    if (!deleteSucceeded) {
                        throw new IOException("Unable to delete file " + originalLocation.toAbsolutePath());
                    }
                }
                for (Result result : results.moved) {
                    // Should we try to use git to move the file first, and only if that fails fall back to this?
                    assert result.getBefore() != null;
                    Path originalLocation = results.getProjectRoot().resolve(result.getBefore().getSourcePath());
                    boolean deleteSucceeded = originalLocation.toFile().delete();
                    if (!deleteSucceeded) {
                        throw new IOException("Unable to delete file " + originalLocation.toAbsolutePath());
                    }
                    assert result.getAfter() != null;
                    // Ensure directories exist in case something was moved into a hitherto non-existent package
                    Path afterLocation = results.getProjectRoot().resolve(result.getAfter().getSourcePath());
                    File parentDir = afterLocation.toFile().getParentFile();
                    //noinspection ResultOfMethodCallIgnored
                    parentDir.mkdirs();
                    try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(afterLocation)) {
                        sourceFileWriter.write(result.getAfter().print());
                    }
                }
                for (Result result : results.refactoredInPlace) {
                    assert result.getBefore() != null;
                    try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                            results.getProjectRoot().resolve(result.getBefore().getSourcePath()))) {
                        assert result.getAfter() != null;
                        sourceFileWriter.write(result.getAfter().print());
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Unable to rewrite source files", e);
            }
        }
    }


    @Override
    public Integer call() { // your business logic goes here...

        if (dryRun) {
            dryRun();
        } else {
            realrun();
        }

        return 0;
    }

    void info(String msg) {
        out.println("[INFO] " + msg);
    }

    void warn(String msg) {
        out.println("[WARN] " + msg);
    }

    void warn(String msg, Throwable t) {
        err.println("[WARN] " + msg);
        if (t != null) {
            t.printStackTrace(err);
        }
    }

    void error(String msg) {
        error(msg, null);
    }

    void error(String msg, Throwable t) {
        err.println("[ERROR] " + msg);
        if (t != null) {
            t.printStackTrace(err);
        }
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new rewrite()).execute(args);
        System.exit(exitCode);
    }

    public static RecipeDescriptor getRecipeDescriptor(String recipe, Collection<RecipeDescriptor> recipeDescriptors)  {
        return recipeDescriptors.stream()
                .filter(r -> r.getName().equalsIgnoreCase(recipe))
                .findAny()
                .orElseThrow(() -> new IllegalStateException(String.format(RECIPE_NOT_FOUND_EXCEPTION_MSG, recipe)));
    }


    @CommandLine.Command(name = "discover")
    static class rewriteDiscover implements Callable<Integer> {

        @CommandLine.ParentCommand
        private rewrite rewrite; // picocli injects reference to parent command

        /**
         * The name of a specific recipe to show details for. For example:<br>
         * {@code rewrite discover --detail --recipe=org.openrewrite.java.format.AutoFormat}
         */
        @Option(names = "recipe")
        String recipe;

        /**
         * Whether to display recipe details such as displayName, description, and configuration options.
         */
        @Option(names = "detail", defaultValue = "false")
        boolean detail;

        /**
         * The maximum level of recursion to display recipe descriptors under recipeList.
         */
        @Option(names = "recursion", defaultValue = "0")
        int recursion;

        /**
         * Whether to enter an interactive shell to explore available recipes. For example:<br>
         * {@code ./mvnw rewrite:discover -Dinteractive}
         */
        // @Option(names = "interactive", defaultValue = "false")
        //boolean interactive;

        // @Component
        // private Prompter prompter;

        rewrite getLog() {
            return rewrite;
        }

        @Override
        public Integer call()  {
            Environment env = rewrite.environment();
            Collection<RecipeDescriptor> availableRecipeDescriptors = env.listRecipeDescriptors();
            if (recipe != null) {
                RecipeDescriptor rd = getRecipeDescriptor(recipe, availableRecipeDescriptors);
                writeRecipeDescriptor(rd, detail, 0, 0);
            } /*else if (interactive) {
                getLog().info("Entering interactive mode, Ctrl-C to exit...");
                RecipeDescriptorTreePrompter treePrompter = new RecipeDescriptorTreePrompter(prompter);
                RecipeDescriptor rd = treePrompter.execute(availableRecipeDescriptors);
                writeRecipeDescriptor(rd, true, 0, 0);
            } */ else {
                Collection<RecipeDescriptor> activeRecipeDescriptors = new HashSet<>();
                for (String activeRecipe : rewrite.activeRecipes) {
                    RecipeDescriptor rd = getRecipeDescriptor(activeRecipe, availableRecipeDescriptors);
                    activeRecipeDescriptors.add(rd);
                }
                writeDiscovery(availableRecipeDescriptors, activeRecipeDescriptors, env.listStyles());
            }
            return 0;
        }

        private void writeDiscovery(Collection<RecipeDescriptor> availableRecipeDescriptors, Collection<RecipeDescriptor> activeRecipeDescriptors, Collection<NamedStyles> availableStyles) {
            getLog().info("Available Recipes:");
            for (RecipeDescriptor recipeDescriptor : availableRecipeDescriptors) {
                writeRecipeDescriptor(recipeDescriptor, detail, 0, 1);
            }

            getLog().info("");
            getLog().info("Available Styles:");
            for (NamedStyles style : availableStyles) {
                getLog().info("    " + style.getName());
            }

            getLog().info("");
            getLog().info("Active Styles:");
            for (String activeStyle : rewrite.activeStyles) {
                getLog().info("    " + activeStyle);
            }

            getLog().info("");
            getLog().info("Active Recipes:");
            for (RecipeDescriptor recipeDescriptor : activeRecipeDescriptors) {
                writeRecipeDescriptor(recipeDescriptor, detail, 0, 1);
            }

            getLog().info("");
            getLog().info("Found " + availableRecipeDescriptors.size() + " available recipes and " + availableStyles.size() + " available styles.");
            getLog().info("Configured with " + activeRecipeDescriptors.size() + " active recipes and " + rewrite.activeStyles.size() + " active styles.");
        }

        private void writeRecipeDescriptor(RecipeDescriptor rd, boolean verbose, int currentRecursionLevel, int indentLevel) {
            String indent = StringUtils.repeat("    ", indentLevel * 4);
            if (currentRecursionLevel <= recursion) {
                if (verbose) {

                    getLog().info(indent + rd.getDisplayName());
                    getLog().info(indent + "    " + rd.getName());
                    if (!rd.getDescription().isEmpty()) {
                        getLog().info(indent + "    " + rd.getDescription());
                    }

                    if (!rd.getOptions().isEmpty()) {
                        getLog().info(indent + "options: ");
                        for (OptionDescriptor od : rd.getOptions()) {
                            getLog().info(indent + "    " + od.getName() + ": " + od.getType() + (od.isRequired() ? "!" : ""));
                            if (od.getDescription() != null && !od.getDescription().isEmpty()) {
                                getLog().info(indent + "    " + "    " + od.getDescription());
                            }
                        }
                    }
                } else {
                    getLog().info(indent + rd.getName());
                }

                if (!rd.getRecipeList().isEmpty() && (currentRecursionLevel + 1 <= recursion)) {
                    getLog().info(indent + "recipeList:");
                    for (RecipeDescriptor r : rd.getRecipeList()) {
                        writeRecipeDescriptor(r, verbose, currentRecursionLevel + 1, indentLevel + 1);
                    }
                }

                if (verbose) {
                    getLog().info("");
                }
            }
        }


    }

}
