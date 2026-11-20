package dev.omp.issue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RunnerTest {
    @TempDir Path temp;
    @Test void preservesArgumentBoundariesWithoutInvokingShell() throws Exception {
        String input="$(touch should-not-exist); \"quotes\" and spaces";
        var result=Runner.run(List.of("/usr/bin/printf","%s",input),temp,temp.resolve("command.log"),5,Map.of());
        assertEquals(input,result.requireSuccess());assertFalse(Files.exists(temp.resolve("should-not-exist")));
    }
    @Test void keepsFullLogsWhileBoundingReturnedOutput() throws Exception {
        Path log=temp.resolve("large.log");
        var result=Runner.run(List.of("/bin/sh","-c","head -c 200000 /dev/zero | tr '\\000' x"),temp,log,5,Map.of());
        assertEquals(200000,Files.size(log));assertEquals(131072,result.output().length());assertEquals(0,result.exit());
    }
    @Test void timeoutKillsChildProcessesAndLeavesRecoveryJournalClean() throws Exception {
        Path log=temp.resolve("slow.log"),pid=temp.resolve("child.pid");
        var result=Runner.run(List.of("/bin/sh","-c","sleep 60 & echo $! > child.pid; wait"),temp,log,1,Map.of());
        assertEquals(124,result.exit());
        long child=Long.parseLong(Files.readString(pid).strip());
        assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
        assertFalse(Files.exists(Path.of(log+".pid.json")));
        assertThrows(IOException.class,result::requireSuccess);
    }
}
