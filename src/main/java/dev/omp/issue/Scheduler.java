package dev.omp.issue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class Scheduler {
    private final Config config;
    private final GitHub github;
    private final Worker worker;
    private final Store store;
    public Scheduler(Config config) throws IOException { this(config,new GitHub(config),new Worker(config)); }
    public Scheduler(Config config,GitHub github,Worker worker) throws IOException {
        this.config=config;this.github=github;this.worker=worker;this.store=new Store(config.stateDir());
    }
    public void scan() throws Exception {
        try (var lease=Store.lock(config.stateDir())) {
            // No remote age-based stealing: only the durable local owner may recover a claim.
            Runner.recoverProcesses(config.stateDir().resolve("jobs"));
            boolean scanFailed=false;
            for (var project : config.projects().values()) {
                try {
                    for (var issue : github.ready(project)) {
                        int number=issue.path("number").asInt();
                        if (store.get(project.repo(),number)==null) store.save(new Store.Job(project,issue));
                    }
                } catch (Exception e) { scanFailed=true;log("扫描 "+project.name()+" 失败: "+e.getMessage()); }
            }
            List<Future<?>> tasks=new ArrayList<>();
            try (var pool=Executors.newFixedThreadPool(config.concurrency())) {
                for (var job : store.jobs()) {
                    if (job.nextAt>System.currentTimeMillis() || job.status.equals("foreign")) continue;
                    var project=config.projects().get(job.project);
                    if (project==null || !project.repo().equals(job.repo)) {
                        log("保留未注册项目任务，不执行: "+job.repo+"#"+job.issue);continue;
                    }
                    if (job.terminal() && job.reported && job.cleaned) continue;
                    tasks.add(pool.submit(() -> { execute(project,job); return null; }));
                }
                for (var task : tasks) task.get();
            }
            if (scanFailed) throw new IOException("部分仓库扫描失败；其他仓库与恢复任务已继续处理");
        }
    }
    public void retry(Config.Project project,int issue) throws Exception {
        try (var lease=Store.lock(config.stateDir())) {
            var job=store.get(project.repo(),issue);
            if (job==null || !job.status.equals("failed")) throw new IOException("只能重试本机已失败任务；请勿删除或抢占其他机器的领取记录");
            Runner.recoverProcesses(store.directory(job));
            job.status="pending";job.attempts=0;job.nextAt=0;job.error="";job.reported=false;job.cleaned=false;
            if (!job.stage.equals("PUBLISH")) job.stage=job.claimSha.isBlank() ? "CLAIMING" : "CLAIMED";
            store.save(job);
            github.ensureLabels(project);github.labels(project,issue,"ready");
        }
    }
    private void execute(Config.Project project,Store.Job job) throws Exception {
        if (job.terminal()) { finish(project,job);return; }
        try {
            job.status="running";job.attempts++;store.save(job);
            validateOrigin(project);
            if (job.stage.equals("CLAIMING")) {
                JsonNode current=github.issue(project,job.issue);
                if (!current.path("state").asText().equals("open") || current.has("pull_request") || !hasLabel(current,"agent:ready")) {
                    job.status="foreign";store.save(job);return;
                }
                if (job.claimSha.isBlank()) { job.claimSha=github.prepareClaim(project,job.owner);store.save(job); }
                if (!github.claim(project,job.issue,job.claimSha)) { job.status="foreign";store.save(job);return; }
                job.stage="CLAIMED";store.save(job);
            } else if (!github.claim(project,job.issue,job.claimSha)) {
                throw new IOException("远端任务归属与本地记录不一致，拒绝执行");
            }
            github.ensureLabels(project);github.labels(project,job.issue,"running");
            if (!job.stage.equals("PUBLISH")) {
                JsonNode existing=github.findPr(project,job.branch);
                if (!existing.isNull()) throw new IOException("任务分支已存在 PR，拒绝重复开发或未经验证地标记成功");
                Path worktree=prepare(project,job);
                job.stage="DEVELOP";store.save(job);
                var result=worker.run(project,job.snapshot,store.directory(job),worktree);
                if (!result.testsPassed()) throw new IOException("项目测试未通过");
                // Never publish free-form model output or local command logs to GitHub.
                job.summary="独立 OMP 会话完成开发，项目现有测试通过。详细测试结果及实际费用保留在本机。";
                job.stage="PUBLISH";store.save(job);
            }
            JsonNode existing=github.findPr(project,job.branch);
            if (existing.isNull()) {
                publish(project,job);
                existing=github.createPr(project,job);
            }
            if (existing.path("state").asText().equals("closed") && existing.path("merged_at").isNull())
                throw new IOException("现有 PR 已关闭且未合并，需要人工检查后再重试");
            job.prUrl=existing.path("html_url").asText();
            if (!job.prUrl.startsWith("https://github.com/"+project.repo()+"/pull/")) throw new IOException("GitHub 未返回有效 PR 链接");
            job.status="done";job.error="";job.nextAt=0;store.save(job);
            log(job.repo+"#"+job.issue+" 完成 "+job.prUrl);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            job.error=error.toString();
            job.status=job.attempts>=config.maxAttempts() ? "failed" : "retry";
            long multiplier=1L<<Math.min(10,Math.max(0,job.attempts-1));
            job.nextAt=System.currentTimeMillis()+Math.multiplyExact((long)config.retrySeconds()*1000,multiplier);
            store.save(job);
            log(job.repo+"#"+job.issue+" "+job.status+": "+job.error);
        }
        if (job.terminal()) finish(project,job);
    }
    private void finish(Config.Project project,Store.Job job) throws Exception {
        if (job.status.equals("foreign")) return;
        try {
            if (!job.reported) {
                github.ensureLabels(project);github.result(project,job,job.status);
                job.reported=true;store.save(job);
            }
            if (!job.cleaned) {
                cleanup(project,job);
                job.cleaned=true;store.save(job);
            }
        } catch (Exception e) {
            // Publication/cleanup retry must not rerun development or create another PR.
            job.error=e.toString();job.nextAt=System.currentTimeMillis()+config.retrySeconds()*1000L;store.save(job);
            log("结果回写或清理待重试: "+job.repo+"#"+job.issue+" "+e.getMessage());
        }
    }
    private Path worktree(Store.Job job) { return store.directory(job).resolve("worktree"); }
    private Path prepare(Config.Project project,Store.Job job) throws Exception {
        Path worktree=worktree(job);
        synchronized (project) {
            Runner.git(project.path(),"fetch","--no-tags","origin",project.branch());
            if (Files.exists(worktree.resolve(".git"))) {
                if (!Runner.git(worktree,"branch","--show-current").equals(job.branch)) throw new IOException("任务工作区分支不匹配");
                return worktree;
            }
            String reference="refs/heads/"+job.branch;
            var exists=Runner.run(List.of("git","show-ref","--verify","--quiet",reference),project.path(),null,30,Map.of());
            if (exists.exit()!=0 && exists.exit()!=1) exists.requireSuccess();
            if (exists.exit()==0) Runner.git(project.path(),"worktree","add",worktree.toString(),job.branch);
            else Runner.git(project.path(),"worktree","add","-b",job.branch,worktree.toString(),"FETCH_HEAD");
            return worktree;
        }
    }
    private void publish(Config.Project project,Store.Job job) throws Exception {
        Path tree=worktree(job);
        if (!Files.isDirectory(tree)) throw new IOException("已测试工作区丢失，请恢复本机状态备份，不能发布未验证内容");
        Runner.git(tree,"add","--all");
        var diff=Runner.run(List.of("git","diff","--cached","--quiet"),tree,null,30,Map.of());
        if (diff.exit()==1) Runner.git(tree,"-c","user.name=OMP Issue Automation","-c","user.email=omp-issue@users.noreply.github.com",
            "-c","commit.gpgSign=false","commit","-m","Implement issue #"+job.issue);
        else diff.requireSuccess();
        String base=Runner.git(tree,"rev-parse","origin/"+project.branch());
        if (Runner.git(tree,"rev-parse","HEAD").equals(base)) throw new IOException("Agent 没有产生代码变更，拒绝创建空 PR");
        Runner.git(tree,"push","origin","HEAD:refs/heads/"+job.branch);
    }
    private void cleanup(Config.Project project,Store.Job job) throws Exception {
        Path tree=worktree(job);
        synchronized (project) {
            if (Files.exists(tree)) Runner.git(project.path(),"worktree","remove","--force",tree.toString());
            Runner.git(project.path(),"worktree","prune");
            // Keep local and remote task branches and immutable claim refs for audit/recovery.
        }
    }
    private static void validateOrigin(Config.Project project) throws Exception {
        String origin=Runner.git(project.path(),"remote","get-url","origin").toLowerCase(Locale.ROOT);
        String repo=project.repo().toLowerCase(Locale.ROOT);
        if (!Set.of("https://github.com/"+repo,"https://github.com/"+repo+".git","git@github.com:"+repo,
            "git@github.com:"+repo+".git","ssh://git@github.com/"+repo,"ssh://git@github.com/"+repo+".git").contains(origin))
            throw new IOException("本地 origin 与注册的 GitHub 仓库不一致");
    }
    private static boolean hasLabel(JsonNode issue,String name) {
        for (var label : issue.path("labels")) if (label.path("name").asText().equals(name)) return true;
        return false;
    }
    private static synchronized void log(String text) { System.err.println(Instant.now()+" "+text); }
}
