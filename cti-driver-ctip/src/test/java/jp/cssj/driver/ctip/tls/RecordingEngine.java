package jp.cssj.driver.ctip.tls;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

/** Delegates to real JSSE. Keeps a bounded trace even when the caller spins. */
class RecordingEngine extends SSLEngine {
    static final class Event {
        final String operation;
        final SSLEngineResult result;
        final int remaining;

        Event(String operation, SSLEngineResult result, int remaining) {
            this.operation = operation;
            this.result = result;
            this.remaining = remaining;
        }

        public String toString() {
            return operation + " " + result.getStatus() + "/" + result.getHandshakeStatus()
                    + "/" + result.bytesConsumed() + "/" + result.bytesProduced()
                    + " remaining=" + remaining;
        }
    }

    final SSLEngine delegate;
    final List<Event> events = new ArrayList<Event>();
    private final String side;
    private final boolean scripted;
    private boolean scriptStarted;
    private boolean scriptFinished;
    long stalled;

    RecordingEngine(SSLEngine delegate, String side, boolean scripted) {
        this.delegate = delegate;
        this.side = side;
        this.scripted = scripted;
    }

    SSLEngineResult record(String operation, SSLEngineResult result, int remaining) {
        Event event = new Event(side + "." + operation, result, remaining);
        if (events.size() < 128) {
            events.add(event);
            if (events.size() <= 32) {
                System.out.println(event);
            }
        }
        if (operation.equals("unwrap") && result.getHandshakeStatus() == HandshakeStatus.NEED_WRAP
                && result.bytesConsumed() == 0 && result.bytesProduced() == 0 && remaining > 0) {
            if (++stalled == 10000) {
                System.out.println("NO_PROGRESS_10000 " + event);
                System.out.flush();
            }
        }
        return result;
    }

    public SSLEngineResult unwrap(ByteBuffer src, ByteBuffer[] dst, int offset, int length)
            throws SSLException {
        SSLEngineResult result;
        if (scripted && !scriptFinished) {
            if (!scriptStarted && !src.hasRemaining()) {
                return record("unwrap", new SSLEngineResult(Status.BUFFER_UNDERFLOW,
                        HandshakeStatus.NEED_UNWRAP, 0, 0), 0);
            }
            int consumed = scriptStarted ? 0 : 1;
            if (consumed != 0) {
                src.get();
                scriptStarted = true;
            }
            result = new SSLEngineResult(Status.OK, HandshakeStatus.NEED_WRAP, consumed, 0);
        } else {
            result = delegate.unwrap(src, dst, offset, length);
        }
        return record("unwrap", result, src.remaining());
    }

    public SSLEngineResult wrap(ByteBuffer[] src, int offset, int length, ByteBuffer dst)
            throws SSLException {
        SSLEngineResult result;
        if (scripted && scriptStarted && !scriptFinished) {
            scriptFinished = true;
            result = new SSLEngineResult(Status.OK, HandshakeStatus.FINISHED, 0, 0);
        } else {
            result = delegate.wrap(src, offset, length, dst);
        }
        return record("wrap", result, 0);
    }

    public HandshakeStatus getHandshakeStatus() {
        if (scripted && scriptStarted) {
            return scriptFinished ? HandshakeStatus.NOT_HANDSHAKING : HandshakeStatus.NEED_WRAP;
        }
        return delegate.getHandshakeStatus();
    }
    public Runnable getDelegatedTask() { return delegate.getDelegatedTask(); }
    public void closeInbound() throws SSLException { delegate.closeInbound(); }
    public boolean isInboundDone() { return delegate.isInboundDone(); }
    public void closeOutbound() { delegate.closeOutbound(); }
    public boolean isOutboundDone() { return delegate.isOutboundDone(); }
    public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
    public String[] getEnabledCipherSuites() { return delegate.getEnabledCipherSuites(); }
    public void setEnabledCipherSuites(String[] suites) { delegate.setEnabledCipherSuites(suites); }
    public String[] getSupportedProtocols() { return delegate.getSupportedProtocols(); }
    public String[] getEnabledProtocols() { return delegate.getEnabledProtocols(); }
    public void setEnabledProtocols(String[] protocols) { delegate.setEnabledProtocols(protocols); }
    public SSLSession getSession() { return delegate.getSession(); }
    public void beginHandshake() throws SSLException { delegate.beginHandshake(); }
    public void setUseClientMode(boolean mode) { delegate.setUseClientMode(mode); }
    public boolean getUseClientMode() { return delegate.getUseClientMode(); }
    public void setNeedClientAuth(boolean need) { delegate.setNeedClientAuth(need); }
    public boolean getNeedClientAuth() { return delegate.getNeedClientAuth(); }
    public void setWantClientAuth(boolean want) { delegate.setWantClientAuth(want); }
    public boolean getWantClientAuth() { return delegate.getWantClientAuth(); }
    public void setEnableSessionCreation(boolean enable) { delegate.setEnableSessionCreation(enable); }
    public boolean getEnableSessionCreation() { return delegate.getEnableSessionCreation(); }
}
