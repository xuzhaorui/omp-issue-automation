package dev.omp.issue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.*;
import java.util.*;

class WorkerTest {
    @TempDir Path temp;

    @Test void refusesProjectsWithoutTrustedTests() throws Exception {
        Path base = Files.createDirectory(temp.resolve("base"));
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Config.Project project = new Config.Project("p", base, "owner/repo", "main", List.of(), List.of(), false);
        Config config = new Config(temp, 60, 1, 2, 1, new Config.Omp("missing", "", 10, 1), Map.of());
        IOExceptionAssert.assertThrows(() -> new Worker(config).run(project, Json.MAPPER.readTree("{\"title\":\"hello\"}"), temp.resolve("job"), workspace), "test");
    }

    @Test void guardRejectsEscapesAndLinksAndAllowsWorkspaceEdits() throws Exception {
        Path node = Path.of("/usr/local/bin/node");
        if (!Files.isExecutable(node)) node = Path.of("/opt/homebrew/bin/node");
        if (!Files.isExecutable(node)) {
            String path = System.getenv("PATH");
            if (path != null) for (String dir : path.split(":")) if (Files.isExecutable(Path.of(dir, "node"))) { node = Path.of(dir, "node"); break; }
        }
        assumeTrue(Files.isExecutable(node));
        Path root = Files.createDirectory(temp.resolve("root"));
        Path outside = Files.writeString(temp.resolve("secret"), "secret");
        Files.createSymbolicLink(root.resolve("link"), outside);
        Path extension = temp.resolve("guard.mjs");
        try (var in = Worker.class.getResourceAsStream("/guard.mjs")) { assertNotNull(in); Files.copy(in, extension); }
        String js = """
            import { pathToFileURL } from 'node:url';
            const tools = {}, events = {};
            const z = { string: () => ({}), object: x => x };
            let active=[];
            const pi = { zod:z, on:(n,f)=>events[n]=f, registerTool:t=>tools[t.name]=t,
              setActiveTools:async a=>{active=a}, getActiveTools:()=>active };
            (await import(pathToFileURL(process.argv[1]))).default(pi);
            await events.session_start();
            const call = async (name, params) => tools[name].execute('id',params);
            await call('workspace_write',{path:'ok.txt',content:'ok'});
            if ((await call('workspace_read',{path:'ok.txt'})).content[0].text !== 'ok') throw Error('bad read');
            await call('workspace_mkdir',{path:'new/package'});
            await call('workspace_write',{path:'new/package/file',content:'new'});
            await call('workspace_delete',{path:'new/package/file'});
            await call('workspace_delete',{path:'new/package'});
            let rejected=false; try { await call('workspace_mkdir',{path:'link/subdir'}); } catch { rejected=true; }
            if (!rejected) throw Error('mkdir followed link');
            for (const p of ['../secret','file:///etc/passwd','link','.env.local','.git/config','.claude/settings.json']) {
              let rejected=false; try { await call('workspace_read',{path:p}); } catch { rejected=true; }
              if (!rejected) throw Error('accepted '+p);
            }
            if (!(await events.tool_call({toolName:'bash'})).block) throw Error('bash allowed');
            """;
        ProcessBuilder pb = new ProcessBuilder(node.toString(), "--input-type=module", "-e", js, extension.toString()).redirectErrorStream(true);
        pb.environment().put("OMP_WORKSPACE", root.toString());
        pb.environment().put("OMP_GUARD_MARKER", temp.resolve("marker").toString());
        pb.environment().put("OMP_GUARD_NONCE", "nonce");
        Process p = pb.start(); String output = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), output);
        assertEquals("secret", Files.readString(outside));
    }

    @Test void failedTestsTriggerBoundedRepairAndRecordCost() throws Exception {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        assumeTrue(os.contains("mac") && Files.isExecutable(Path.of("/usr/bin/sandbox-exec")) || os.contains("linux") && Files.isExecutable(Path.of("/usr/bin/bwrap")));
        Path base = Files.createDirectory(temp.resolve("base"));
        Path job = Files.createDirectory(temp.resolve("job"));
        Path root = Files.createDirectory(job.resolve("worktree"));
        Path fake = Files.writeString(temp.resolve("fake-omp"), """
            #!/bin/sh
            for a do case "$a" in --session-dir=*) session=${a#--session-dir=} ;; esac; done
            printf 'ready:%s\\nused:%s\\n' "$OMP_GUARD_NONCE" "$OMP_GUARD_NONCE" > "$OMP_GUARD_MARKER"
            echo '{}' > "$session/session.jsonl"
            mkdir -p "$session/session"
            echo '{}' > "$session/session/__advisor.jsonl"
            echo '{"type":"message_end","message":{"role":"assistant","usage":{"input":10,"cost":{"total":0.2}},"content":[{"type":"text","text":"edited"}]}}'
            """);
        assertTrue(fake.toFile().setExecutable(true));
        Config.Project project = new Config.Project("p", base, "owner/repo", "main", List.of(), List.of(List.of("/bin/sh", "-c", "exit 7")), false);
        Config config = new Config(temp, 60, 1, 2, 1, new Config.Omp(fake.toString(), "", 20, 1), Map.of());
        Worker.Result result = new Worker(config).run(project, Json.MAPPER.readTree("{\"title\":\"$(touch /tmp/should-not-run)\"}"), job, root);
        assertFalse(result.testsPassed());
        assertEquals(2, Json.read(job.resolve("tests.json")).size());
        assertEquals(7, Json.read(job.resolve("tests.json")).get(0).path("exit").asInt());
        assertEquals(0.4, Json.read(job.resolve("usage.json")).path("totalCost").asDouble(), 0.00001);
        Worker.Result retried = new Worker(config).run(project, Json.MAPPER.readTree("{}"), job, root);
        assertFalse(retried.testsPassed());
        assertEquals(4, Json.read(job.resolve("tests.json")).size());
        assertEquals(0.8, Json.read(job.resolve("usage.json")).path("totalCost").asDouble(), 0.00001);
    }

    @Test void missingGuardFailsClosedButPreservesUnknownCost() throws Exception {
        Path base = Files.createDirectory(temp.resolve("base"));
        Path root = Files.createDirectory(temp.resolve("workspace"));
        Path fake = Files.writeString(temp.resolve("fake-omp"), "#!/bin/sh\necho '{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"usage\":{\"input\":4}}}'\n");
        assertTrue(fake.toFile().setExecutable(true));
        Config.Project project = new Config.Project("p", base, "owner/repo", "main", List.of(), List.of(List.of("true")), false);
        Config config = new Config(temp, 60, 1, 1, 1, new Config.Omp(fake.toString(), "", 20, 1), Map.of());
        Path job = temp.resolve("job");
        IOExceptionAssert.assertThrows(() -> new Worker(config).run(project, Json.MAPPER.readTree("{}"), job, root), "guard");
        assertTrue(Json.read(job.resolve("usage.json")).path("totalCost").isNull());
    }

    @Test void providerFailuresAreNotMisreportedAsMissingGuardTools() throws Exception {
        Path base = Files.createDirectory(temp.resolve("base"));
        Path root = Files.createDirectory(temp.resolve("workspace"));
        Path fake = Files.writeString(temp.resolve("fake-omp"), "#!/bin/sh\necho 'provider quota exceeded'\nexit 23\n");
        assertTrue(fake.toFile().setExecutable(true));
        Config.Project project = new Config.Project("p", base, "owner/repo", "main", List.of(), List.of(List.of("true")), false);
        Config config = new Config(temp, 60, 1, 1, 1, new Config.Omp(fake.toString(), "", 20, 1), Map.of());
        IOExceptionAssert.assertThrows(() -> new Worker(config).run(project, Json.MAPPER.readTree("{}"), temp.resolve("job"), root), "provider quota exceeded");
    }

    @Test void sandboxCannotReadOutsideOrWriteOutside() throws Exception {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        assumeTrue(os.contains("mac") && Files.isExecutable(Path.of("/usr/bin/sandbox-exec")) || os.contains("linux") && Files.isExecutable(Path.of("/usr/bin/bwrap")));
        Path root = Files.createDirectory(temp.resolve("work"));
        Path secret = Files.writeString(temp.resolve("secret"), "sensitive").toRealPath();
        Path escaped = temp.resolve("escaped").toAbsolutePath();
        Runner.Result result = Sandbox.run(List.of("/bin/sh", "-c", "test ! -r \"$1\" && (echo bad > \"$2\" 2>/dev/null || :) && echo allowed > inside", "sh", secret.toString(), escaped.toString()), root, temp.resolve("scratch"), temp.resolve("sandbox.log"), 20);
        assertEquals(0, result.exit(), result.output());
        assertFalse(Files.exists(escaped));
        assertEquals("allowed\n", Files.readString(root.resolve("inside")));
    }

    private static final class IOExceptionAssert {
        interface Operation { void run() throws Exception; }
        static void assertThrows(Operation op, String message) {
            Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, op::run);
            assertTrue(e.getMessage().toLowerCase(Locale.ROOT).contains(message), e.toString());
        }
    }
}
