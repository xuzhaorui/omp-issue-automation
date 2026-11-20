package dev.omp.issue;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.*;

/** Installs content-addressed releases without modifying an active executable. */
public final class Deployment {
    private final Config config;
    public Deployment(Config config) { this.config = config; }

    public void install(Path jar) throws Exception {
        withLock(() -> activate(jar));
    }

    public void update(Path source) throws Exception {
        withLock(() -> {
            Path root = source.toRealPath();
            Config.Project self = config.projects().values().stream().filter(Config.Project::self).findFirst()
                .orElseThrow(() -> new IOException("未注册 self 项目"));
            if (!root.equals(self.path().toRealPath()) || !self.branch().equals("main"))
                throw new IOException("更新源必须是注册的 self 项目及 main 分支");
            Path state = config.stateDir().toAbsolutePath().normalize().toRealPath();
            if (state.startsWith(root.resolve("target")))
                throw new IOException("状态目录不能位于 Maven target 下");
            Path releases = config.stateDir().toAbsolutePath().normalize().resolve("releases");
            if (Files.exists(releases) && root.startsWith(releases.toRealPath()))
                throw new IOException("不能从已安装的 release 更新");
            if (!Runner.git(root,"rev-parse","--show-toplevel").equals(root.toString()) ||
                !Runner.git(root,"branch","--show-current").equals("main"))
                throw new IOException("更新源必须检出 main 工作树根目录");
            String remote = Runner.git(root,"remote","get-url","origin");
            String expected = self.repo();
            if (!(remote.equals("https://github.com/" + expected) || remote.equals("https://github.com/" + expected + ".git") ||
                  remote.equals("git@github.com:" + expected) || remote.equals("git@github.com:" + expected + ".git") ||
                  remote.equals("ssh://git@github.com/" + expected) || remote.equals("ssh://git@github.com/" + expected + ".git")))
                throw new IOException("origin 与注册的 GitHub 仓库不一致");
            requireClean(root);
            Runner.git(root,"fetch","--no-tags","origin","refs/heads/main:refs/remotes/origin/main");
            String commit = Runner.git(root,"rev-parse","HEAD");
            if (!commit.equals(Runner.git(root,"rev-parse","refs/remotes/origin/main")))
                throw new IOException("更新源不是 origin/main 当前提交；请先人工合并并同步");
            Runner.run(List.of("mvn","clean","verify"),root,null,3600,Map.of()).requireSuccess();
            requireClean(root);
            if (!commit.equals(Runner.git(root,"rev-parse","HEAD")) ||
                !commit.equals(Runner.git(root,"rev-parse","refs/remotes/origin/main")))
                throw new IOException("验证期间源提交发生变化");
            activate(root.resolve("target/omp-issue-automation.jar"));
        });
    }

    public String current() throws Exception {
        Path link = config.stateDir().resolve("current");
        return Files.isSymbolicLink(link) ? Files.readSymbolicLink(link).toString() : "未安装";
    }

    private void requireClean(Path root) throws Exception {
        if (!Runner.git(root,"status","--porcelain","--untracked-files=all").isEmpty())
            throw new IOException("更新源工作树不干净");
    }

    private void withLock(Action action) throws Exception {
        try (Store.Lease ignored = Store.lock(config.stateDir().toAbsolutePath().normalize())) {
            action.run();
        }
    }

    private void activate(Path jar) throws Exception {
        if (!Files.isRegularFile(jar) || !Files.isReadable(jar)) throw new IOException("无法读取 JAR: " + jar);
        Path state = config.stateDir().toAbsolutePath().normalize();
        Path releases = state.resolve("releases");
        Json.privateDirectory(releases);
        Path staged = Files.createTempDirectory(releases,".staging-");
        try {
            Path copy = staged.resolve("app.jar");
            Files.copy(jar,copy);
            String digest;
            try (var in = Files.newInputStream(copy)) {
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) sha.update(buffer,0,n);
                digest = HexFormat.of().formatHex(sha.digest());
            }
            Path release = releases.resolve(digest);
            if (Files.exists(release,LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(release) || !Files.isRegularFile(release.resolve("app.jar"),LinkOption.NOFOLLOW_LINKS) ||
                    Files.mismatch(copy,release.resolve("app.jar")) != -1)
                    throw new IOException("已有 release 不匹配，拒绝覆盖");
            } else {
                try { Files.setPosixFilePermissions(copy,EnumSet.of(PosixFilePermission.OWNER_READ)); }
                catch (UnsupportedOperationException ignored) { if (!copy.toFile().setReadOnly()) throw new IOException("无法保护 release"); }
                Files.move(staged,release,StandardCopyOption.ATOMIC_MOVE);
            }
            installLauncher(state);
            Path temporary = state.resolve(".current-" + UUID.randomUUID());
            try {
                Files.createSymbolicLink(temporary,Path.of("releases",digest));
                Files.move(temporary,state.resolve("current"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } finally {
            Files.deleteIfExists(staged.resolve("app.jar"));
            Files.deleteIfExists(staged);
        }
    }

    private void installLauncher(Path state) throws IOException {
        Path bin = state.resolve("bin"); Json.privateDirectory(bin);
        Path temp = Files.createTempFile(bin,".omp-issue-", ".tmp");
        try {
            Files.writeString(temp,"#!/bin/sh\nset -eu\nBASE=$(CDPATH= cd -- \"$(dirname -- \"$0\")/..\" && pwd -P)\nexec java -jar \"$BASE/current/app.jar\" \"$@\"\n");
            try { Files.setPosixFilePermissions(temp,EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE)); }
            catch (UnsupportedOperationException ignored) { if (!temp.toFile().setExecutable(true,true)) throw new IOException("无法设置启动器权限"); }
            Files.move(temp,bin.resolve("omp-issue"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
