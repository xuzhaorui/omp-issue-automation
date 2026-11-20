package dev.omp.issue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StoreTest {
    @TempDir Path temp;
    @Test void tasksSurviveRestartAndRepositoryNamesCannotCollide() throws Exception {
        var first=new Store(temp);
        var p1=new Config.Project("one",temp,"a-b/c","main",List.of(),List.of(),false);
        var p2=new Config.Project("two",temp,"a/b-c","main",List.of(),List.of(),false);
        var issue=Json.MAPPER.readTree("{\"number\":7,\"title\":\"Reconnect\",\"body\":\"Keep retry state\"}");
        var j1=new Store.Job(p1,issue);j1.status="retry";j1.attempts=2;j1.nextAt=123456;
        first.save(j1);first.save(new Store.Job(p2,issue));
        var restarted=new Store(temp);
        assertEquals(2,restarted.jobs().size());
        var recovered=restarted.get(p1.repo(),7);
        assertEquals(j1.owner,recovered.owner);assertEquals(2,recovered.attempts);
        assertEquals("retry",recovered.status);assertEquals(123456,recovered.nextAt);
        assertEquals("Keep retry state",recovered.snapshot.path("body").asText());
        assertNotEquals(recovered.owner,restarted.get(p2.repo(),7).owner);
    }
    @Test void schedulerAndUpdaterCannotOwnStateAtSameTime() throws Exception {
        try (var lease=Store.lock(temp)) { assertThrows(IOException.class,() -> Store.lock(temp)); }
        try (var lease=Store.lock(temp)) { assertTrue(lease.lock().isValid()); }
    }
    @Test void corruptJournalIsNotSilentlyDiscarded() throws Exception {
        var store=new Store(temp);
        var project=new Config.Project("one",temp,"a/b","main",List.of(),List.of(),false);
        var job=new Store.Job(project,Json.MAPPER.readTree("{\"number\":1}"));store.save(job);
        Files.writeString(store.directory(job).resolve("job.json"),"{corrupt");
        assertThrows(IOException.class,store::jobs);
    }
}
