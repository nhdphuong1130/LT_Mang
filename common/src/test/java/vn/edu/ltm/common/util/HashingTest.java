package vn.edu.ltm.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HashingTest {
    @TempDir
    Path directory;

    @Test
    void hashesEmptyFileWithStandardSha256() throws Exception {
        Path file = Files.createFile(directory.resolve("empty"));

        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hashing.sha256(file));
    }

    @Test
    void hashesKnownContentToLowercaseHex() throws Exception {
        Path file = Files.writeString(directory.resolve("abc"), "abc");

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256(file));
    }

    @Test
    void hashesContentAcrossMultipleReadBuffers() throws Exception {
        Path file = Files.writeString(directory.resolve("large"), "a".repeat(10_000));

        assertEquals("27dd1f61b867b6a0f6e9d8a41c43231de52107e53ae424de8f847b821db4b711", Hashing.sha256(file));
    }

    @Test
    void propagatesMissingFileFailure() {
        assertThrows(IOException.class, () -> Hashing.sha256(directory.resolve("missing")));
    }
}
