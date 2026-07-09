package jp.cssj.server.socket;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.KeyStore;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import jp.cssj.server.acl.Acl;

/**
 * ソケットで待ち受けるサーバーです。
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: CTIServer.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class CTIServer {
	private static final Logger LOG = Logger.getLogger(CTIServer.class.getName());

	private static final Logger ACCESS = Logger.getLogger("jp.cssj.copper.access");

	private static final long FORCED_SHUTDOWN_TIMEOUT_SECONDS = 5;

	private ProtocolHandler[] handlers = null;

	/**
	 * 待ち受けポート、キューのサイズ、タイムアウト、最小ワーカー数、最大ワーカー数。
	 */
	private int port = -1, tlsPort = -1, backlog = 30, timeout = 180000, minThreads = 10, maxThreads = 50;

	private long shutdownTimeout = -1L;

	private File keyStore;

	private String keyPassword, keyStorePassword;

	private ServerSocket serverSocket;

	private ServerSocket tlsServerSocket;

	private Semaphore permits;

	private ExecutorService executor;

	private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();

	private final AtomicInteger activeConnections = new AtomicInteger();

	private volatile boolean running = true;

	private volatile boolean forceClosing = false;

	private final AtomicLong accessCount = new AtomicLong();

	public CTIServer() {
		// nothing to do
	}

	public int getTotalThreads() {
		return this.maxThreads;
	}

	public int getFreeThreads() {
		Semaphore permits = this.permits;
		return permits == null ? this.maxThreads : permits.availablePermits();
	}

	public int getMaxThreads() {
		return this.maxThreads;
	}

	public long getAccessCount() {
		return this.accessCount.get();
	}

	public void setProtocolHandlers(ProtocolHandler[] handlers) {
		this.handlers = handlers;
	}

	public synchronized void setConfigFile(File configFile, Properties props) throws IOException {
		// 設定の読み込み
		LOG.fine("設定ファイルを読み込みます");
		if (props == null) {
			props = new Properties();
			try (InputStream in = new BufferedInputStream(new FileInputStream(configFile))) {
				props.load(in);
			}
		}
		this.port = Integer.parseInt(props.getProperty("jp.cssj.cssjd.port", String.valueOf(this.port)));
		this.backlog = Integer.parseInt(props.getProperty("jp.cssj.cssjd.backlog", String.valueOf(this.backlog)));
		this.timeout = Integer.parseInt(props.getProperty("jp.cssj.cssjd.timeout", String.valueOf(this.timeout / 1000)))
				* 1000;
		long shutdownTimeoutSeconds = Long.parseLong(
				props.getProperty("jp.cssj.cssjd.shutdownTimeout", String.valueOf(this.timeout / 1000)));
		this.shutdownTimeout = shutdownTimeoutSeconds < 0 ? -1L : shutdownTimeoutSeconds * 1000L;
		this.maxThreads = Integer
				.parseInt(props.getProperty("jp.cssj.cssjd.maxThreads", String.valueOf(this.maxThreads)));
		this.minThreads = Integer
				.parseInt(props.getProperty("jp.cssj.cssjd.minThreads", String.valueOf(this.minThreads)));
		this.maxThreads = Math.max(1, this.maxThreads);
		this.minThreads = Math.max(0, this.minThreads);

		this.tlsPort = Integer.parseInt(props.getProperty("jp.cssj.cssjd.tls.port", String.valueOf(this.tlsPort)));
		if (this.tlsPort != -1) {
			this.keyStore = new File(configFile.getParentFile(),
					props.getProperty("jp.cssj.cssjd.tls.keyStore", "keystore"));
			this.keyStorePassword = props.getProperty("jp.cssj.cssjd.tls.keyStorePassword", "");
			this.keyPassword = props.getProperty("jp.cssj.cssjd.tls.keyPassword", "");
		}
	}

	private void accept(ServerSocket serverSocket) {
		try {
			while (this.running) {
				Socket socket = serverSocket.accept();
				socket.setSoTimeout(this.timeout);
				if (this.acquirePermit(socket)) {
					this.startConnection(socket);
				}
			}
		} catch (SocketException e) {
			LOG.log(Level.FINE, "ソケットがクローズされました", e);
		} catch (Exception e) {
			LOG.log(Level.SEVERE, "予期しないエラーです", e);
		}
	}

	private boolean acquirePermit(Socket socket) {
		try {
			while (this.running) {
				if (this.permits.tryAcquire(500, TimeUnit.MILLISECONDS)) {
					return true;
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		this.closeSocket(socket);
		return false;
	}

	private void startConnection(Socket socket) {
		try {
			this.activeSockets.add(socket);
			this.executor.execute(() -> this.process(socket));
		} catch (Throwable e) {
			this.activeSockets.remove(socket);
			this.permits.release();
			this.closeSocket(socket);
			if (this.running) {
				LOG.log(Level.WARNING, "接続処理を開始できません", e);
			}
			if (e instanceof Error error) {
				throw error;
			}
		}
	}

	private void process(Socket socket) {
		this.activeConnections.incrementAndGet();
		try {
			this.handle(socket);
		} finally {
			this.activeSockets.remove(socket);
			this.activeConnections.decrementAndGet();
			this.permits.release();
			this.closeSocket(socket);
		}
	}

	private void handle(Socket socket) {
		final InetAddress remoteAddress = socket.getInetAddress();
		final String remoteHost = remoteAddress.getHostAddress();
		try {
			Acl acl = Acl.find(remoteHost);
			if (acl == null || !acl.checkAccess(remoteAddress)) {
				ACCESS.info(remoteHost + "からのアクセスを拒否しました");
				return;
			}

			this.accessCount.incrementAndGet();
			final InputStream in = socket.getInputStream();
			final OutputStream out = socket.getOutputStream();

			StringBuilder buff = new StringBuilder();
			for (int b = in.read(); b != -1 && b != '\n'; b = in.read()) {
				buff.append((char) b);
			}
			String firstLine = buff.toString();

			ProtocolProcessor processor = null;
			for (ProtocolHandler handler : this.handlers) {
				if (handler.accepts(firstLine)) {
					processor = handler.newProcesor();
					break;
				}
			}
			if (processor == null) {
				throw new IOException("Unknown protocol:" + firstLine);
			}
			ACCESS.fine("Connected: " + remoteHost);
			try {
				processor.process(socket, in, out, firstLine);
			} finally {
				processor.close();
			}
		} catch (Throwable e) {
			if (this.forceClosing) {
				LOG.log(Level.FINE, "停止処理により接続処理を終了しました", e);
			} else {
				ACCESS.info(remoteHost + "から要求された処理を実行中にエラーが発生しました");
				LOG.log(Level.WARNING, "処理を実行中にエラーが発生しました", e);
			}
		}
	}

	private void closeSocket(Socket socket) {
		try {
			socket.close();
		} catch (Exception e) {
			// ignore
		}
	}

	private boolean awaitShutdown(ExecutorService executor) throws InterruptedException {
		if (this.shutdownTimeout < 0) {
			while (!executor.awaitTermination(1, TimeUnit.DAYS)) {
				// wait indefinitely, preserving the legacy graceful shutdown default when requested
			}
			return true;
		}
		return executor.awaitTermination(this.shutdownTimeout, TimeUnit.MILLISECONDS);
	}

	/**
	 * サーバーを起動します。
	 * 
	 * @throws IOException
	 */
	public synchronized void startup() throws BindException, IOException {
		this.permits = new Semaphore(this.maxThreads);
		this.executor = Executors.newCachedThreadPool(
				Thread.ofPlatform().daemon().name("CopperServer worker-", 0).factory());

		// サーバー開始
		if (this.port != -1) {
			this.serverSocket = new ServerSocket(this.port, this.backlog);
		}

		if (this.tlsPort != -1) {
			try {
				KeyStore keyStore = KeyStore.getInstance("JKS");
				keyStore.load(new FileInputStream(this.keyStore), this.keyPassword.toCharArray());
				KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
				kmf.init(keyStore, this.keyStorePassword.toCharArray());
				SSLContext sslCtxt = SSLContext.getInstance("TLS");
				sslCtxt.init(kmf.getKeyManagers(), null, null);
				ServerSocketFactory ssf = sslCtxt.getServerSocketFactory();
				this.tlsServerSocket = ssf.createServerSocket(this.tlsPort, this.backlog);
			} catch (Exception e) {
				LOG.warning("TLS の設定が不正です");
				e.printStackTrace();
			}
		}

		if (this.serverSocket != null) {
			Thread.ofPlatform().daemon().name(CTIServer.class.getName() + "-plain").start(() -> {
				LOG.info("サーバーを" + port + "番ポートで起動します");
				CTIServer.this.accept(CTIServer.this.serverSocket);
			});
		}
		if (this.tlsServerSocket != null) {
			Thread.ofPlatform().daemon().name(CTIServer.class.getName() + "-tls").start(() -> {
				LOG.info("TLS サーバーを" + tlsPort + "番ポートで起動します");
				CTIServer.this.accept(CTIServer.this.tlsServerSocket);
			});
		}
	}

	/**
	 * サーバーを停止します。
	 * 
	 */
	public synchronized void shutdown() {
		if (!this.running) {
			return;
		}
		this.running = false;
		this.forceClosing = false;
		if (this.serverSocket != null) {
			try {
				this.serverSocket.close();
			} catch (IOException e) {
				LOG.log(Level.WARNING, "待ち受けソケットをクローズできませんでした", e);
			}
		}
		if (this.tlsServerSocket != null) {
			try {
				this.tlsServerSocket.close();
			} catch (IOException e) {
				LOG.log(Level.WARNING, "SSL 待ち受けソケットをクローズできませんでした", e);
			}
		}
		LOG.info("サーバーを停止しています...");
		ExecutorService executor = this.executor;
		if (executor != null) {
			executor.shutdown();
			try {
				if (!this.awaitShutdown(executor)) {
					LOG.warning("接続処理の終了待ちがタイムアウトしました(実行中:" + this.activeConnections.get() + ")");
					this.forceClosing = true;
					for (Socket socket : this.activeSockets) {
						this.closeSocket(socket);
					}
					executor.shutdownNow();
					if (!executor.awaitTermination(FORCED_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
						LOG.warning("強制停止後も接続処理が終了しません(実行中:" + this.activeConnections.get() + ")");
					}
				}
			} catch (InterruptedException e) {
				this.forceClosing = true;
				for (Socket socket : this.activeSockets) {
					this.closeSocket(socket);
				}
				executor.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
		LOG.info("サーバーを停止しました");
	}
}
