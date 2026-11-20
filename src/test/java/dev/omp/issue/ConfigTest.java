package dev.omp.issue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {
    @TempDir Path temp;
    private Path config(String extra,String projects) throws IOException {
        Path path=temp.resolve("projects.yml");
        Files.writeString(path,"stateDir: '"+temp.resolve("state")+"'\n"+extra+"projects:\n"+projects);
        return path;
    }
    private String project(String name,String alias) {
        return "  "+name+":\n    path: '"+temp+"'\n    repo: owner/"+name+"\n    aliases: ["+alias+"]\n    tests: [[mvn, test]]\n";
    }
    @Test void resolvesAliasesAcrossProjectsWithoutGuessingPaths() throws Exception {
        var c=Config.load(config("pollSeconds: 15\nconcurrency: 3\n",project("video","视频")+project("matching","match")));
        assertEquals("owner/video",c.project("视频").repo());
        assertEquals("matching",c.project("MATCH").name());
        assertEquals(15,c.pollSeconds());assertEquals(3,c.concurrency());
        assertThrows(IllegalArgumentException.class,() -> c.project("missing"));
    }
    @Test void rejectsAmbiguousAliasesAndUnsafeCommandShapes() throws Exception {
        Path duplicate=config("",project("video","same")+project("matching","same"));
        assertThrows(IOException.class,() -> Config.load(duplicate));
        Path shell=config("","  video:\n    path: '"+temp+"'\n    repo: owner/video\n    tests: ['mvn test; touch /tmp/pwned']\n");
        assertThrows(IOException.class,() -> Config.load(shell));
    }
    @Test void rejectsInvalidLimitsAndMissingPaths() throws Exception {
        assertThrows(IOException.class,() -> Config.load(config("concurrency: 0\n",project("video","vid"))));
        assertThrows(IOException.class,() -> Config.load(config("pollSeconds: 0.5\n",project("video","vid"))));
        assertThrows(IOException.class,() -> Config.load(config("","  video:\n    repo: owner/video\n")));
    }
    @Test void cliRejectsUnknownOrDuplicateArgumentsBeforeAnySideEffect() {
        var bytes=new ByteArrayOutputStream();var output=new PrintStream(bytes);
        assertEquals(1,Main.run(new String[]{"create","--shell","touch /tmp/pwned"},output,output));
        assertEquals(1,Main.run(new String[]{"create","--project","video","--project","matching"},output,output));
        assertEquals(0,Main.run(new String[]{"--help"},output,output));
        assertTrue(bytes.toString().contains("create --project"));
    }
}
