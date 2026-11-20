package dev.omp.issue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentTest {
    @TempDir Path temp;
    private Config config() {
        return new Config(temp.resolve("state"),60,1,3,60,new Config.Omp("omp","",60,5),Map.of());
    }
    @Test void installsIndependentContentAddressedReleasesAndSwitchesAtomically() throws Exception {
        Config config = config(); Deployment deployment = new Deployment(config);
        Path jar = temp.resolve("build.jar"); Files.writeString(jar,"first");
        deployment.install(jar);
        Path current = config.stateDir().resolve("current");
        assertTrue(Files.isSymbolicLink(current));
        Path first = current.toRealPath();
        assertEquals("first",Files.readString(current.resolve("app.jar")));
        assertTrue(Files.isExecutable(config.stateDir().resolve("bin/omp-issue")));
        Files.writeString(jar,"second");
        assertEquals("first",Files.readString(current.resolve("app.jar")));
        deployment.install(jar);
        assertNotEquals(first,current.toRealPath());
        assertEquals("first",Files.readString(first.resolve("app.jar")));
        assertEquals("second",Files.readString(current.resolve("app.jar")));
        assertEquals(Files.readSymbolicLink(current).toString(),deployment.current());
    }
    @Test void rejectsInstallAndUpdateWhileSchedulerHoldsLock() throws Exception {
        Config config = config(); Deployment deployment = new Deployment(config);
        Path jar = temp.resolve("build.jar"); Files.writeString(jar,"first");
        deployment.install(jar);
        String before = deployment.current();
        try (Store.Lease ignored = Store.lock(config.stateDir())) {
            assertThrows(Exception.class,() -> deployment.install(jar));
            assertThrows(Exception.class,() -> deployment.update(temp));
        }
        assertEquals(before,deployment.current());
    }
    @Test void failedUpdateAndInvalidInstallLeaveCurrentUntouched() throws Exception {
        Config config = config(); Deployment deployment = new Deployment(config);
        Path jar = temp.resolve("build.jar"); Files.writeString(jar,"first");
        deployment.install(jar); String before = deployment.current();
        assertThrows(Exception.class,() -> deployment.update(temp));
        assertThrows(Exception.class,() -> deployment.install(temp.resolve("missing.jar")));
        assertEquals(before,deployment.current());
        assertEquals("first",Files.readString(config.stateDir().resolve("current/app.jar")));
    }
}
