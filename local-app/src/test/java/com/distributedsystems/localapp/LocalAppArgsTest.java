package com.distributedsystems.localapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalAppArgsTest {

    @TempDir
    Path dir;

    private String input;
    private String output;

    @BeforeEach
    void createInputFile() throws IOException {
        Path inputFile = dir.resolve("input.txt");
        Files.writeString(inputFile, "http://example.com/a.png\n");
        input = inputFile.toString();
        output = dir.resolve("output.html").toString();
    }

    @Test
    void parsesFourArguments() {
        LocalAppArgs args = LocalAppArgs.parse(new String[] {input, output, "3", "10"});

        assertEquals(Path.of(input), args.inputFile());
        assertEquals(Path.of(output), args.outputFile());
        assertEquals(3, args.managerClientsN());
        assertEquals(10, args.workerFilesN());
        assertFalse(args.terminate());
    }

    @Test
    void parsesOptionalTerminate() {
        assertTrue(LocalAppArgs.parse(new String[] {input, output, "3", "10", "terminate"}).terminate());
    }

    @Test
    void rejectsWrongArgumentCount() {
        assertThrows(IllegalArgumentException.class, () -> LocalAppArgs.parse(new String[] {input, output, "3"}));
        assertThrows(IllegalArgumentException.class,
                () -> LocalAppArgs.parse(new String[] {input, output, "3", "10", "terminate", "extra"}));
        assertThrows(IllegalArgumentException.class, () -> LocalAppArgs.parse(null));
    }

    @Test
    void rejectsMissingInputFile() {
        String missing = dir.resolve("nope.txt").toString();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LocalAppArgs.parse(new String[] {missing, output, "3", "10"}));
        assertTrue(e.getMessage().contains("Input file not found"));
    }

    @Test
    void rejectsOutputInMissingDirectory() {
        String badOutput = dir.resolve("no-such-dir").resolve("out.html").toString();
        assertThrows(IllegalArgumentException.class, () -> LocalAppArgs.parse(new String[] {input, badOutput, "3", "10"}));
    }

    @Test
    void rejectsNonPositiveOrNonNumericCounts() {
        for (String bad : new String[] {"0", "-1", "abc", "1.5", ""}) {
            assertThrows(IllegalArgumentException.class,
                    () -> LocalAppArgs.parse(new String[] {input, output, bad, "10"}), "manager-clients-n=" + bad);
            assertThrows(IllegalArgumentException.class,
                    () -> LocalAppArgs.parse(new String[] {input, output, "3", bad}), "worker-files-n=" + bad);
        }
    }

    @Test
    void rejectsUnknownFifthArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> LocalAppArgs.parse(new String[] {input, output, "3", "10", "terminte"}));
    }
}
