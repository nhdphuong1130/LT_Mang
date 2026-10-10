package vn.edu.ltm.server.ui;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ServerListenersTest {

    @Test void fileListenerStartsBeforeControlAndClosesOnControlFailure() {
        var events = new ArrayList<String>();
        var files = new ServerListeners.Listener() {
            public void start() { events.add("file-start"); }
            public void close() { events.add("file-close"); }
        };
        var control = new ServerListeners.Listener() {
            public void start() throws IOException { events.add("control-start"); throw new IOException("bind failed"); }
            public void close() { events.add("control-close"); }
        };
        var listeners = new ServerListeners(files, control);
        assertThrows(IOException.class, listeners::start);
        assertEquals(List.of("file-start", "control-start", "control-close", "file-close"), events);
    }

    @Test void failedControlStartupClosesTheActualFileSocket() throws Exception {
        var socket = new AtomicReference<ServerSocket>();
        var files = new ServerListeners.Listener() {
            public void start() throws IOException { socket.set(new ServerSocket(0)); }
            public void close() {
                try { socket.get().close(); } catch (IOException failure) { throw new IllegalStateException(failure); }
            }
        };
        var control = new ServerListeners.Listener() {
            public void start() throws IOException { throw new IOException("Control bind failed"); }
            public void close() { }
        };
        assertThrows(IOException.class, () -> new ServerListeners(files, control).start());
        assertTrue(socket.get().isClosed());
    }

    @Test void shutdownClosesFileListenerEvenIfControlCloseFails() {
        var events = new ArrayList<String>();
        var files = new ServerListeners.Listener() {
            public void start() { }
            public void close() { events.add("file-close"); }
        };
        var control = new ServerListeners.Listener() {
            public void start() { }
            public void close() { events.add("control-close"); throw new IllegalStateException("close failed"); }
        };
        assertThrows(IllegalStateException.class, () -> new ServerListeners(files, control).close());
        assertEquals(List.of("control-close", "file-close"), events);
    }
}
