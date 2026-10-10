package vn.edu.ltm.common.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import vn.edu.ltm.common.protocol.ProtocolException;

import java.io.*;
import java.security.MessageDigest;

/** Length-prefixed JSON metadata followed by an exact, unframed payload. Streams remain caller-owned. */
public final class FileTransferCodec {
    private static final int MAX_FRAME = 65_536;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public void writeHeader(OutputStream output, FileTransferHeader header) throws IOException {
        writeFrame(output, header);
    }

    public FileTransferHeader readHeader(InputStream input) throws IOException {
        FileTransferHeader header = readFrame(input, FileTransferHeader.class);
        if (header.operation() == null || header.transferId() == null || header.deviceId() == null
                || header.credential() == null || header.relativePath() == null || header.sha256() == null
                || header.fileSize() < 0) throw new ProtocolException("Invalid file header");
        return header;
    }

    public void writeResult(OutputStream output, TransferResult result) throws IOException {
        writeFrame(output, result);
    }

    public TransferResult readResult(InputStream input) throws IOException {
        TransferResult result = readFrame(input, TransferResult.class);
        if (result.status() == null) throw new ProtocolException("Invalid transfer result");
        return result;
    }

    public void copyExactly(InputStream input, OutputStream output, long length, MessageDigest digest) throws IOException {
        if (length < 0) throw new IllegalArgumentException("Negative payload length");
        byte[] buffer = new byte[65_536];
        long remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) throw new EOFException("File payload ended before declared size");
            if (count == 0) continue;
            output.write(buffer, 0, count);
            digest.update(buffer, 0, count);
            remaining -= count;
        }
    }

    private void writeFrame(OutputStream output, Object value) throws IOException {
        byte[] payload = mapper.writeValueAsBytes(value);
        if (payload.length == 0 || payload.length > MAX_FRAME) throw new ProtocolException("File metadata too large");
        var data = new DataOutputStream(output);
        data.writeInt(payload.length);
        data.write(payload);
        data.flush();
    }

    private <T> T readFrame(InputStream input, Class<T> type) throws IOException {
        var data = new DataInputStream(input);
        int length;
        try { length = data.readInt(); }
        catch (EOFException failure) { throw new ProtocolException("Truncated file metadata length", failure); }
        if (length <= 0 || length > MAX_FRAME) throw new ProtocolException("Invalid file metadata length");
        byte[] payload = new byte[length];
        try { data.readFully(payload); }
        catch (EOFException failure) { throw new ProtocolException("Truncated file metadata", failure); }
        try {
            T value = mapper.readValue(payload, type);
            if (value == null) throw new ProtocolException("Null file metadata");
            return value;
        } catch (JsonProcessingException failure) {
            throw new ProtocolException("Invalid file metadata JSON", failure);
        }
    }
}
