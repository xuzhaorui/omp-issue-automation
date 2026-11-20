package dev.omp.issue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

public final class Store {
    private final Path root;
    public Store(Path root) throws IOException { this.root=root;Json.privateDirectory(root.resolve("jobs")); }
    public static final class Job {
        public String project,repo,owner,claimSha="",branch,title,stage="CLAIMING",status="pending",prUrl="",summary="",error="";
        public int issue,attempts;
        public long nextAt;
        public boolean reported,cleaned;
        public JsonNode snapshot;
        public Job() {}
        public Job(Config.Project p,JsonNode issue) {
            project=p.name();repo=p.repo();this.issue=issue.path("number").asInt();
            if (this.issue<1) throw new IllegalArgumentException("无效 Issue 编号");
            owner=UUID.randomUUID().toString();branch="omp/issue-"+this.issue;
            title=issue.path("title").asText();snapshot=issue.deepCopy();
        }
        public boolean terminal() { return status.equals("done") || status.equals("failed") || status.equals("foreign"); }
    }
    public Path directory(Job job) { return root.resolve("jobs").resolve(key(job.repo,job.issue)); }
    public synchronized Job get(String repo,int issue) throws IOException {
        Path path=root.resolve("jobs").resolve(key(repo,issue)).resolve("job.json");
        return Files.exists(path) ? Json.MAPPER.treeToValue(Json.read(path),Job.class) : null;
    }
    public synchronized void save(Job job) throws IOException { Json.write(directory(job).resolve("job.json"),job); }
    public synchronized List<Job> jobs() throws IOException {
        List<Job> jobs=new ArrayList<>();
        try (var files=Files.walk(root.resolve("jobs"))) {
            for (Path file : files.filter(p -> p.getFileName().toString().equals("job.json")).toList()) {
                if (Files.isRegularFile(file)) jobs.add(Json.MAPPER.treeToValue(Json.read(file),Job.class));
            }
        }
        jobs.sort(Comparator.comparing((Job j) -> j.repo).thenComparingInt(j -> j.issue));
        return jobs;
    }
    public static Lease lock(Path state) throws IOException {
        Json.privateDirectory(state);
        var channel=FileChannel.open(state.resolve("scheduler.lock"),CREATE,WRITE);
        try {
            FileLock lock;
            try { lock=channel.tryLock(); } catch (OverlappingFileLockException e) { lock=null; }
            if (lock==null) throw new IOException("调度器正在执行任务或更新，请稍后重试");
            return new Lease(channel,lock);
        } catch (IOException|RuntimeException e) { channel.close();throw e; }
    }
    public record Lease(FileChannel channel,FileLock lock) implements AutoCloseable {
        @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
    }
    private static String key(String repo,int issue) {
        if (!repo.matches("[A-Za-z0-9][A-Za-z0-9_-]*/[A-Za-z0-9_.-]+") ||
            Set.of(".","..").contains(repo.substring(repo.indexOf('/')+1)) || issue<1)
            throw new IllegalArgumentException("无效仓库或编号");
        // Owner and repo are nested to avoid ambiguous '-' concatenation collisions.
        return repo+"/"+issue;
    }
}
