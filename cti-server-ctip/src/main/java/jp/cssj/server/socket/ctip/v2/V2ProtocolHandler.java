package jp.cssj.server.socket.ctip.v2;

import java.io.IOException;
import java.net.URI;

import jp.cssj.cti2.CTIDriver;
import jp.cssj.cti2.CTIDriverManager;
import jp.cssj.server.ConversionGate;
import jp.cssj.server.socket.ProtocolHandler;
import jp.cssj.server.socket.ProtocolProcessor;

/**
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: V2ProtocolHandler.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class V2ProtocolHandler implements ProtocolHandler {
	protected final URI uri;
	protected final CTIDriver driver;

	public V2ProtocolHandler(URI uri, CTIDriver driver) {
		this.uri = uri;
		this.driver = driver;
	}

	public V2ProtocolHandler(URI uri) {
		this.uri = uri;
		this.driver = CTIDriverManager.getDriver(uri);
	}

	private ConversionGate gate = ConversionGate.UNLIMITED;

	/** 同時変換数の上限を設定します(REST と共有するゲート。2026-10-03)。 */
	public void setConversionGate(final ConversionGate gate) {
		this.gate = gate == null ? ConversionGate.UNLIMITED : gate;
	}

	public boolean accepts(String firstLine) {
		return firstLine.startsWith("CTIP/2.0 ");
	}

	public ProtocolProcessor newProcesor() throws IOException, SecurityException {
		final V2ProtocolProcessor processor = new V2ProtocolProcessor(this.uri, this.driver);
		processor.setConversionGate(this.gate);
		return processor;
	}
}
