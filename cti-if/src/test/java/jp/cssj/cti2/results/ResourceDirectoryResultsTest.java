package jp.cssj.cti2.results;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.zamasoft.zstream.io.FragmentedOutput;
import net.zamasoft.zstream.io.util.FragmentOutputAdapter;
import net.zamasoft.zstream.resolver.util.SimpleSourceMetadata;

class ResourceDirectoryResultsTest {
	@TempDir
	Path temp;

	@Test
	void preservesRelativeResultUri() throws Exception {
		final ResourceDirectoryResults results = new ResourceDirectoryResults(this.temp.toFile());
		write(results, "pages/0001.svg", "image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8));
		write(results, "assets/fonts/font-0001.woff2", "font/woff2", new byte[] { 'w', 'O', 'F', '2' });

		assertArrayEquals("<svg/>".getBytes(StandardCharsets.UTF_8),
				Files.readAllBytes(this.temp.resolve("pages/0001.svg")));
		assertArrayEquals(new byte[] { 'w', 'O', 'F', '2' },
				Files.readAllBytes(this.temp.resolve("assets/fonts/font-0001.woff2")));
	}

	@Test
	void rejectsTraversalAndNonPathComponents() throws Exception {
		final String[] unsafe = { "../escape", "%2e%2e/escape", "assets/%5c..%5cescape", "/absolute",
				"C:%5cescape", "https://example.com/a", "pages/a?query", "pages/a#fragment", "pages//a",
				"pages/./a" };
		for (int i = 0; i < unsafe.length; ++i) {
			final ResourceDirectoryResults results = new ResourceDirectoryResults(this.temp.resolve("case-" + i).toFile());
			final URI uri = URI.create(unsafe[i]);
			assertThrows(IOException.class,
					() -> results.nextBuilder(new SimpleSourceMetadata(uri, "application/octet-stream", null, -1)),
					unsafe[i]);
		}
		assertTrue(Files.notExists(this.temp.getParent().resolve("escape")));
	}

	@Test
	void rejectsDuplicateAndExistingTargets() throws Exception {
		final ResourceDirectoryResults results = new ResourceDirectoryResults(this.temp.toFile());
		write(results, "manifest.json", "application/json", new byte[] { '{', '}' });
		assertThrows(IOException.class, () -> results.nextBuilder(
				new SimpleSourceMetadata(URI.create("manifest.json"), "application/json", null, -1)));

		Files.write(this.temp.resolve("existing.txt"), new byte[] { 1 });
		assertThrows(IOException.class, () -> results.nextBuilder(
				new SimpleSourceMetadata(URI.create("existing.txt"), "text/plain", null, -1)));
	}

	private static void write(final ResourceDirectoryResults results, final String uri, final String mimeType,
			final byte[] data) throws Exception {
		final FragmentedOutput builder = results.nextBuilder(
				new SimpleSourceMetadata(URI.create(uri), mimeType, null, data.length));
		try {
			builder.addFragment();
			try (OutputStream out = new FragmentOutputAdapter(builder, 0)) {
				out.write(data);
			}
		} finally {
			builder.close();
		}
	}
}
