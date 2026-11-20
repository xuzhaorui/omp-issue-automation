package dev.omp.issue;

import java.io.PrintStream;
import java.nio.file.*;
import java.util.*;

public final class Main {
    private Main() {}
    public static void main(String[] args) { System.exit(run(args,System.out,System.err)); }
    public static int run(String[] args,PrintStream out,PrintStream err) {
        try {
            var options=Options.parse(args);
            if (options.command.equals("help")) { out.println(HELP);return 0; }
            if (options.command.equals("--version")) { out.println("OMP Issue Automation 1.0.0");return 0; }
            Config config=Config.load(Path.of(options.values.getOrDefault("config",System.getenv().getOrDefault("OMP_ISSUE_CONFIG",
                System.getProperty("user.home")+"/.config/omp-issue-automation/projects.yml"))));
            Json.privateDirectory(config.stateDir());
            switch (options.command) {
                case "create" -> {
                    var project=config.project(options.need("project"));
                    String requirement=options.need("requirement"), acceptance=options.values.getOrDefault("acceptance","");
                    if (acceptance.isBlank()) {
                        var draft=Requirements.draft(config,requirement);requirement=draft.requirement();acceptance=draft.acceptance();
                    }
                    var issue=new GitHub(config).create(project,requirement,acceptance);
                    out.println(Json.MAPPER.writeValueAsString(Map.of("project",project.name(),"number",issue.path("number").asInt(),"url",issue.path("html_url").asText())));
                }
                case "scan" -> { new Scheduler(config).scan();out.println("扫描及到期任务处理完成"); }
                case "daemon" -> {
                    Path running=Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
                    while (!Thread.currentThread().isInterrupted()) {
                        Path active=config.stateDir().resolve("current/app.jar");
                        if (Files.exists(active) && !active.toRealPath().equals(running)) { out.println("版本已切换，旧调度进程安全退出");break; }
                        try { new Scheduler(config).scan(); } catch (Exception e) { err.println(e.getMessage()); }
                        Thread.sleep(config.pollSeconds()*1000L);
                    }
                }
                case "status" -> out.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(new Store(config.stateDir()).jobs()));
                case "retry" -> {
                    int issue=Integer.parseInt(options.need("issue"));
                    if (issue<1) throw new IllegalArgumentException("Issue 编号必须为正整数");
                    new Scheduler(config).retry(config.project(options.need("project")),issue);
                    out.println("任务已重新排队；下次扫描自动执行");
                }
                case "install" -> { new Deployment(config).install(Path.of(options.need("jar")));out.println("独立版本已安装"); }
                case "update" -> { new Deployment(config).update(Path.of(options.need("source")));out.println("新版本测试通过，已安全切换"); }
                case "deployment" -> out.println(new Deployment(config).current());
                case "doctor" -> doctor(config,out);
                default -> throw new IllegalArgumentException("未知命令: "+options.command);
            }
            return 0;
        } catch (InterruptedException e) { Thread.currentThread().interrupt();err.println("已中断，任务保留供恢复");return 130; }
        catch (Exception e) { err.println("错误: "+e.getMessage());return 1; }
    }
    private static void doctor(Config config,PrintStream out) throws Exception {
        String help=Runner.run(List.of(config.omp().executable(),"--help"),config.stateDir(),null,30,Map.of()).requireSuccess();
        for (String capability : List.of("--mode","--session-dir","--no-tools","--no-extensions","--no-context-files","--max-cost"))
            if (!help.contains(capability)) throw new IllegalStateException("OMP 不支持必需能力: "+capability);
        var user=Runner.run(List.of("gh","api","user","--jq",".login"),config.stateDir(),null,30,Map.of()).requireSuccess().strip();
        String sandbox=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac") ? "sandbox-exec" : "bwrap";
        Runner.run(List.of("/usr/bin/which",sandbox),config.stateDir(),null,10,Map.of()).requireSuccess();
        for (var project : config.projects().values()) {
            Runner.git(project.path(),"rev-parse","--show-toplevel");
            if (new GitHub(config).optional("repos/"+project.repo()).isNull())
                throw new IllegalStateException("无法访问仓库: "+project.repo());
        }
        out.println("OMP 后台会话/工具限制可用；gh 已登录 "+user+"；测试沙箱 "+sandbox+" 可用；已注册 "+config.projects().size()+" 个项目。");
    }
    private record Options(String command,Map<String,String> values) {
        static Options parse(String[] args) {
            String command=null;Map<String,String> values=new HashMap<>();
            Set<String> flags=Set.of("config","project","requirement","acceptance","issue","jar","source");
            for (int i=0;i<args.length;i++) {
                String arg=args[i];
                if (arg.equals("--help") || arg.equals("-h")) return new Options("help",Map.of());
                if (arg.equals("--version")) return new Options("--version",Map.of());
                if (arg.startsWith("--")) {
                    String key=arg.substring(2);
                    if (!flags.contains(key) || i+1==args.length) throw new IllegalArgumentException("无效或缺值参数: "+arg);
                    if (values.putIfAbsent(key,args[++i])!=null) throw new IllegalArgumentException("重复参数: "+arg);
                } else if (command==null) command=arg;
                else throw new IllegalArgumentException("多余参数: "+arg);
            }
            if (command==null) command="help";
            Set<String> allowed=switch (command) {
                case "create" -> Set.of("config","project","requirement","acceptance");
                case "retry" -> Set.of("config","project","issue");
                case "install" -> Set.of("config","jar");case "update" -> Set.of("config","source");
                default -> Set.of("config");
            };
            for (String key : values.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("此命令不接受 --"+key);
            return new Options(command,values);
        }
        String need(String name) {
            String value=values.get(name);
            if (value==null || value.isBlank()) throw new IllegalArgumentException("缺少 --"+name);
            return value;
        }
    }
    private static final String HELP="""
        OMP Issue Automation — GitHub Issue 自动开发（不包含 Telegram 连接）
        用法: omp-issue [--config FILE] COMMAND [OPTIONS]
          create --project NAME --requirement TEXT [--acceptance TEXT]
                 创建带 agent:ready 的 Issue；未指定验收标准时由无工具 OMP 会话整理
          scan   扫描、原子领取并处理到期任务，完成后退出（供现有定时器调用）
          daemon 按 pollSeconds 周期扫描（本地备用调度器）
          status 查看本机任务状态、重试原因及 PR 链接
          retry --project NAME --issue NUMBER
          install --jar FILE       安装不可变版本及启动脚本
          update --source PATH     测试干净、已合并的主分支后安全切换
          deployment               查看当前安装版本
          doctor                   检查 OMP、gh、仓库和测试沙箱
          --version
        默认配置: ~/.config/omp-issue-automation/projects.yml 或 OMP_ISSUE_CONFIG
        """;
}
