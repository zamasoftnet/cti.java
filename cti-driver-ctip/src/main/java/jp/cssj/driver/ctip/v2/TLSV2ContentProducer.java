package jp.cssj.driver.ctip.v2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;

public class TLSV2ContentProducer extends V2ContentProducer {
	public TLSV2ContentProducer(URI uri, String encoding) throws IOException {
		super(uri, encoding);
	}

	protected ByteChannel createChannel(InetSocketAddress address) throws IOException {
		SocketChannel socketChannel = SelectorProvider.provider().openSocketChannel();
		TLSSocketChannel channel = new TLSSocketChannel(socketChannel);
		channel.connect(address, this.serverURI.getHost(), address.getPort(), this.connectTimeout);
		return channel;
	}
}
