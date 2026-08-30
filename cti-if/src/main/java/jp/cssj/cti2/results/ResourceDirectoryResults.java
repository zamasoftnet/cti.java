package jp.cssj.cti2.results;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import net.zamasoft.zstream.io.FragmentedOutput;
import net.zamasoft.zstream.io.impl.FileFragmentedOutput;
import net.zamasoft.zstream.resolver.SourceMetadata;

/**
 * Stores multi-part results at the relative URI supplied in each result's
 * metadata.  Unlike {@link DirectoryResults}, this preserves resource paths
 * such as {@code pages/0001.svg} and {@code assets/fonts/font-0001.woff2}.
 *
 * <p>The URI is treated as untrusted input. Absolute paths, traversal,
 * fragments, queries, duplicate names and existing targets are rejected.</p>
 *
 * @since 2.2
 */
public class ResourceDirectoryResults implements Results {
	private final File directory;
	private final String canonicalPrefix;
	private final Set<String> names = new HashSet<String>();

	public ResourceDirectoryResults(final File directory) throws IOException {
		if (directory == null) {
			throw new NullPointerException("directory");
		}
		Files.createDirectories(directory.toPath());
		this.directory = directory.getCanonicalFile();
		if (!this.directory.isDirectory()) {
			throw new IOException("Output is not a directory: " + directory);
		}
		final String[] children = this.directory.list();
		if (children == null) {
			throw new IOException("Cannot inspect output directory: " + directory);
		}
		if (children.length != 0) {
			throw new IOException("Output directory is not empty: " + directory);
		}
		this.canonicalPrefix = this.directory.getPath() + File.separator;
	}

	@Override
	public boolean hasNext() {
		return true;
	}

	@Override
	public synchronized FragmentedOutput nextBuilder(final SourceMetadata metadata) throws IOException {
		if (metadata == null || metadata.getURI() == null) {
			throw new IOException("A relative result URI is required");
		}
		final URI uri = metadata.getURI();
		if (uri.isAbsolute() || uri.isOpaque() || uri.getRawAuthority() != null || uri.getRawQuery() != null
				|| uri.getRawFragment() != null) {
			throw new IOException("Unsafe result URI: " + uri);
		}
		final String path = uri.getPath();
		if (path == null || path.length() == 0 || path.startsWith("/") || path.startsWith("\\")
				|| path.indexOf(':') >= 0) {
			throw new IOException("Unsafe result URI: " + uri);
		}
		final String[] segments = path.split("[/\\\\]", -1);
		for (int i = 0; i < segments.length; ++i) {
			if (segments[i].length() == 0 || ".".equals(segments[i]) || "..".equals(segments[i])) {
				throw new IOException("Unsafe result URI: " + uri);
			}
		}
		final String key = String.join("/", segments);
		final File target = new File(this.directory, key.replace('/', File.separatorChar));
		final File parent = target.getParentFile();
		File existingAncestor = parent;
		while (!existingAncestor.exists()) {
			existingAncestor = existingAncestor.getParentFile();
		}
		final String ancestorPath = existingAncestor.getCanonicalPath();
		if (!ancestorPath.equals(this.directory.getPath()) && !ancestorPath.startsWith(this.canonicalPrefix)) {
			throw new IOException("Result URI escapes the output directory: " + uri);
		}
		Files.createDirectories(parent.toPath());
		final File canonicalTarget = target.getCanonicalFile();
		if (!canonicalTarget.getPath().startsWith(this.canonicalPrefix)) {
			throw new IOException("Result URI escapes the output directory: " + uri);
		}
		if (canonicalTarget.exists()) {
			throw new IOException("Result target already exists: " + canonicalTarget);
		}
		if (!this.names.add(key)) {
			throw new IOException("Duplicate result URI: " + uri);
		}
		return new FileFragmentedOutput(canonicalTarget);
	}

	@Override
	public void end() {
		// NOP
	}
}
