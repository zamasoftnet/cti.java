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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
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

	private ProtocolHandler[] handlers = null;

	/**
	 * 一時に1つの処理を行うためのワーカースレッドです。
	 * 
	 * @author MIYABE Tatsuhiko
	 * @version $Id: CTIServer.java 1552 2018-04-26 01:43:24Z miyabe $
	 */
	private class WorkerThread extends Thread {
		private Socket socket;
		private final ProtocolProcessor[] processors = new ProtocolProcessor[CTIServer.this.handlers.length];

		WorkerThread() {
			super("CopperServer worker");
		}

		public synchronized void wakeup(Socket socket) {
			this.socket = socket;
			this.notify();
		}

		private void closeSocket() {
			try {
				this.socket.close();
			} catch (Exception e) {
				// ignore
			}
			this.socket = null;
		}

		public synchronized void run() {
			try {
				while (CTIServer.this.running) {
					try {
						CTIServer.this.freeThread(this);
						this.wait();
						if (!CTIServer.this.running) {
							break;
						}
					} catch (InterruptedException ex) {
						// ignore
					}
					final InetAddress remoteAddress = this.socket.getInetAddress();
					final String remoteHost = remoteAddress.getHostAddress();
					try {
						// アクセス制御
						try {
							Acl acl = Acl.find(remoteHost);
							if (acl == null || !acl.checkAccess(remoteAddress)) {
								ACCESS.info(remoteHost + "からのアクセスを拒否しました");
								continue;
							}

							CTIServer.this.accessCount++;
							final InputStream in = this.socket.getInputStream();
							final OutputStream out = this.socket.getOutputStream();

							StringBuffer buff = new StringBuffer();
							for (int b = in.read(); b != -1 && b != '\n'; b = in.read()) {
								buff.append((char) b);
							}
							String firstLine = buff.toString();

							ProtocolProcessor processor = null;
							for (int i = 0; i < CTIServer.this.handlers.length; ++i) {
								if (CTIServer.this.handlers[i].accepts(firstLine)) {
									if (this.processors[i] == null) {
										this.processors[i] = CTIServer.this.handlers[i].newProcesor();
									}
									processor = this.processors[i];
									break;
								}
							}
							if (processor == null) {
								throw new IOException("Unknown protocol:" + firstLine);
							}
							ACCESS.fine("Connected: " + remoteHost);
							try {
								processor.process(this.socket, in, out, firstLine);
							} finally {
								// System.err.println("dispose: "+this.socket);
								processor.close();
							}
						} finally {
							// System.err.println("closeSocket: "+this.socket);
							this.closeSocket();
						}
					} catch (Throwable e) {
						ACCESS.info(remoteHost + "から要求された処理を実行中にエラーが発生しました");
						LOG.log(Level.WARNING, "処理を実行中にエラーが発生しました", e);
					}
				}
			} catch (Throwable e) {
				String m = "予期しないエラーです。実行環境に問題がある可能性があります";
				LOG.log(Level.SEVERE, m, e);
				CTIServer.this.threads.remove(this);
				CTIServer.this.supplementThreads();
			}
		}
	}

	/**
	 * 待ち受けポート、キューのサイズ、タイムアウト、最小ワーカー数、最大ワーカー数。
	 */
	private int port = -1, tlsPort = -1, backlog = 30, timeout = 180000, minThreads = 10, maxThreads = 50;

	private File keyStore;

	private String keyPassword, keyStorePassword;

	private ServerSocket serverSocket;

	private ServerSocket tlsServerSocket;

	private final List<WorkerThread> free = new ArrayList<WorkerThread>();

	private final Set<WorkerThread> threads = Collections.synchronizedSet(new HashSet<WorkerThread>());

	private volatile boolean running = true;

	private volatile long accessCount;

	public CTIServer() {
		// nothing to do
	}

	public int getTotalThreads() {
		return this.threads.size();
	}

	public int getFreeThreads() {
		return this.free.size();
	}

	public int getMaxThreads() {
		return this.maxThreads;
	}

	public long getAccessCount() {
		return this.accessCount;
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
		this.maxThreads = Integer
				.parseInt(props.getProperty("jp.cssj.cssjd.maxThreads", String.valueOf(this.maxThreads)));
		this.minThreads = Integer
				.parseInt(props.getProperty("jp.cssj.cssjd.minThreads", String.valueOf(this.minThreads)));

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
			OUTER: while (this.running) {
				Socket socket = serverSocket.accept();
				socket.setSoTimeout(this.timeout);
				// 受付
				synchronized (this) {
					while (this.free.isEmpty() && !socket.isClosed()) {
						if (this.threads.size() < this.maxThreads) {
							this.startThread();
						}
						try {
							this.wait();
						} catch (InterruptedException e) {
							// ignore
						}
						if (!this.running) {
							break OUTER;
						}
					}
				}
				if (!socket.isClosed()) {
					WorkerThread worker;
					synchronized (this) {
						worker = (WorkerThread) this.free.remove(this.free.size() - 1);
					}
					worker.wakeup(socket);
				}
			}
		} catch (SocketException e) {
			LOG.log(Level.FINE, "ソケットがクローズされました", e);
		} catch (Exception e) {
			LOG.log(Level.SEVERE, "予期しないエラーです", e);
		}
	}

	private synchronized void supplementThreads() {
		if (!this.running) {
			return;
		}
		while (this.threads.size() < this.minThreads) {
			this.startThread();
		}
	}

	private synchronized void startThread() {
		if (!this.running) {
			return;
		}
		WorkerThread worker = new WorkerThread();
		worker.setDaemon(true);
		this.threads.add(worker);
		worker.start();
	}

	private synchronized void freeThread(WorkerThread worker) {
		if (!this.running) {
			return;
		}
		this.free.add(worker);
		this.notify();
		LOG.fine("ワーカーを登録しました(総数:" + this.threads.size() + "/利用可能:" + this.free.size() + ")");
	}

	/**
	 * サーバーを起動します。
	 * 
	 * @throws IOException
	 */
	public synchronized void startup() throws BindException, IOException {
		this.supplementThreads();

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
			Thread th = new Thread(CTIServer.class.getName()) {
				public void run() {
					LOG.info("サーバーを" + port + "番ポートで起動します");
					CTIServer.this.accept(CTIServer.this.serverSocket);
				}
			};
			th.setDaemon(true);
			th.start();
		}
		if (this.tlsServerSocket != null) {
			Thread th = new Thread(CTIServer.class.getName()) {
				public void run() {
					LOG.info("TLS サーバーを" + tlsPort + "番ポートで起動します");
					CTIServer.this.accept(CTIServer.this.tlsServerSocket);
				}
			};
			th.setDaemon(true);
			th.start();
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
		this.running = false;
		LOG.info("サーバーを停止しています...");
		for (Iterator<WorkerThread> i = this.threads.iterator(); i.hasNext();) {
			WorkerThread worker = (WorkerThread) i.next();
			synchronized (worker) {
				worker.notify();
			}
			try {
				worker.join();
			} catch (InterruptedException e) {
				// ignore
			}
			i.remove();
			LOG.fine("ワーカーの数:" + this.threads.size());
		}
		LOG.info("サーバーを停止しました");
	}
}