package dev.omp.issue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class GitHubTest {
    @TempDir Path temp;
    Config.Project project() { return new Config.Project("video",temp,"owner/video","main",List.of(),List.of(),false); }
    Config config() { return new Config(temp,60,2,3,1,new Config.Omp("omp","",60,1),Map.of("video",project())); }
    @Test void simultaneousMachinesCannotBothClaimTheSameIssue() throws Exception {
        var refs=new ConcurrentHashMap<String,String>();
        var api=new GitHub(config()) {
            @Override protected JsonNode api(String method,String endpoint,Object body) throws Exception {
                if (method.equals("GET")) {
                    String sha=refs.get("ref");
                    if (sha==null) throw new IOException("HTTP 404");
                    return Json.MAPPER.valueToTree(Map.of("object",Map.of("sha",sha)));
                }
                String sha=((Map<?,?>)body).get("sha").toString();
                if (refs.putIfAbsent("ref",sha)!=null) throw new IOException("HTTP 422 ref exists");
                return Json.MAPPER.valueToTree(Map.of("object",Map.of("sha",sha)));
            }
        };
        var gate=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(() -> {gate.await();return api.claim(project(),9,"owner-one");});
            var two=pool.submit(() -> {gate.await();return api.claim(project(),9,"owner-two");});gate.countDown();
            assertNotEquals(one.get(),two.get());
        }
        assertTrue(api.claim(project(),9,refs.get("ref")));
        assertFalse(api.claim(project(),9,"another-machine"));
    }
    @Test void lostPrCreationResponseIsRecoveredWithoutCreatingAnotherPr() throws Exception {
        var creates=new AtomicInteger();var persisted=new AtomicBoolean();
        JsonNode pr=Json.MAPPER.readTree("{\"html_url\":\"https://github.com/owner/video/pull/11\",\"head\":{\"ref\":\"omp/issue-9\"}}");
        var github=new GitHub(config()) {
            @Override protected JsonNode api(String method,String endpoint,Object body) throws Exception {
                if (method.equals("POST")) {creates.incrementAndGet();persisted.set(true);throw new IOException("connection reset after server commit");}
                return persisted.get() ? Json.MAPPER.valueToTree(List.of(pr)) : Json.MAPPER.createArrayNode();
            }
        };
        var job=new Store.Job(project(),Json.MAPPER.readTree("{\"number\":9,\"title\":\"Fix\"}"));job.summary="Tests passed";
        assertEquals("https://github.com/owner/video/pull/11",github.createPr(project(),job).path("html_url").asText());
        assertEquals("https://github.com/owner/video/pull/11",github.createPr(project(),job).path("html_url").asText());
        assertEquals(1,creates.get());
    }
    @Test void issueTextIsPostedAsDataNotInterpolatedIntoACommand() throws Exception {
        var saved=new AtomicReference<JsonNode>();
        var github=new GitHub(config()) {
            @Override public void ensureLabels(Config.Project p) {}
            @Override protected JsonNode api(String method,String endpoint,Object body) {
                saved.set(Json.MAPPER.valueToTree(body));return Json.MAPPER.valueToTree(Map.of("number",3));
            }
        };
        String text="增加重连功能 $(touch /tmp/pwned); `rm -rf /`";
        github.create(project(),text,"- [ ] 网络恢复后自动重新连接");
        assertEquals(text,saved.get().path("title").asText());
        assertTrue(saved.get().path("body").asText().contains("网络恢复后自动重新连接"));
        assertEquals("agent:ready",saved.get().path("labels").get(0).asText());
    }
}
