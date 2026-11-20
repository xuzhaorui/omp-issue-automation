package dev.omp.issue;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Executes project-controlled test code without network, host home, or host write access. */
public final class Sandbox {
    private Sandbox() {}

    public static Runner.Result run(List<String> command, Path worktree, Path scratch, Path log, int timeout) throws IOException, InterruptedException {
        if (command == null || command.isEmpty() || command.getFirst().isBlank()) throw new IOException("Empty test command");
        Path root = worktree.toRealPath();
        Files.createDirectories(scratch);
        Path temp = scratch.toRealPath();
        if (temp.startsWith(root) || root.startsWith(temp)) throw new IOException("Sandbox scratch and worktree must be separate");
        Path home = Files.createDirectories(temp.resolve("home"));
        Path tmp = Files.createDirectories(temp.resolve("tmp"));
        Path java = trustedToolchain("JAVA_HOME", "java");
        Path maven = trustedToolchain("MAVEN_HOME", "maven");
        Path repository = trustedRepository();
        List<Path> readOnly = new ArrayList<>();
        if (java != null) readOnly.add(java);
        if (maven != null && !readOnly.contains(maven)) readOnly.add(maven);
        if (repository != null) {
            readOnly.add(repository);
            Path settings = Files.createDirectories(home.resolve(".m2")).resolve("settings.xml");
            String local = repository.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
            Files.writeString(settings, "<settings><offline>true</offline><localRepository>" + local + "</localRepository></settings>");
        }
        String testPath = (maven == null ? "" : maven.resolve("bin") + ":") +
            (java == null ? "" : java.resolve("bin") + ":") + "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin";
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        List<String> argv = new ArrayList<>();
        if (os.contains("mac")) {
            Path executable = Path.of("/usr/bin/sandbox-exec");
            if (!Files.isExecutable(executable)) throw new IOException("sandbox-exec is unavailable");
            StringBuilder profile = new StringBuilder("(version 1)(deny default)(allow process-exec)(allow process-fork)(allow process-info* (target same-sandbox))(allow signal (target same-sandbox))(allow sysctl-read)(allow file-read-metadata)(allow file-read-data (literal \"/\"))");
            profile.append("(allow file-read* (subpath \"/System\") (subpath \"/usr\") (subpath \"/bin\") (subpath \"/sbin\") (subpath \"/opt/homebrew\") (subpath \"/Library/Java/JavaVirtualMachines\") (subpath \"/Library/Developer/CommandLineTools\") (literal \"/dev/null\") (literal \"/dev/zero\") (literal \"/dev/random\") (literal \"/dev/urandom\") (subpath ").append(quote(root)).append(") (subpath ").append(quote(temp)).append(")");
            for (Path allowed : readOnly) profile.append(" (subpath ").append(quote(allowed)).append(")");
            profile.append(")(allow file-write* (subpath ").append(quote(root)).append(") (subpath ").append(quote(temp)).append(") (literal \"/dev/null\"))(deny network*)");
            argv.addAll(List.of(executable.toString(), "-p", profile.toString(), "--"));
        } else if (os.contains("linux")) {
            Path bwrap = findExecutable("bwrap");
            if (bwrap == null) throw new IOException("bubblewrap is unavailable");
            argv.addAll(List.of(bwrap.toString(), "--die-with-parent", "--new-session", "--unshare-all", "--clearenv"));
            for (String system : List.of("/usr", "/bin", "/sbin", "/lib", "/lib64"))
                if (Files.exists(Path.of(system))) argv.addAll(List.of("--ro-bind", system, system));
            argv.addAll(List.of("--proc", "/proc", "--dev", "/dev"));
            Set<String> made = new HashSet<>();
            for (Path target : List.of(root, temp)) mount(argv, made, target, false);
            for (Path target : readOnly) {
                if (!target.startsWith(Path.of("/usr")) && !target.startsWith(Path.of("/bin")) && !target.startsWith(Path.of("/lib")))
                    mount(argv, made, target, true);
            }
            argv.addAll(List.of("--chdir", root.toString(), "--setenv", "HOME", home.toString(), "--setenv", "TMPDIR", tmp.toString(), "--setenv", "PATH", testPath));
            if (java != null) argv.addAll(List.of("--setenv", "JAVA_HOME", java.toString()));
            if (maven != null) argv.addAll(List.of("--setenv", "MAVEN_HOME", maven.toString()));
            if (java != null) argv.addAll(List.of("--setenv", "OMP_SANDBOX_JAVA", java.toString()));
            if (maven != null) argv.addAll(List.of("--setenv", "OMP_SANDBOX_MAVEN", maven.toString()));
            if (repository != null) argv.addAll(List.of("--setenv", "OMP_SANDBOX_REPOSITORY", repository.toString()));
            argv.add("--");
        } else throw new IOException("No supported test sandbox for " + os);
        argv.addAll(command);
        Map<String,String> env = new HashMap<>();
        System.getenv().keySet().forEach(k -> env.put(k, null));
        env.put("HOME", home.toString()); env.put("TMPDIR", tmp.toString());
        env.put("PATH", testPath);
        if (java != null) env.put("JAVA_HOME", java.toString());
        if (maven != null) env.put("MAVEN_HOME", maven.toString());
        if (java != null) env.put("OMP_SANDBOX_JAVA", java.toString());
        if (maven != null) env.put("OMP_SANDBOX_MAVEN", maven.toString());
        if (repository != null) env.put("OMP_SANDBOX_REPOSITORY", repository.toString());
        env.put("LANG", "C.UTF-8");
        return Runner.run(argv, root, log, timeout, env);
    }
    private static Path trustedToolchain(String variable, String candidate) throws IOException {
        String value = System.getenv(variable);
        if (value == null || value.isBlank()) return null;
        Path path = Path.of(value).toRealPath();
        Path home = Path.of(System.getProperty("user.home")).toRealPath();
        Path sdk = home.resolve(".sdkman/candidates").resolve(candidate);
        boolean sdkAllowed = Files.isDirectory(sdk) && path.startsWith(sdk.toRealPath());
        String inherited = System.getenv("OMP_SANDBOX_" + candidate.toUpperCase(Locale.ROOT));
        boolean inheritedAllowed = inherited != null && path.equals(Path.of(inherited).toRealPath());
        if (!sdkAllowed && !inheritedAllowed && !path.startsWith(Path.of("/usr")) && !path.startsWith(Path.of("/opt/homebrew")) &&
            !path.startsWith(Path.of("/Library/Java/JavaVirtualMachines")))
            throw new IOException("Untrusted " + variable + " toolchain location");
        return path;
    }
    private static Path trustedRepository() throws IOException {
        String inherited = System.getenv("OMP_SANDBOX_REPOSITORY");
        if (inherited != null) return Path.of(inherited).toRealPath();
        Path home = Path.of(System.getProperty("user.home")).toRealPath();
        Path repository = home.resolve(".m2/repository");
        if (!Files.isDirectory(repository)) return null;
        Path real = repository.toRealPath();
        if (!real.startsWith(home)) throw new IOException("Maven cache outside user home");
        return real;
    }
    private static void mount(List<String> argv, Set<String> made, Path target, boolean readOnly) {
        Path current = target.getRoot();
        for (Path part : target.getParent()) {
            current = current.resolve(part);
            String value = current.toString();
            if (made.add(value) && !List.of("/usr", "/bin", "/sbin", "/lib", "/lib64").contains(value))
                argv.addAll(List.of("--dir", value));
        }
        argv.addAll(List.of(readOnly ? "--ro-bind" : "--bind", target.toString(), target.toString()));
    }
    private static String quote(Path p) throws IOException {
        String s = p.toString();
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\0') >= 0) throw new IOException("Invalid sandbox path");
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    private static Path findExecutable(String command) {
        for (String dir : List.of("/usr/bin", "/bin", "/usr/local/bin")) {
            Path file = Path.of(dir, command); if (Files.isExecutable(file)) return file;
        }
        return null;
    }
}
