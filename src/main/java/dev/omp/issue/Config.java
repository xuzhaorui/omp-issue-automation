package dev.omp.issue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public record Config(Path stateDir, int pollSeconds, int concurrency, int maxAttempts,
                     int retrySeconds, Omp omp, Map<String,Project> projects) {
    public record Omp(String executable, String model, int timeoutSeconds, double maxCost) {}
    public record Project(String name, Path path, String repo, String branch, List<String> aliases,
                          List<List<String>> tests, boolean self) {}
    public static Config load(Path file) throws IOException {
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(file.toFile());
        if (root == null || !root.isObject()) throw new IOException("配置必须为 YAML 对象");
        Path state = expand(root.path("stateDir").asText("~/.local/state/omp-issue-automation"));
        int poll = positive(root,"pollSeconds",60), concurrency = positive(root,"concurrency",2);
        int attempts = positive(root,"maxAttempts",3), retry = positive(root,"retrySeconds",60);
        JsonNode o = root.path("omp");
        var omp = new Omp(o.path("executable").asText("omp"),o.path("model").asText(""),
            positive(o,"timeoutSeconds",1800),o.path("maxCost").asDouble(5));
        if (!Double.isFinite(omp.maxCost()) || omp.maxCost() <= 0) throw new IOException("omp.maxCost 必须为正数");
        Map<String,Project> projects = new LinkedHashMap<>();
        Set<String> names = new HashSet<>(), repos = new HashSet<>();
        JsonNode registry = root.path("projects");
        if (!registry.isObject() || registry.isEmpty()) throw new IOException("projects 不能为空");
        var entries = registry.fields();
        while (entries.hasNext()) {
            var entry = entries.next(); var p = entry.getValue(); String name = entry.getKey();
            if (!name.matches("[A-Za-z0-9_-]+")) throw new IOException("项目名只允许字母、数字、_、-");
            String repo = p.path("repo").asText();
            if (!repo.matches("[A-Za-z0-9][A-Za-z0-9_-]*/[A-Za-z0-9_.-]+") ||
                Set.of(".","..").contains(repo.substring(repo.indexOf('/')+1)) || !repos.add(repo.toLowerCase(Locale.ROOT)))
                throw new IOException("仓库格式错误或重复: " + repo);
            String branch = p.path("branch").asText("main");
            if (branch.isBlank() || branch.startsWith("-") || branch.contains("..") || branch.contains("@{") ||
                !branch.matches("[A-Za-z0-9_./-]+") || branch.endsWith("/") || branch.endsWith(".lock"))
                throw new IOException("无效基础分支");
            String localPath = p.path("path").asText();
            if (localPath.isBlank()) throw new IOException("项目缺少 path: " + name);
            Path path = expand(localPath);
            if (!Files.isDirectory(path)) throw new IOException("本地项目目录不存在: " + name);
            List<String> aliases = new ArrayList<>();
            if (p.has("aliases")) {
                if (!p.get("aliases").isArray()) throw new IOException("aliases 必须为数组");
                for (var alias : p.get("aliases")) {
                    if (!alias.isTextual() || alias.asText().isBlank()) throw new IOException("别名不能为空");
                    aliases.add(alias.asText());
                }
            }
            for (String alias : concat(name,aliases))
                if (!names.add(alias.toLowerCase(Locale.ROOT))) throw new IOException("项目名或别名冲突: " + alias);
            List<List<String>> tests = new ArrayList<>();
            if (p.has("tests")) {
                if (!p.get("tests").isArray()) throw new IOException("tests 必须为命令数组的数组");
                for (var command : p.get("tests")) {
                    if (!command.isArray() || command.isEmpty()) throw new IOException("测试必须是非空 argv 数组，不接受 shell 字符串");
                    List<String> argv = new ArrayList<>();
                    for (var arg : command) {
                        if (!arg.isTextual() || arg.asText().indexOf(0) >= 0) throw new IOException("测试参数必须为字符串");
                        argv.add(arg.asText());
                    }
                    if (argv.getFirst().isBlank()) throw new IOException("测试程序不能为空");
                    tests.add(List.copyOf(argv));
                }
            }
            projects.put(name,new Project(name,path.toRealPath(),repo,branch,List.copyOf(aliases),List.copyOf(tests),p.path("self").asBoolean(false)));
        }
        if (projects.values().stream().filter(Project::self).count() > 1) throw new IOException("只能注册一个 self 项目");
        return new Config(state,poll,concurrency,attempts,retry,omp,Collections.unmodifiableMap(projects));
    }
    public Project project(String name) {
        return projects.values().stream().filter(p -> p.name().equalsIgnoreCase(name) ||
            p.aliases().stream().anyMatch(a -> a.equalsIgnoreCase(name))).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("未注册的项目: " + name));
    }
    private static List<String> concat(String first,List<String> rest) {
        var list = new ArrayList<String>(); list.add(first); list.addAll(rest); return list;
    }
    private static int positive(JsonNode node,String key,int fallback) throws IOException {
        JsonNode value = node.get(key);
        if (value != null && (!value.isIntegralNumber() || !value.canConvertToInt())) throw new IOException(key + " 必须为整数");
        int n = node.path(key).asInt(fallback);
        if (n < 1) throw new IOException(key + " 必须大于零");
        return n;
    }
    static Path expand(String path) {
        if (path.equals("~")) path = System.getProperty("user.home");
        else if (path.startsWith("~/")) path = System.getProperty("user.home") + path.substring(1);
        return Path.of(path).toAbsolutePath().normalize();
    }
}
