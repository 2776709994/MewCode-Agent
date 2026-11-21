package com.mewcode.filehistory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileHistoryTest {
    @Test
    void rewindRestoresTheContentsAtTheEndOfTheSelectedTurn(@TempDir Path root) throws Exception {
        var history = new FileHistory(root.toString(), "test-session");
        Path file = root.resolve("test.txt");
        Files.writeString(file, "original");
        history.trackEdit(file.toString());
        Files.writeString(file, "first turn");
        history.makeSnapshot(2, "first turn");
        history.trackEdit(file.toString());
        Files.writeString(file, "second turn");
        history.makeSnapshot(4, "second turn");

        assertEquals(1, history.rewind(0).size());
        assertEquals("first turn", Files.readString(file));
    }
}
