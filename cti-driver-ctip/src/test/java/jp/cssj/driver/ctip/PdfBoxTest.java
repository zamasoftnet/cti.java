package jp.cssj.driver.ctip;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.stream.Stream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestFactory;

@Order(2)
class PdfBoxTest {

	@TestFactory
	Stream<DynamicTest> test_outputの全PDFが正しい() {
		File[] files = CTIPDriverTest.OUTPUT_DIR.listFiles((dir, name) -> name.endsWith(".pdf"));
		if (files == null || files.length == 0) {
			return Stream.empty();
		}
		return Arrays.stream(files).map(pdfFile ->
			DynamicTest.dynamicTest(pdfFile.getName(), () -> {
				try (PDDocument doc = PDDocument.load(pdfFile)) {
					assertTrue(doc.getNumberOfPages() > 0, pdfFile.getName() + " に1ページ以上あること");
				}
			})
		);
	}
}
