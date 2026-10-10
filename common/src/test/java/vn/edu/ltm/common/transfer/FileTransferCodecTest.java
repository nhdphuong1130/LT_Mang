package vn.edu.ltm.common.transfer;

import org.junit.jupiter.api.Test;
import vn.edu.ltm.common.protocol.ProtocolException;

import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class FileTransferCodecTest {
    private final FileTransferCodec codec = new FileTransferCodec();

    @Test void copiesOnlyDeclaredBytesAcrossShortReads() throws Exception {
        byte[] source = {1, 2, 3, 4, 5};
        var input = new ByteArrayInputStream(source) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(length, 1));
            }
        };
        var output = new ByteArrayOutputStream();
        var digest = MessageDigest.getInstance("SHA-256");
        codec.copyExactly(input, output, 4, digest);
        assertArrayEquals(Arrays.copyOf(source, 4), output.toByteArray());
        assertEquals(5, input.read());
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(source, 4)), digest.digest());
    }

    @Test void prematureEofFailsWithoutAcceptingShortPayload() throws Exception {
        assertThrows(EOFException.class, () -> codec.copyExactly(
                new ByteArrayInputStream(new byte[]{1}), new ByteArrayOutputStream(), 2,
                MessageDigest.getInstance("SHA-256")));
    }

    @Test void rejectsInvalidAndOversizedHeaders() throws Exception {
        assertThrows(ProtocolException.class, () -> codec.readHeader(new ByteArrayInputStream(new byte[]{0, 1, 0, 1})));
        assertThrows(ProtocolException.class, () -> codec.readHeader(new ByteArrayInputStream(new byte[]{0, 0, 0, 2, '{', '}'})));
        var header = new FileTransferHeader(FileOperation.BACKUP_UPLOAD, "id", "device", "token",
                "a.txt", 2, "a".repeat(64), null);
        var output = new ByteArrayOutputStream();
        codec.writeHeader(output, header);
        assertEquals(header, codec.readHeader(new ByteArrayInputStream(output.toByteArray())));
    }

    @Test void resultFramesRoundTrip() throws Exception {
        var output = new ByteArrayOutputStream();
        var result = new TransferResult(TransferResult.Status.STORED, "ok");
        codec.writeResult(output, result);
        assertEquals(result, codec.readResult(new ByteArrayInputStream(output.toByteArray())));
    }
}
