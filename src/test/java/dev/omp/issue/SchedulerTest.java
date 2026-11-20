package dev.omp.issue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {
    @TempDir Path temp;
    private Config config(int concurrency,int attempts) throws Exception {
        Path repo=temp.resolve("repo");Files.createDirectories(repo);
        Runner.git(repo,"init","--initial-branch=main");
        Runner.git(repo,"remote","add","origin","https://github.com/owner/video.git");
        var p=new Config.Project("video",repo,"owner/video","main",List.of(),List.of(),false);
        return new Config(temp.resolve("state"),60,concurrency,attempts,1,new Config.Omp("omp","",60,1),Map.of("video",p));
    }
    private JsonNode issue(int number) throws IOException {
        return Json.MAPPER.readTree("{\"number\":"+number+",\"title\":\"Reconnect\",\"body\":\"Keep working\",\"state\":\"open\",\"labels\":[{\"name\":\"agent:ready\"}]}");
    }
    @Test void restartAfterPrCreationReportsExactlyOnePrWithoutRerunningAgent() throws Exception {
        Config c=config(1,3);var project=c.project("video");var store=new Store(c.stateDir());
        var job=new Store.Job(project,issue(9));job.status="running";job.stage="PUBLISH";job.claimSha="ours";job.summary="Tests passed";
        store.save(job);
        AtomicInteger reported=new AtomicInteger(),created=new AtomicInteger();
        var gh=new GitHub(c) {
            @Override public List<JsonNode> ready(Config.Project p) { return List.of(); }
            @Override public boolean claim(Config.Project p,int n,String sha) { return sha.equals("ours"); }
            @Override public void ensureLabels(Config.Project p) {}
            @Override public void labels(Config.Project p,int n,String status) {}
            @Override public JsonNode findPr(Config.Project p,String branch) throws Exception {
                return Json.MAPPER.readTree("{\"state\":\"open\",\"html_url\":\"https://github.com/owner/video/pull/12\"}");
            }
            @Override public JsonNode createPr(Config.Project p,Store.Job j) { created.incrementAndGet();throw new AssertionError("duplicate PR"); }
            @Override public void result(Config.Project p,Store.Job j,String status) { assertEquals("done",status);reported.incrementAndGet(); }
        };
        new Scheduler(c,gh,new Worker(c)).scan();
        var recovered=store.get(project.repo(),9);
        assertEquals("done",recovered.status);assertEquals("https://github.com/owner/video/pull/12",recovered.prUrl);
        assertTrue(recovered.reported);assertTrue(recovered.cleaned);assertEquals(1,reported.get());assertEquals(0,created.get());
        new Scheduler(c,gh,new Worker(c)).scan();
        assertEquals(1,reported.get());assertEquals(0,created.get());
    }
    @Test void parallelTasksRespectConfiguredLimitAndDoNotStealForeignClaims() throws Exception {
        Config c=config(2,3);AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger();
        var gh=new GitHub(c) {
            @Override public List<JsonNode> ready(Config.Project p) throws Exception {
                return List.of(SchedulerTest.this.issue(1),SchedulerTest.this.issue(2),SchedulerTest.this.issue(3),SchedulerTest.this.issue(4));
            }
            @Override public JsonNode issue(Config.Project p,int n) throws Exception { return SchedulerTest.this.issue(n); }
            @Override public String prepareClaim(Config.Project p,String owner) { return owner; }
            @Override public boolean claim(Config.Project p,int n,String sha) throws Exception {
                int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);
                try {Thread.sleep(100);return false;} finally {active.decrementAndGet();}
            }
        };
        new Scheduler(c,gh,new Worker(c)).scan();
        assertEquals(2,maximum.get());
        var jobs=new Store(c.stateDir()).jobs();assertEquals(4,jobs.size());
        assertTrue(jobs.stream().allMatch(j -> j.status.equals("foreign")));
    }
    @Test void transientFailuresBackOffThenStopAtRetryLimitAndCanBeExplicitlyRetried() throws Exception {
        Config c=config(1,2);var project=c.project("video");AtomicInteger attempts=new AtomicInteger();
        var gh=new GitHub(c) {
            @Override public List<JsonNode> ready(Config.Project p) throws Exception { return List.of(SchedulerTest.this.issue(3)); }
            @Override public JsonNode issue(Config.Project p,int n) throws Exception { attempts.incrementAndGet();throw new IOException("network down"); }
            @Override public void ensureLabels(Config.Project p) {}
            @Override public void labels(Config.Project p,int n,String status) {}
            @Override public void result(Config.Project p,Store.Job job,String status) { assertEquals("failed",status); }
        };
        var scheduler=new Scheduler(c,gh,new Worker(c));var store=new Store(c.stateDir());
        scheduler.scan();var first=store.get(project.repo(),3);
        assertEquals("retry",first.status);assertTrue(first.nextAt>System.currentTimeMillis());
        scheduler.scan();assertEquals(1,attempts.get());
        first.nextAt=0;store.save(first);scheduler.scan();
        assertEquals("failed",store.get(project.repo(),3).status);assertEquals(2,attempts.get());
        scheduler.retry(project,3);var retried=store.get(project.repo(),3);
        assertEquals("pending",retried.status);assertEquals(0,retried.attempts);
    }
    @Test void issueResultWritebackFailureDoesNotRerunDevelopment() throws Exception {
        Config c=config(1,2);var project=c.project("video");var store=new Store(c.stateDir());
        var job=new Store.Job(project,issue(7));job.status="done";job.stage="PUBLISH";job.prUrl="https://github.com/owner/video/pull/8";store.save(job);
        AtomicInteger reports=new AtomicInteger();
        var gh=new GitHub(c) {
            @Override public List<JsonNode> ready(Config.Project p) { return List.of(); }
            @Override public void ensureLabels(Config.Project p) {}
            @Override public void result(Config.Project p,Store.Job j,String status) throws Exception {
                if (reports.incrementAndGet()==1) throw new IOException("GitHub temporarily unavailable");
            }
        };
        var scheduler=new Scheduler(c,gh,new Worker(c));scheduler.scan();
        var pending=store.get(project.repo(),7);assertEquals("done",pending.status);assertFalse(pending.reported);
        pending.nextAt=0;store.save(pending);scheduler.scan();
        var done=store.get(project.repo(),7);assertTrue(done.reported);assertTrue(done.cleaned);assertEquals(0,done.attempts);
    }
}
