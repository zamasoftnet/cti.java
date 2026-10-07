package jp.cssj.server.rest;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.TranscoderException;
import jp.cssj.cti2.helpers.CTIMessageCodes;
import jp.cssj.cti2.helpers.MimeTypeHelper;
import jp.cssj.cti2.helpers.ServletHelper;
import jp.cssj.cti2.helpers.ServletResponseResults;
import jp.cssj.cti2.message.MessageHandler;
import jp.cssj.cti2.progress.ProgressListener;
import jp.cssj.cti2.results.Results;
import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceResolver;
import net.zamasoft.zstream.resolver.composite.CompositeSourceResolver;
import net.zamasoft.zstream.resolver.util.SimpleSourceMetadata;
import net.zamasoft.zstream.resolver.util.URIHelper;
import net.zamasoft.zstream.resolver.protocol.file.FileSource;
import net.zamasoft.zstream.resolver.protocol.stream.StreamSource;
import net.zamasoft.zstream.io.FragmentedOutput;
import net.zamasoft.zstream.io.impl.FileFragmentedOutput;
import jp.cssj.server.ConversionGate;
import jp.cssj.server.rest.RestRequest.FormField;

import org.apache.commons.fileupload.FileItemHeaders;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.io.IOUtils;

/**
 * Session information for the REST interface.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: RestSession.java 1635 2023-04-03 08:16:41Z miyabe $
 */
public class RestSession {
	private final CTISession session;
	public final long timeout;
	private final Messages messages;
	private final SourceResolver resolver;
	private volatile long accessed = System.currentTimeMillis();
	private volatile TranscodeTask transcode = null;

	/**
	 * Messages already received.
	 */
	protected record Message(short code, String[] args, String text) {
		protected Message {
			args = args == null ? null : args.clone();
		}
	}

	/**
	 * Receives messages.
	 * 
	 * @author MIYABE Tatsuhiko
	 * @version $Id: RestSession.java 1635 2023-04-03 08:16:41Z miyabe $
	 */
	protected class Messages implements MessageHandler {
		private final List<Message> messages = Collections.synchronizedList(new ArrayList<>());

		public void message(short code, String[] args, String mes) {
			if ((code & 0xF000) >= 0x3000) {
				// Error level or higher (2026-10-04). If conversion fails, discard results closed after this point
				// as partial output (failure handling in TranscodeTask.run).
				RestSession.this.errorMessages.incrementAndGet();
			}
			Message message = new Message(code, args, mes);
			this.add(message);
		}

		public void add(Message message) {
			// System.err.println("message1: "+message.text);
			this.messages.add(message);
			synchronized (RestSession.this) {
				RestSession.this.notifyAll();
			}
			// System.err.println("message2: "+message.text);
		}

		public boolean isEmpty() {
			return this.messages.isEmpty();
		}

		public Message remove() {
			return this.messages.remove(0);
		}

		public int size() {
			return this.messages.size();
		}
	}

	protected class TranscodeTask implements SourceResolver, ProgressListener, Runnable {
		/** Length of the main document. **/
		private long srcLength = -1L;
		/** Main document data already read. */
		private long srcRead = -1L;
		/** URI of the main document on the server. */
		private URI uri = null;
		/** Source of the main document on the client. */
		private Source source = null;
		/** Temporary input that makes multipart rest.main independent of part order. */
		private File sourceFile = null;
		/** Requested resource. */
		private URI requiredResource = null;
		private Source resolvedResource = null;
		/** List of result URIs. */
		private List<URI> resultList = null;
		/** Map from URIs to result files. */
		private Map<URI, File> uriToResult = null;
		/** Map from URIs to result SourceMetadata. */
		private Map<URI, SourceMetadata> uriToSourceMetadata = null;
		private volatile boolean transcoding = false;
		private Throwable ex = null;
		private Thread th = null;
		/**
		 * Concurrent conversion permit (2026-10-03). Returned when conversion ends in non-continuous mode,
		 * or on join, reset, or close in continuous mode.
		 */
		private ConversionGate.Permit permit = null;
		private boolean continuous = false;
		/** Makes resolve stop waiting for a client resource on abort or close. */
		private volatile boolean aborted = false;
		/** Number of error notifications at the start of conversion. */
		private int errorsAtStart = 0;
		/** Results closed after an error notification (discarded as partial output on failure). */
		private java.util.Set<URI> resultsAfterError = null;

		/**
		 * Reserves this session for conversion and acquires a concurrent conversion permit before touching input
		 * (2026-10-03). Refuses without waiting if the session is converting or the server has no free slots.
		 * Previously, the request thread for a second conversion on a busy session waited (blocking an XNIO task
		 * thread), after replacing the input.
		 */
		void begin(final boolean continuous) throws ConversionRefusedException {
			synchronized (RestSession.this) {
				if (this.transcoding) {
					throw new ConversionRefusedException(RestServlet.ERROR_SESSION_BUSY, null);
				}
				if (this.permit == null) {
					this.permit = RestSession.this.gate.tryEnter();
					if (this.permit == null) {
						throw new ConversionRefusedException(CTIMessageCodes.ERROR_BUSY,
								new String[] { String.valueOf(RestSession.this.gate.limit()) });
					}
				}
				this.continuous = continuous;
				this.aborted = false;
				this.transcoding = true;
			}
		}

		/** Cleans up after failure following {@link #begin}, before conversion starts. */
		void abandon() {
			synchronized (RestSession.this) {
				this.transcoding = false;
				RestSession.this.notifyAll();
			}
			if (!this.continuous) {
				this.releasePermit();
			}
		}

		/** Returns the permit (subsequent calls do nothing). */
		void releasePermit() {
			final ConversionGate.Permit p;
			synchronized (RestSession.this) {
				p = this.permit;
				this.permit = null;
			}
			if (p != null) {
				p.close();
			}
		}

		/** Makes {@link #resolve} stop waiting for a client resource. */
		void abortWaits() {
			this.aborted = true;
			synchronized (this) {
				this.notifyAll();
			}
		}

		public void sourceLength(long srcLength) {
			// System.err.println("srcLength: "+srcLength);
			synchronized (RestSession.this) {
				this.srcLength = srcLength;
				RestSession.this.notifyAll();
			}
		}

		public void progress(long srcRead) {
			// System.err.println("srcRead1: "+srcRead);
			synchronized (RestSession.this) {
				this.srcRead = srcRead;
				RestSession.this.notifyAll();
			}
			// System.err.println("srcRead2: "+srcRead);
		}

		public void setSourceURI(URI uri) {
			this.cleanupSourceFile();
			this.source = null;
			this.uri = uri;
		}

		public void setSource(Source source) {
			this.setSource(source, null);
		}
		public void setSource(Source source, File sourceFile) {
			this.cleanupSourceFile();
			this.uri = null;
			this.source = source;
			this.sourceFile = sourceFile;
		}
		private void cleanupSourceFile() {
			if (this.sourceFile != null) {
				if (!this.sourceFile.delete()) {
					this.sourceFile.deleteOnExit();
				}
				this.sourceFile = null;
			}
		}

		public synchronized Source resolve(URI uri) throws IOException, FileNotFoundException {
			synchronized (RestSession.this) {
				this.requiredResource = uri;
				RestSession.this.notifyAll();
			}
			try {
				for (;;) {
					if (this.resolvedResource != null) {
						return this.resolvedResource;
					}
					// Stop waiting on abort or close (2026-10-03). Previously, interrupts did not end the wait;
					// when close waited for conversion to finish (join), neither finished.
					if (this.requiredResource == null || !this.transcoding || this.aborted) {
						throw new FileNotFoundException(uri.toString());
					}
					try {
						this.wait(1000);
					} catch (InterruptedException e) {
						this.aborted = true;
					}
				}
			} finally {
				this.requiredResource = null;
				this.resolvedResource = null;
			}
		}

		public void release(Source source) {
			try {
				((StreamSource) source).close();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		public void transcode(final HttpServletRequest req, final HttpServletResponse res, boolean async,
				boolean resolverMode, boolean continuous) throws ServletException, IOException, TranscoderException {
			try {
				if (resolverMode) {
					RestSession.this.session.setSourceResolver(this);
				} else if (RestSession.this.resolver != null) {
					RestSession.this.session.setSourceResolver(RestSession.this.resolver);
				}
				RestSession.this.session.setContinuous(continuous);
			} catch (RuntimeException | Error e) {
				this.abandon();
				throw e;
			}

			// begin() has already reserved the session and acquired a concurrent conversion permit (2026-10-03).
			if (async) {
				try {
					this.th = Thread.ofVirtual().name(RestServlet.class.getName()).start(this);
				} catch (RuntimeException | Error e) {
					this.th = null;
					this.abandon();
					throw e;
				}
				RestServlet.sendMessage(req, res, RestServlet.INFO_OK);
			} else {
				this.syncTranscode(res);
			}
		}

		/**
		 * Receives output in a temporary file, then streams it to the response (added on 2026-08-06).
		 *
		 * <p>
		 * <b>Why not write directly to the response.</b> Previously, {@link ServletResponseResults} wrote directly
		 * to the servlet's output stream. However, if conversion <b>aborted after output started</b>
		 * (whether due to {@code output.page-limit}, whose default abort mode is {@code force},
		 * {@code output.size-limit}, or an engine-side abort), cleanup via {@code builder.close()}
		 * <b>committed the partial output as a "complete response with a correct Content-Length"</b>.
		 * By the time the servlet received the exception, {@code isCommitted()} was already true,
		 * leaving no way to correct the response. The client therefore received
		 * <b>HTTP 200 + application/pdf + a broken body</b>, which simply looked as though
		 * "the engine produced a broken PDF" (confirmed by measurement on 2026-08-06).
		 * The manual (CopperPDF's "Operating limits") says, "When an output limit takes effect …
		 * an error is reported," and the implementation contradicted it.
		 * </p>
		 *
		 * <p>
		 * <b>What this change sacrifices.</b> Measurements show no loss. The synchronous path sent
		 * no bytes to the client during conversion (confirmed by observing a 23 MB document every second
		 * on 2026-08-06), so it was already effectively not streaming. The asynchronous path
		 * has always used temporary files.
		 * </p>
		 */
		private class SpooledResults implements Results {
			private final File file;
			private final long time = System.currentTimeMillis();
			private FragmentedOutput builder = null;
			private SourceMetadata metaSource = null;

			SpooledResults(File file) {
				this.file = file;
			}

			public boolean hasNext() {
				return this.builder == null;
			}

			public FragmentedOutput nextBuilder(SourceMetadata metaSource) throws IOException {
				if (this.builder != null) {
					throw new IllegalStateException();
				}
				this.metaSource = metaSource;
				// **Close only once.** Contents are finalized in the temporary file
				// on close, so {@link #sendTo} closes it first. Engine cleanup
				// ({@code PDFUserAgent.dispose}) then tries to close it again.
				// Do not let the second close reassemble the fragments.
				this.builder = new FileFragmentedOutput(this.file) {
					private boolean closed = false;

					public void close() throws IOException {
						if (this.closed) {
							return;
						}
						this.closed = true;
						super.close();
					}
				};
				return this.builder;
			}

			public void end() {
				// NOP
			}

			/**
			 * Called <b>only when conversion succeeds</b>. This is the first access to the response,
			 * so on failure it remains uncommitted and can return an error.
			 */
			void sendTo(HttpServletResponse res) throws IOException {
				if (this.builder == null) {
					// No output was created
					return;
				}
				// **Close first.** close finalizes the contents in the temporary file, and the engine
				// has not yet closed it when conversion finishes (the old implementation wrote
				// directly to the response, so it emitted bytes even before close).
				this.builder.close();
				long length = this.file.length();
				RestSession.this.done(length, System.currentTimeMillis() - this.time);
				if (this.metaSource != null && this.metaSource.getMimeType() != null) {
					res.setContentType(ServletHelper.getContentType(this.metaSource));
				}
				res.setContentLengthLong(length);
				try (InputStream in = new FileInputStream(this.file)) {
					IOUtils.copy(in, res.getOutputStream());
				}
			}
		}

		/**
		 * Performs synchronous conversion.
		 *
		 * @param res
		 * @throws ServletException
		 * @throws IOException
		 */
		private void syncTranscode(final HttpServletResponse res)
				throws ServletException, IOException, TranscoderException {
			final File spool = File.createTempFile("copper-rest-sync-", ".dat");
			try {
				RestSession.this.session.setProgressListener(this);
				// Retrieve only one result
				SpooledResults results = new SpooledResults(spool);
				RestSession.this.session.setResults(results);
				if (this.uri != null) {
					RestSession.this.session.transcode(this.uri);
				} else {
					RestSession.this.session.transcode(this.source);
				}
				// **Reaching this point means success.** This is the only point that touches the response.
				results.sendTo(res);
			} catch (IOException e) {
				this.ex = e;
				throw e;
			} finally {
				this.cleanupSourceFile();
				this.transcoding = false;
				// Always delete the temporary file, whether conversion succeeds or fails.
				if (!spool.delete()) {
					spool.deleteOnExit();
				}
				synchronized (RestSession.this) {
					RestSession.this.notifyAll();
				}
				if (!this.continuous) {
					this.releasePermit();
				}
			}
		}

		/**
		 * Performs asynchronous conversion.
		 */
		public void run() {
			try {
				this.resultList = new ArrayList<>();
				this.uriToResult = new HashMap<>();
				this.uriToSourceMetadata = new HashMap<>();
				this.resultsAfterError = new java.util.HashSet<>();
				this.errorsAtStart = RestSession.this.errorMessages.get();
				Results results = new Results() {
					public boolean hasNext() {
						return true;
					}

					public FragmentedOutput nextBuilder(final SourceMetadata metaSource) throws IOException {
						final URI uri = metaSource.getURI();
						final File file = File.createTempFile("copper-rest-result-", ".dat");
						FragmentedOutput builder = new FileFragmentedOutput(file) {
							public void close() throws IOException {
								super.close();
								File resultFile = null;
								if (file.length() > 0L) {
									resultFile = File.createTempFile("copper-rest-result-stable-", ".dat");
									Files.copy(file.toPath(), resultFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
								}
								synchronized (RestSession.this) {
									File previous = uriToResult.get(uri);
									if (resultFile != null) {
										if (previous == null) {
											resultList.add(uri);
										} else {
											previous.delete();
										}
										uriToResult.put(uri, resultFile);
										uriToSourceMetadata.put(uri, metaSource);
										if (RestSession.this.errorMessages.get() > errorsAtStart) {
											resultsAfterError.add(uri);
										} else {
											resultsAfterError.remove(uri);
										}
									} else {
										if (previous == null) {
											file.delete();
										}
									}
									RestSession.this.notifyAll();
								}
							}
						};
						return builder;
					}

					public void end() {
						// NOP
					}
				};

				RestSession.this.session.setResults(results);
				RestSession.this.session.setProgressListener(this);
				if (this.uri != null) {
					RestSession.this.session.transcode(this.uri);
				} else {
					RestSession.this.session.transcode(this.source);
				}
			} catch (TranscoderException e) {
				if (e.getState() == TranscoderException.STATE_BROKEN) {
					this.th = null;
					this.discardFailedResults();
				}
				this.ex = e;
			} catch (IOException e) {
				this.th = null;
				this.discardFailedResults();
				this.ex = e;
			} catch (Throwable e) {
				this.th = null;
				this.discardFailedResults();
				this.ex = e;
			} finally {
				this.cleanupSourceFile();
				this.transcoding = false;
				synchronized (RestSession.this) {
					RestSession.this.notifyAll();
				}
				// The engine returns only after joining the conversion thread, so the permit can be returned here.
				if (!this.continuous) {
					this.releasePermit();
				}
			}
		}

		/**
		 * Cleans up results from a failed conversion (2026-10-04, TECH-20261003-004, item ⑦).
		 *
		 * <p>
		 * Previously, all results were deleted, so {@code /result} returned 404 even for results
		 * already announced by {@code /messages} (such as completed pages in image output).
		 * Results closed before an error notification (such as 3805 for {@code output.page-limit})
		 * are complete, so keep them until the session closes. Results closed after the error notification
		 * are partial output closed during the abort (a partial PDF in the case of PDF output), so discard them.
		 * If conversion fails without an error notification, there is no way to tell which results are complete,
		 * so discard them all as before.
		 * </p>
		 */
		private void discardFailedResults() {
			if (this.uriToResult == null) {
				return;
			}
			synchronized (RestSession.this) {
				if (RestSession.this.errorMessages.get() <= this.errorsAtStart) {
					this.dispose();
					return;
				}
				for (final URI uri : this.resultsAfterError) {
					final File file = this.uriToResult.remove(uri);
					if (file != null) {
						file.delete();
					}
					this.uriToSourceMetadata.remove(uri);
					this.resultList.remove(uri);
				}
				this.resultsAfterError.clear();
			}
		}

		public void dispose() {
			if (this.th != null) {
				try {
					this.th.join();
				} catch (InterruptedException e) {
					// ignore;
				}
			}
			if (this.uriToResult != null) {
				for (File file : this.uriToResult.values()) {
					file.delete();
				}
				this.resultList = null;
				this.uriToResult = null;
				this.uriToSourceMetadata = null;
			}
		}
	}

	/** Concurrent conversion limit (shared by REST and CTIP; 2026-10-03). */
	private final ConversionGate gate;

	/** Number of notifications received at error level or higher (2026-10-04; used to clean up failed conversion results). */
	private final java.util.concurrent.atomic.AtomicInteger errorMessages = new java.util.concurrent.atomic.AtomicInteger();

	RestSession(CTISession session, boolean messages, boolean restResolver, long timeout) throws IOException {
		this(session, messages, restResolver, timeout, ConversionGate.UNLIMITED);
	}

	RestSession(CTISession session, boolean messages, boolean restResolver, long timeout, ConversionGate gate)
			throws IOException {
		this.gate = gate == null ? ConversionGate.UNLIMITED : gate;
		this.session = session;
		if (messages) {
			this.messages = new Messages();
		} else {
			this.messages = null;
		}
		if (this.messages != null) {
			this.session.setMessageHandler(this.messages);
		}
		if (restResolver) {
			this.resolver = CompositeSourceResolver.createGenericCompositeSourceResolver();
		} else {
			this.resolver = null;
		}
		this.timeout = timeout;
	}

	private void resource(SourceMetadata metaSource, byte[] data) throws IOException {
		if (this.transcode != null) {
			synchronized (this.transcode) {
				if (metaSource.getURI().equals(this.transcode.requiredResource)) {
					this.transcode.resolvedResource = new StreamSource(metaSource.getURI(),
							new ByteArrayInputStream(data), metaSource.getMimeType(), metaSource.getEncoding(),
							data.length);
					this.transcode.notify();
					return;
				}
			}
		}
		try (OutputStream out = this.session.resource(metaSource)) {
			out.write(data);
		}
	}

	/**
	 * Reports that the client could not find a resource.
	 *
	 * <p>
	 * Corresponds to the CTIP2 {@code MISSING_RESOURCE} packet. Ends the waiting
	 * {@link Transcode#resolve(URI)} with <b>not found</b> (withdrawing the request
	 * results in {@code FileNotFoundException}).
	 * </p>
	 *
	 * @param uri URI of the resource that was not found; null means the currently requested resource.
	 * @return true if the URI matches the currently requested resource and the request is withdrawn.
	 */
	private boolean resourceNotFound(final URI uri) {
		if (this.transcode == null) {
			return false;
		}
		synchronized (this.transcode) {
			final URI required = this.transcode.requiredResource;
			if (required == null || (uri != null && !required.equals(uri))) {
				return false;
			}
			this.transcode.requiredResource = null;
			this.transcode.notify();
			return true;
		}
	}

	private void resource(Source source) throws IOException {
		if (this.transcode != null) {
			synchronized (this.transcode) {
				if (source.getURI().equals(this.transcode.requiredResource)) {
					this.transcode.resolvedResource = source;
					this.transcode.notify();
					return;
				}
			}
		}
		this.session.resource(source);
	}

	/**
	 * Completes processing.
	 * 
	 * @param length
	 * @param time
	 */
	private void done(long length, long time) {
		String size;
		if (length < 1024) {
			size = length + "B";
		} else if (length < 1024 * 1024) {
			size = (length / 1024) + "KB";
		} else {
			size = (length / 1024 / 1024) + "MB";
		}
		if (this.messages != null) {
			Message message = new Message((short) 0, null, "Done: " + size + " / " + time + "ms");
			this.messages.add(message);
		}
	}

	/**
	 * Returns the time of the last access.
	 * 
	 * @return
	 */
	long getAccessed() {
		return this.accessed;
	}

	void info(final HttpServletRequest req, final HttpServletResponse res)
			throws ServletException, FileUploadException, IOException, URISyntaxException {
		RestRequest restReq = RestRequest.getRestRequest(req);
		String uriStr = restReq.getParameter("rest.uri");
		if (uriStr == null) {
			uriStr = ".";
		}
		URI uri = URIHelper.create(RestServlet.CHARSET, uriStr);
		try (InputStream in = this.session.getServerInfo(uri)) {
			OutputStream out = res.getOutputStream();
			IOUtils.copy(in, out);
		}
	}

	/**
	 * Sets properties.
	 * 
	 * @param req
	 * @throws ServletException
	 * @throws FileUploadException
	 * @throws IOException
	 */
	void properties(final HttpServletRequest req) throws ServletException, FileUploadException, IOException {
		RestRequest restReq = RestRequest.getRestRequest(req);
		String charset = req.getCharacterEncoding();
		if (charset == null) {
			charset = RestServlet.CHARSET;
		}
		while (restReq.getType() != RestRequest.NONE) {
			if (restReq.getType() != RestRequest.FIELD) {
				restReq.getItem();
				restReq.nextItem();
				continue;
			}
			FormField field = (FormField) restReq.getItem();
			if (field.name.startsWith("rest.")) {
				restReq.nextItem();
				continue;
			}
			this.property(req, field.name, field.value);
			restReq.nextItem();
		}
	}

	private void property(HttpServletRequest req, String name, String value) throws IOException {
		if (name.equals("webapp.user-agent")) {
			String ua = req.getHeader("User-Agent");
			if (ua != null) {
				this.session.property("input.http.header.0.name", "User-Agent");
				this.session.property("input.http.header.0.value", ua);
			}
		}
		this.session.property(name, value);
	}

	/**
	 * Sends a resource.
	 * 
	 * @param req
	 * @param res
	 * @throws ServletException
	 * @throws IOException
	 * @throws TranscoderException
	 * @throws FileUploadException
	 */
	void resources(final HttpServletRequest req, final HttpServletResponse res)
			throws ServletException, IOException, TranscoderException, FileUploadException {
		this.accessed = System.currentTimeMillis();
		RestRequest restReq = RestRequest.getRestRequest(req);
		String uri = restReq.getParameter("rest.uri");
		String mimeType = restReq.getParameter("rest.mimeType");
		String encoding = restReq.getParameter("rest.encoding");

		if ("yes".equals(restReq.getParameter("rest.notFound"))) {
			// **The client could not find the resource.**
			// Without reading this, the request with no body would be treated
			// as a "0-byte resource," and the missing CSS or image would
			// resolve to empty content (2026-08-03). Give this the same
			// meaning as CTIP2's MISSING_RESOURCE.
			URI missing = null;
			if (uri != null) {
				try {
					missing = URIHelper.create(RestServlet.CHARSET, uri);
				} catch (URISyntaxException e) {
					this.messages.message(CTIMessageCodes.WARN_BAD_RESOURCE_URI, new String[] { uri }, null);
				}
			}
			this.resourceNotFound(missing);
			return;
		}

		// System.err.println(req.getContentType());
		String charset = req.getCharacterEncoding();
		if (charset == null) {
			charset = RestServlet.CHARSET;
		}

		// System.err.println("resources");
		while (restReq.getType() != RestRequest.NONE) {
			if (restReq.getType() == RestRequest.FIELD) {
				// Form value
				FormField field = (FormField) restReq.getItem();
				// System.err.println("form: "+field.name);
				if (field.name.startsWith("rest.")) {
					if (field.name.equals("rest.resource")) {
						URI rsrcURI;
						if (uri == null) {
							rsrcURI = URIHelper.CURRENT_URI;
						} else {
							try {
								rsrcURI = URIHelper.create(RestServlet.CHARSET, uri);
							} catch (URISyntaxException e) {
								this.messages.message(CTIMessageCodes.WARN_BAD_RESOURCE_URI, new String[] { uri },
										null);
								rsrcURI = URIHelper.CURRENT_URI;
							}
						}
						byte[] data = field.data;
						String e = encoding;
						if (data == null) {
							data = field.value.getBytes(charset);
							e = charset;
						}
						this.resource(new SimpleSourceMetadata(rsrcURI, mimeType, e, data.length), data);
					} else if (field.name.equals("rest.uri")) {
						uri = field.value;
					} else if (field.name.equals("rest.mimeType")) {
						mimeType = field.value;
					} else if (field.name.equals("rest.encoding")) {
						encoding = field.value;
					}
				} else {
					this.property(req, field.name, field.value);
				}
			} else {
				// File
				FileItemStream item = (FileItemStream) restReq.getItem();
				String name = item.getFieldName();
				// System.err.println("file: "+name+"/"+item);
				if (name.equals("rest.resource")) {
					FileItemHeaders headers = item.getHeaders();
					if (uri == null && headers != null) {
						uri = headers.getHeader("X-URI");
					}
					if (uri == null) {
						uri = item.getName();
					}
					if (mimeType == null) {
						mimeType = item.getContentType();
					}
					if (encoding == null && mimeType != null) {
						encoding = MimeTypeHelper.getParameter(mimeType, "charset");
					}
					mimeType = MimeTypeHelper.getTypePart(mimeType);
					long length = -1L;
					if (headers != null) {
						String value = headers.getHeader("Content-Length");
						if (value != null) {
							length = Long.parseLong(value);
						}
					}
					URI rsrcURI;
					try {
						rsrcURI = URIHelper.create(RestServlet.CHARSET, uri);
					} catch (URISyntaxException e) {
						this.messages.message(CTIMessageCodes.WARN_BAD_RESOURCE_URI, new String[] { uri }, null);
						rsrcURI = URIHelper.CURRENT_URI;
					}
					try (InputStream in = item.openStream()) {
						this.resource(new StreamSource(rsrcURI, in, mimeType, encoding, length));
					}
					uri = null;
					mimeType = null;
					encoding = null;
					length = -1L;
				}
			}
			restReq.nextItem();
		}
		if (!RestUtils.isForm(req)) {
			// The body is a resource
			if (uri == null) {
				uri = req.getHeader("X-URI");
			}
			if (mimeType == null) {
				mimeType = req.getContentType();
			}
			if (encoding == null && mimeType != null) {
				encoding = MimeTypeHelper.getParameter(mimeType, "charset");
			}
			mimeType = MimeTypeHelper.getTypePart(mimeType);
			long length = -1L;
			String value = req.getHeader("Content-Length");
			if (value != null) {
				length = Long.parseLong(value);
			}
			URI rsrcURI;
			if (uri == null) {
				rsrcURI = URIHelper.CURRENT_URI;
			} else {
				try {
					rsrcURI = URIHelper.create(RestServlet.CHARSET, uri);
				} catch (URISyntaxException e) {
					this.messages.message(CTIMessageCodes.WARN_BAD_RESOURCE_URI, new String[] { uri }, null);
					rsrcURI = URIHelper.CURRENT_URI;
				}
			}
			StreamSource source = new StreamSource(rsrcURI, req.getInputStream(), mimeType, encoding, length);
			this.resource(source);
		}
	}

	/**
	 * Sends the main document.
	 * 
	 * @param req
	 * @param res
	 * @return
	 * @throws ServletException
	 * @throws IOException
	 * @throws TranscoderException
	 * @throws FileUploadException
	 * @throws URISyntaxException
	 */
	boolean transcode(final HttpServletRequest req, final HttpServletResponse res) throws ServletException,
			IOException, TranscoderException, FileUploadException, URISyntaxException, ConversionRefusedException {
		this.accessed = System.currentTimeMillis();
		RestRequest restReq = RestRequest.getRestRequest(req);
		String uri = restReq.getParameter("rest.uri");
		String mimeType = restReq.getParameter("rest.mimeType");
		String encoding = restReq.getParameter("rest.encoding");
		String mainURI = restReq.getParameter("rest.mainURI");

		boolean async = "true".equals(restReq.getParameter("rest.async"));
		boolean resolverMode = "true".equals(restReq.getParameter("rest.requestResource"));
		boolean continuous = "true".equals(restReq.getParameter("rest.continuous"));

		String charset = req.getCharacterEncoding();
		if (charset == null) {
			charset = RestServlet.CHARSET;
		}

		Source mainSource = null;
		File mainSourceFile = null;
		boolean mainSourceTransferred = false;
		try {
			// Multipart order has no significance. Even if rest.main arrives first, apply all subsequent
			// regular properties before starting conversion.
			while (restReq.getType() != RestRequest.NONE) {
				if (restReq.getType() == RestRequest.FIELD) {
					FormField field = (FormField) restReq.getItem();
					if (field.name.startsWith("rest.")) {
						if (field.name.equals("rest.mainURI")) {
							mainURI = field.value;
						} else if (field.name.equals("rest.main")) {
							if (mainSource != null) {
								throw new ServletException("Duplicate rest.main part");
							}
							byte[] data = field.data;
							String enc = encoding;
							if (data == null) {
								data = field.value.getBytes(charset);
								enc = charset;
							}
							URI rsrcURI;
							if (uri == null) {
								rsrcURI = URIHelper.CURRENT_URI;
							} else {
								try {
									rsrcURI = URIHelper.create(RestServlet.CHARSET, uri);
								} catch (URISyntaxException e) {
									this.messages.message(CTIMessageCodes.WARN_BAD_RESOURCE_URI,
											new String[] { uri }, null);
									rsrcURI = URIHelper.CURRENT_URI;
								}
							}
							mainSource = new StreamSource(rsrcURI, new ByteArrayInputStream(data), mimeType, enc,
									data.length);
							uri = null;
							mimeType = null;
							encoding = null;
						} else if (field.name.equals("rest.resource")) {
							byte[] data = field.data;
							String enc = encoding;
							if (data == null) {
								data = field.value.getBytes(charset);
								enc = charset;
							}
							URI rsrcURI;
							if (uri == null) {
								rsrcURI = URIHelper.CURRENT_URI;
							} else {
								try {
									rsrcURI = URIHelper.create(RestServlet.CHARSET, uri);
								} catch (URISyntaxException e) {
									this.messages.message(CTIMessageCodes.WARN_BAD_RESOURCE_URI,
											new String[] { uri }, null);
									rsrcURI = URIHelper.CURRENT_URI;
								}
							}
							SourceMetadata metaSource = new SimpleSourceMetadata(rsrcURI, mimeType, enc, data.length);
							this.resource(metaSource, data);
						} else if (field.name.equals("rest.uri")) {
							uri = field.value;
						} else if (field.name.equals("rest.mimeType")) {
							mimeType = field.value;
						} else if (field.name.equals("rest.encoding")) {
							encoding = field.value;
						} else if (field.name.equals("rest.async")) {
							async = "true".equals(field.value);
						} else if (field.name.equals("rest.requestResource")) {
							resolverMode = "true".equals(field.value);
						} else if (field.name.equals("rest.continuous")) {
							continuous = "true".equals(field.value);
						}
					} else {
						this.property(req, field.name, field.value);
					}
				} else {
					FileItemStream item = (FileItemStream) restReq.getItem();
					String name = item.getFieldName();
					if (name.equals("rest.resource") || name.equals("rest.main")) {
						FileItemHeaders headers = item.getHeaders();
						if (uri == null && headers != null) {
							uri = headers.getHeader("X-URI");
						}
						if (uri == null) {
							uri = item.getName();
						}
						if (mimeType == null) {
							mimeType = item.getContentType();
						}
						if (encoding == null && mimeType != null) {
							encoding = MimeTypeHelper.getParameter(mimeType, "charset");
						}
						mimeType = MimeTypeHelper.getTypePart(mimeType);
						URI rsrcURI;
						if (uri == null) {
							rsrcURI = URIHelper.CURRENT_URI;
						} else {
							try {
								rsrcURI = URIHelper.create(RestServlet.CHARSET, uri);
							} catch (URISyntaxException e) {
								this.messages.message(CTIMessageCodes.WARN_BAD_RESOURCE_URI,
										new String[] { uri }, null);
								rsrcURI = URIHelper.CURRENT_URI;
							}
						}
						if (name.equals("rest.main")) {
							if (mainSource != null) {
								throw new ServletException("Duplicate rest.main part");
							}
							mainSourceFile = File.createTempFile("copper-rest-main-", ".dat");
							try (InputStream in = item.openStream()) {
								Files.copy(in, mainSourceFile.toPath(),
										java.nio.file.StandardCopyOption.REPLACE_EXISTING);
							}
							mainSource = new FileSource(mainSourceFile, rsrcURI, mimeType, encoding);
						} else {
							try (InputStream in = item.openStream()) {
								this.resource(new StreamSource(rsrcURI, in, mimeType, encoding, -1L));
							}
						}
						uri = null;
						mimeType = null;
						encoding = null;
					}
				}
				restReq.nextItem();
			}

			if (!async) {
				resolverMode = false;
				continuous = false;
			}
			if (mainSource != null) {
				if (this.transcode == null) {
					this.transcode = new TranscodeTask();
				}
				this.transcode.begin(continuous);
				this.transcode.setSource(mainSource, mainSourceFile);
				mainSourceTransferred = true;
				this.transcode.transcode(req, res, async, resolverMode, continuous);
				return true;
			}
			if (mainURI != null) {
				if (this.transcode == null) {
					this.transcode = new TranscodeTask();
				}
				URI mainURIParsed = URIHelper.create(RestServlet.CHARSET, mainURI);
				this.transcode.begin(continuous);
				this.transcode.setSourceURI(mainURIParsed);
				this.transcode.transcode(req, res, async, resolverMode, continuous);
				return true;
			}
			if (!RestUtils.isForm(req)) {
				if (uri == null) {
					uri = req.getHeader("X-URI");
				}
				if (mimeType == null) {
					mimeType = req.getContentType();
				}
				if (encoding == null && mimeType != null) {
					encoding = MimeTypeHelper.getParameter(mimeType, "charset");
				}
				mimeType = MimeTypeHelper.getTypePart(mimeType);
				long length = -1L;
				String value = req.getHeader("Content-Length");
				if (value != null) {
					length = Long.parseLong(value);
				}
				URI rsrcURI = uri == null ? URIHelper.CURRENT_URI
						: URIHelper.create(RestServlet.CHARSET, uri);
				Source source = new StreamSource(rsrcURI, req.getInputStream(), mimeType, encoding, length);
				if (this.transcode == null) {
					this.transcode = new TranscodeTask();
				}
				this.transcode.begin(continuous);
				this.transcode.setSource(source);
				this.transcode.transcode(req, res, async, resolverMode, continuous);
				return true;
			}
			return false;
		} finally {
			if (!mainSourceTransferred && mainSourceFile != null && !mainSourceFile.delete()) {
				mainSourceFile.deleteOnExit();
			}
		}
	}
	void noResource(HttpServletRequest req)
			throws ServletException, IOException, TranscoderException, FileUploadException, URISyntaxException {
		if (this.transcode != null) {
			synchronized (this.transcode) {
				RestRequest restReq = RestRequest.getRestRequest(req);
				String uri = restReq.getParameter("rest.uri");
				if (uri == null) {
					uri = ".";
				}
				if (URIHelper.create(RestServlet.CHARSET, uri).equals(this.transcode.requiredResource)) {
					this.transcode.requiredResource = null;
					this.transcode.resolvedResource = null;
					this.transcode.notify();
					return;
				}
			}
		}
	}

	/**
	 * Returns messages.
	 * 
	 * @param req
	 * @param res
	 * @throws ServletException
	 * @throws IOException
	 */
	synchronized void messages(final HttpServletRequest req, final HttpServletResponse res)
			throws ServletException, IOException, FileUploadException {
		// System.err.println("messages1");

		this.accessed = System.currentTimeMillis();
		res.setContentType("text/xml");
		res.setCharacterEncoding("UTF-8");
		try (PrintWriter out = res.getWriter()) {
			out.println("<?xml version=\"1.0\"?>");
			out.println("<response>");

			boolean transcoding = this.transcode != null && this.transcode.transcoding;
			if (transcoding && this.messages.isEmpty()) {
				RestRequest restReq = RestRequest.getRestRequest(req);
				String waitStr = restReq.getParameter("rest.wait");
				if (waitStr != null) {
					// Wait for messages to accumulate.
					int wait = 0;
					try {
						wait = Integer.parseInt(waitStr);
					} catch (NumberFormatException e1) {
						// ignore
					}
					// Do not wait forever for nonpositive or invalid values. Cap at MAX_MESSAGES_WAIT (2026-10-03;
					// previously, wait(0) meant forever, and repeated requests could exhaust XNIO task threads).
					if (wait > 0) {
						try {
							this.wait(Math.min(wait, MAX_MESSAGES_WAIT));
						} catch (InterruptedException e) {
							// ignore
						}
					}
				}
			}

			String code = Integer.toHexString(transcoding ? RestServlet.INFO_TRANSCODING : RestServlet.INFO_TRANDCODED);
			out.print("<message code=\"");
			out.print(code);
			out.println("\" />");

			// Send messages
			if (!this.messages.isEmpty()) {
				out.println("<messages>");
				do {
					Message message = this.messages.remove();
					String text = RestUtils.htmlEscape(message.text());
					out.print("<message code=\"" + Integer.toHexString(message.code()) + "\"");
					if (message.args() != null) {
						for (int i = 0; i < message.args().length; ++i) {
							out.print(" arg" + i + "=\"" + RestUtils.htmlEscape(message.args()[i]) + "\"");
						}
					}
					out.print(">");
					out.print(text);
					out.println("</message>");
				} while (!this.messages.isEmpty());
				out.println("</messages>");
			}
			if (this.transcode != null) {
				// Abort
				if (this.transcode.ex != null) {
					TranscoderException e;
					if (this.transcode.ex instanceof TranscoderException) {
						e = (TranscoderException) this.transcode.ex;
					} else {
						e = new TranscoderException(CTIMessageCodes.FATAL_UNEXPECTED,
								new String[] { this.transcode.ex.getMessage() }, "");
					}
					String text = RestUtils.htmlEscape(e.getMessage());
					out.print("<interrupted code=\"" + Integer.toHexString(e.getCode()) + "\"");
					if (e.getArgs() != null) {
						for (int i = 0; i < e.getArgs().length; ++i) {
							out.print(" arg" + i + "=\"" + RestUtils.htmlEscape(e.getArgs()[i]) + "\"");
						}
					}
					out.print(">");
					out.print(text);
					out.println("</interrupted>");
				}

				// Requested resource
				if (this.transcode.requiredResource != null) {
					out.println("<resources>");
					out.print("<resource uri=\"");
					out.print(RestUtils.htmlEscape(this.transcode.requiredResource.toString()));
					out.println("\"/>");
					out.println("</resources>");
				}
				// Conversion results
				if (this.transcode.uriToResult != null && !this.transcode.uriToResult.isEmpty()) {
					out.println("<results>");
					for (URI uri : this.transcode.resultList) {
						final SourceMetadata metaSource = this.transcode.uriToSourceMetadata.get(uri);
						out.print("<result uri=\"");
						out.print(RestUtils.htmlEscape(uri.toString()));
						out.print("\"");
						if (metaSource != null) {
							final String mimeType = metaSource.getMimeType();
							if (mimeType != null) {
								out.print(" mimeType=\"");
								out.print(RestUtils.htmlEscape(mimeType));
								out.print("\"");
							}
							final String encoding = metaSource.getEncoding();
							if (encoding != null) {
								out.print(" encoding=\"");
								out.print(RestUtils.htmlEscape(encoding));
								out.print("\"");
							}
						}
						final File resultFile = this.transcode.uriToResult.get(uri);
						if (resultFile != null) {
							out.print(" length=\"");
							out.print(resultFile.length());
							out.print("\"");
						}
						out.println("/>");
					}
					out.println("</results>");
				}
				// Progress
				if (this.transcode.srcLength != -1L || this.transcode.srcRead != -1L) {
					out.print("<progress");
					if (this.transcode.srcLength != -1L) {
						out.print(" length=\"" + this.transcode.srcLength + "\"");
					}
					if (this.transcode.srcRead != -1L) {
						out.print(" read=\"" + this.transcode.srcRead + "\"");
					}
					out.println(" />");
				}
			}
			out.println("</response>");
		}
	}

	/**
	 * Receives a result.
	 * 
	 * @param req
	 * @param res
	 * @throws IOException
	 * @throws FileUploadException
	 */
	synchronized void result(HttpServletRequest req, HttpServletResponse res)
			throws IOException, FileUploadException, ServletException {
		this.accessed = System.currentTimeMillis();
		if (this.transcode == null || this.transcode.uriToResult == null) {
			noResult(req, res);
			return;
		}
		RestRequest restReq = RestRequest.getRestRequest(req);
		String uri = restReq.getParameter("rest.uri");
		if (uri == null) {
			uri = ".";
		}
		this.writeResult(req, res, uri, false);
	}

	/**
	 * Returns a result by path ({@code /result/<sessionID>/<relativeURI>})
	 * (2026-08-28).
	 *
	 * <p>
	 * Result sets (such as page-split SVG) refer to one another with relative URIs. With the query form
	 * ({@code ?rest.uri=…}), the client has to rewrite {@code ../assets/…} in each received page itself.
	 * With the path form, <b>the browser's relative URI resolution works directly</b>,
	 * so neither rewriting nor resource prefetching is needed.
	 * </p>
	 */
	void resultByPath(HttpServletRequest req, HttpServletResponse res, String uri)
			throws IOException, FileUploadException, ServletException {
		this.accessed = System.currentTimeMillis();
		if (this.transcode == null || this.transcode.uriToResult == null) {
			noResult(req, res);
			return;
		}
		this.writeResult(req, res, uri, true);
	}

	/**
	 * Returns <b>404</b> for missing results (2026-09-02, requested by cti.li). Previously, the response
	 * before conversion was 200+XML(3016), and a missing URI after conversion returned 500+XML(3002 I/O error).
	 * The viewer had to recognize "not yet" from the body format, and the 500 caused false monitoring alerts.
	 * The body still contains XML code 3016, so existing clients can also check the body.
	 */
	private static void noResult(HttpServletRequest req, HttpServletResponse res)
			throws ServletException, IOException {
		res.setStatus(HttpServletResponse.SC_NOT_FOUND);
		RestServlet.sendMessage(req, res, RestServlet.ERROR_NO_RESULT);
	}

	/**
	 * @param declareEncoding whether to add {@code Content-Encoding} to gzip-compressed results.
	 *                        Added only for the path form, because existing clients expect raw bytes
	 *                        with the query form.
	 */
	private void writeResult(HttpServletRequest req, HttpServletResponse res, String uri, boolean declareEncoding)
			throws IOException, FileUploadException, ServletException {
		try {
			URI resultURI = URIHelper.create(RestServlet.CHARSET, uri);
			File file = this.transcode.uriToResult.get(resultURI);
			if (file == null) {
				noResult(req, res);
				return;
			}
			SourceMetadata metaSource = this.transcode.uriToSourceMetadata.get(resultURI);
			res.setContentLengthLong(file.length());
			res.setContentType(ServletHelper.getContentType(metaSource));
			if (declareEncoding) {
				final String name = resultURI.toString();
				if (name.endsWith(".gz") || name.endsWith(".svgz")) {
					// The content is gzip-compressed. Declaring it lets the browser decompress it before passing it on.
					res.setHeader("Content-Encoding", "gzip");
				}
			}
			try (InputStream in = new FileInputStream(file)) {
				IOUtils.copy(in, res.getOutputStream());
			}
		} catch (URISyntaxException e) {
			noResult(req, res);
		}
	}

	/**
	 * Aborts conversion.
	 * 
	 * @param req
	 * @throws IOException
	 * @throws FileUploadException
	 */
	void abort(HttpServletRequest req) throws IOException, FileUploadException {
		this.accessed = System.currentTimeMillis();
		RestRequest restReq = RestRequest.getRestRequest(req);
		byte mode = CTISession.ABORT_NORMAL;
		String modeStr = restReq.getParameter("rest.mode");
		if (modeStr != null && modeStr.equals("force")) {
			mode = CTISession.ABORT_FORCE;
		}
		if (this.transcode != null) {
			this.transcode.abortWaits();
		}
		this.session.abort(mode);
	}

	/**
	 * Joins results.
	 * 
	 * @throws IOException
	 * @throws FileUploadException
	 */
	void join() throws IOException, FileUploadException {
		this.accessed = System.currentTimeMillis();
		try {
			this.session.join();
		} finally {
			// continuous mode ends here (2026-10-03).
			if (this.transcode != null && !this.transcode.transcoding) {
				this.transcode.releasePermit();
			}
		}
	}

	/**
	 * Resets the session.
	 * 
	 * @throws IOException
	 */
	void reset() throws IOException {
		this.accessed = System.currentTimeMillis();
		this.disposeTranscode();
		this.session.reset();
	}

	/**
	 * Closes the session.
	 * 
	 * @throws IOException
	 */
	void close() throws IOException {
		this.disposeTranscode();
		this.session.close();
	}

	/**
	 * Cleans up conversion. If conversion is waiting for a client resource, ends that wait before waiting
	 * for conversion to finish (previously, neither finished), then returns the permit (2026-10-03).
	 */
	private void disposeTranscode() {
		if (this.transcode != null) {
			this.transcode.abortWaits();
			this.transcode.dispose();
			this.transcode.releasePermit();
			this.transcode = null;
		}
	}

	/** Maximum {@code rest.wait} for {@code /messages} (milliseconds). */
	static final long MAX_MESSAGES_WAIT = 30000L;
}
