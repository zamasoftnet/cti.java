package jp.cssj.cti2;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

import jp.cssj.cti2.message.MessageHandler;
import jp.cssj.cti2.progress.ProgressListener;
import jp.cssj.cti2.results.Results;
import net.zamasoft.zstream.resolver.SourceMetadata;
import net.zamasoft.zstream.resolver.Source;
import net.zamasoft.zstream.resolver.SourceResolver;

/**
 * A connection to a server for converting documents.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: CTISession.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public interface CTISession extends Closeable {
	/** A constant for aborting at a suitable stopping point. Pass this to the abort method. */
	public static final byte ABORT_NORMAL = 1;

	/** A constant for forcibly aborting processing. Pass this to the abort method. */
	public static final byte ABORT_FORCE = 2;

	/**
	 * Returns server information. For details, see the <a href=
	 * "http://dl.cssj.jp/docs/copper/3.0/html/3410_ctip2.html#prog-ctip2-server-info"
	 * >Copper PDF documentation</a>.
	 * 
	 * @param uri
	 *            The URI that selects the server information.
	 * @return A stream of server information data.
	 */
	public InputStream getServerInfo(URI uri) throws IOException;

	/**
	 * <p>
	 * Sets the output destination.
	 * </p>
	 * <p>
	 * You must call this method before any transcode method.
	 * </p>
	 * 
	 * @param results
	 *            The output destination.
	 */
	public void setResults(Results results) throws IOException;

	/**
	 * <p>
	 * Sets the object that receives messages.
	 * </p>
	 * 
	 * <p>
	 * You must call this method before any transcode method.
	 * </p>
	 * 
	 * @see MessageHandler
	 * @param messageHandler
	 *            The message handler
	 */
	public void setMessageHandler(MessageHandler messageHandler) throws IOException;

	/**
	 * <p>
	 * Sets the object that monitors progress.
	 * </p>
	 * <p>
	 * You must call this method before any transcode method.
	 * </p>
	 * 
	 * @see ProgressListener
	 * @param progressListener
	 *            The progress listener
	 */
	public void setProgressListener(ProgressListener progressListener) throws IOException;

	/**
	 * <p>
	 * Sets a property.
	 * </p>
	 * <p>
	 * You must call this method before any transcode method.
	 * </p>
	 * 
	 * @param name
	 *            The property name
	 * @param value
	 *            The value
	 * @throws IOException
	 */
	public void property(String name, String value) throws IOException;

	/**
	 * <p>
	 * Returns an output stream for sending a resource.
	 * </p>
	 * <p>
	 * <strong>Always close the output stream after sending the resource. </strong>
	 * </p>
	 * <p>
	 * You must call this method before any transcode method.
	 * </p>
	 * 
	 * @param SourceMetadata
	 *            Metadata for the resource data.
	 * @return An output stream to the server.
	 * @throws IOException
	 */
	public OutputStream resource(SourceMetadata metaSource) throws IOException;

	/**
	 * <p>
	 * Sends a resource.
	 * </p>
	 * <p>
	 * You must call this method before any transcode method.
	 * </p>
	 * 
	 * @param source
	 *            The data source for the resource.
	 * @throws IOException
	 */
	public void resource(Source source) throws IOException;

	/**
	 * <p>
	 * Sets the object that loads resources.
	 * </p>
	 * 
	 * @param resolver
	 *            The SourceResolver that retrieves resources requested by the server.
	 */
	public void setSourceResolver(SourceResolver resolver) throws IOException;

	/**
	 * <p>
	 * Returns an output stream for sending the main document.
	 * </p>
	 * <p>
	 * <strong>Always close the output stream after sending the document body. </strong>
	 * </p>
	 * 
	 * @param SourceMetadata
	 *            Metadata for the main document.
	 * @return An output stream to the server.
	 * @throws IOException
	 */
	public OutputStream transcode(SourceMetadata metaSource) throws IOException;

	/**
	 * <p>
	 * Retrieves and converts the main document by accessing the specified address from the server.
	 * This also works with resources you previously sent using the resource method.
	 * </p>
	 * 
	 * @param uri
	 *            The URI of the main document.
	 * @throws IOException
	 */
	public void transcode(URI uri) throws IOException, TranscoderException;

	/**
	 * <p>
	 * Retrieves the main document from a data source and converts it.
	 * </p>
	 * 
	 * @param source
	 *            The data source for the main document.
	 * @throws IOException
	 */
	public void transcode(Source source) throws IOException, TranscoderException;

	/**
	 * <p>
	 * Switches to a mode that combines multiple results.
	 * </p>
	 * 
	 * @param continuous
	 *            If true, enables the mode that combines results using join.
	 * @throws IOException
	 */
	public void setContinuous(boolean continuous) throws IOException;

	/**
	 * <p>
	 * With setContinues(true) set, combines and outputs the results of multiple transcode calls.
	 * </p>
	 * 
	 * @throws IOException
	 */
	public void join() throws IOException;

	/**
	 * <p>
	 * Aborts conversion. You must call this method asynchronously (from another thread).
	 * When processing actually stops, the thread performing the conversion (the one that called transcode)
	 * throws a TranscoderException.
	 * </p>
	 * 
	 * @param mode
	 *            Specify ABORT_NORMAL to output up to a suitable stopping point,
	 *            or ABORT_FORCE to forcibly stop processing.
	 * @throws IOException
	 */
	public void abort(byte mode) throws IOException;

	/**
	 * <p>
	 * Clears all sent resources and all settings, including properties and the message handler,
	 * and restores the initial state the session had when it was created.
	 * </p>
	 * 
	 * @throws IOException
	 */
	public void reset() throws IOException;

	/**
	 * <p>
	 * Closes the session.
	 * <p>
	 * 
	 * You cannot perform any operations on the session after calling this method.
	 * 
	 * @throws IOException
	 */
	public void close() throws IOException;
}