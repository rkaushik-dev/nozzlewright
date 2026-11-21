package io.github.rkaushikdev.nozzlewright.transport;

import static io.github.rkaushikdev.nozzlewright.testing.Wire.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LinkClosed;
import io.github.rkaushikdev.nozzlewright.TransportError;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransportTcpTest {

    /** A tiny server that answers every line with a zero status. */
    static final class EchoServer {
        final ServerSocket server;
        final AtomicInteger connections = new AtomicInteger();
        volatile boolean closeImmediately;
        private final Thread acceptor;

        EchoServer() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            acceptor = Thread.ofPlatform().daemon().name("echo-server").start(this::serve);
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    connections.incrementAndGet();
                    Thread.ofPlatform().daemon().start(() -> handle(socket));
                } catch (IOException error) {
                    return;
                }
            }
        }

        private void handle(Socket socket) {
            try (socket) {
                if (closeImmediately) {
                    return;
                }
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                OutputStream out = socket.getOutputStream();
                String line;
                while ((line = reader.readLine()) != null) {
                    out.write(("0:" + line.strip() + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
            } catch (IOException error) {
                // The client went away; nothing to answer.
                return;
            }
        }

        void stop() throws IOException, InterruptedException {
            server.close();
            acceptor.join(1000);
        }
    }

    private EchoServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new EchoServer();
    }

    @AfterEach
    void stopServer() throws Exception {
        server.stop();
    }

    @Test
    void roundTrip() throws Exception {
        try (TcpTransport link = new TcpTransport("127.0.0.1", server.port())) {
            link.open();
            link.send(bytes("STATUS\r\n"));
            assertThat(link.recv(Duration.ofSeconds(1))).isEqualTo(bytes("0:STATUS\r\n"));
        }
    }

    @Test
    void endpointIsHostAndPort() {
        TcpTransport link = new TcpTransport("127.0.0.1", server.port());
        assertThat(link.endpoint()).isEqualTo("127.0.0.1:" + server.port());
    }

    @Test
    void countersMove() throws Exception {
        try (TcpTransport link = new TcpTransport("127.0.0.1", server.port())) {
            link.open();
            link.send(bytes("INK\r\n"));
            link.recv(Duration.ofSeconds(1));
            assertThat(link.stats().bytesOut()).isEqualTo(5);
            assertThat(link.stats().bytesIn()).isEqualTo(7);
        }
    }

    @Test
    void recvTimesOutQuietly() throws Exception {
        try (TcpTransport link = new TcpTransport("127.0.0.1", server.port())) {
            link.open();
            assertThat(link.recv(Duration.ofMillis(50))).isEmpty();
            assertThat(link.stats().readTimeouts()).isEqualTo(1);
        }
    }

    @Test
    void farEndCloseRaises() throws Exception {
        server.closeImmediately = true;
        try (TcpTransport link = new TcpTransport("127.0.0.1", server.port())) {
            link.open();
            assertThatThrownBy(() -> link.recv(Duration.ofSeconds(1)))
                    .isInstanceOf(LinkClosed.class)
                    .hasMessageContaining("far end closed");
            assertThat(link.isOpen()).isFalse();
        }
    }

    @Test
    void connectRefusedIsATransportError() {
        TcpTransport link = new TcpTransport("127.0.0.1", 1);
        assertThatThrownBy(link::open).isInstanceOf(TransportError.class);
        assertThat(link.isOpen()).isFalse();
    }

    @Test
    void connectTimeoutIsReported() {
        TcpTransport link = new TcpTransport("10.255.255.1", 9100, 0.05, 2.0);
        assertThatThrownBy(link::open).isInstanceOf(TransportError.class);
    }

    @Test
    void reconnectAfterClose() throws Exception {
        TcpTransport link = new TcpTransport("127.0.0.1", server.port());
        link.open();
        link.close();
        link.open();
        link.send(bytes("PING\r\n"));
        assertThat(link.recv(Duration.ofSeconds(1))).isEqualTo(bytes("0:PING\r\n"));
        link.close();
        assertThat(server.connections.get()).isEqualTo(2);
        assertThat(link.stats().reopens()).isEqualTo(1);
    }

    @Test
    void sendAfterCloseIsRefused() throws Exception {
        TcpTransport link = new TcpTransport("127.0.0.1", server.port());
        link.open();
        link.close();
        assertThatThrownBy(() -> link.send(bytes("PING\r\n"))).isInstanceOf(LinkClosed.class);
    }
}
