package dev.omp.issue;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class Runner {
    private Runner() {}
    public record Result(int exit, String output) {
        public String requireSuccess() throws IOException {
            if (exit != 0) throw new IOException("命令失败 (" + exit + "): " + output);
            return output;
        }
    }
    public static Result run(List<String> argv, Path cwd, Path log, int timeoutSeconds,
                             Map<String,String> environment) throws IOException, InterruptedException {
        if (argv.isEmpty() || timeoutSeconds < 1) throw new IllegalArgumentException("命令或超时无效");
        var builder = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true);
        environment.forEach((k,v) -> { if (v == null) builder.environment().remove(k); else builder.environment().put(k,v); });
        if (log != null) Json.privateDirectory(log.toAbsolutePath().getParent());
        Process process = builder.start();
        Path pidFile = log == null ? null : Path.of(log + ".pid.json");
        Thread shutdown = new Thread(() -> terminate(process.toHandle()), "omp-process-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);
        Tail tail = new Tail(131072);
        try {
            if (pidFile != null) Json.write(pidFile, Map.of("pid",process.pid(),"started",process.info().startInstant().orElse(Instant.EPOCH).toString()));
            try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> output = readers.submit(() -> {
                    try (var input = process.getInputStream(); var sink = log == null ? OutputStream.nullOutputStream() :
                        Files.newOutputStream(log, StandardOpenOption.CREATE,StandardOpenOption.APPEND)) {
                        byte[] buffer = new byte[8192]; int n;
                        while ((n = input.read(buffer)) != -1) { sink.write(buffer,0,n); tail.add(buffer,n); }
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                });
                boolean ended;
                try { ended = process.waitFor(timeoutSeconds,TimeUnit.SECONDS); }
                catch (InterruptedException e) { terminate(process.toHandle()); throw e; }
                if (!ended) terminate(process.toHandle());
                try { output.get(10,TimeUnit.SECONDS); }
                catch (ExecutionException e) { throw new IOException("读取进程输出失败",e.getCause()); }
                catch (TimeoutException e) { terminate(process.toHandle()); throw new IOException("子进程输出未关闭",e); }
                return new Result(ended ? process.exitValue() : 124,tail.text());
            }
        } finally {
            terminate(process.toHandle());
            if (pidFile != null) Files.deleteIfExists(pidFile);
            try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { }
        }
    }
    public static String git(Path cwd,String... args) throws IOException,InterruptedException {
        var argv = new ArrayList<String>(); argv.add("git");
        argv.add("-c"); argv.add("core.hooksPath=/dev/null");
        argv.addAll(List.of("-c","credential.helper=","-c","credential.helper=!gh auth git-credential"));
        argv.addAll(List.of(args));
        return run(argv,cwd,null,120,Map.of("GIT_TERMINAL_PROMPT","0")).requireSuccess().trim();
    }
    static void recoverProcesses(Path taskDirectory) throws IOException {
        if (!Files.isDirectory(taskDirectory)) return;
        try (var paths = Files.walk(taskDirectory)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".pid.json")).toList()) {
                var node = Json.read(path);
                ProcessHandle.of(node.path("pid").asLong()).ifPresent(process -> {
                    if (process.info().startInstant().map(Instant::toString).orElse("").equals(node.path("started").asText())) terminate(process);
                });
                Files.delete(path);
            }
        }
    }
    private static void terminate(ProcessHandle process) {
        List<ProcessHandle> children = process.descendants().toList();
        for (var child : children.reversed()) child.destroyForcibly();
        if (process.isAlive()) process.destroyForcibly();
        for (var child : children) { try { child.onExit().get(5,TimeUnit.SECONDS); } catch (Exception ignored) { } }
        try { process.onExit().get(5,TimeUnit.SECONDS); } catch (Exception ignored) { }
    }
    private static final class Tail {
        private final byte[] data; private int position, size;
        Tail(int capacity) { data = new byte[capacity]; }
        synchronized void add(byte[] buffer,int n) {
            if (n >= data.length) { System.arraycopy(buffer,n-data.length,data,0,data.length); position=0;size=data.length;return; }
            int first = Math.min(n,data.length-position);
            System.arraycopy(buffer,0,data,position,first);
            System.arraycopy(buffer,first,data,0,n-first);
            position=(position+n)%data.length; size=Math.min(data.length,size+n);
        }
        synchronized String text() {
            byte[] result = new byte[size]; int start=(position-size+data.length)%data.length;
            int first=Math.min(size,data.length-start);
            System.arraycopy(data,start,result,0,first);System.arraycopy(data,0,result,first,size-first);
            return new String(result,StandardCharsets.UTF_8);
        }
    }
}
