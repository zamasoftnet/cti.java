package jp.cssj.cti2.helpers;

/**
 * A helper class for parsing MIME types.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: MimeTypeHelper.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public final class MimeTypeHelper {
	private MimeTypeHelper() {
		// unused
	}

	/**
	 * Determines whether two MIME types are equal, ignoring their parameters.
	 * 
	 * @param type1
	 *            The first MIME type.
	 * @param type2
	 *            The second MIME type.
	 * @return true if the two types match; false otherwise.
	 */
	public static boolean equals(String type1, String type2) {
		if (type2 == null || type1 == null) {
			return false;
		}
		type1 = getTypePart(type1);
		type2 = getTypePart(type2);
		return type1.equals(type2);
	}

	/**
	 * Returns the part excluding parameters.
	 * 
	 * @param type
	 *            The MIME type.
	 * @return The MIME type without its parameters.
	 */
	public static String getTypePart(String type) {
		if (type == null) {
			return null;
		}
		int semi = type.indexOf(';');
		if (semi != -1) {
			return type.substring(0, semi).trim();
		}
		return type.trim();

	}

	/**
	 * Returns the value of a Content-Type header parameter.
	 * 
	 * @param type
	 *            The Content-Type header value.
	 * @param name
	 *            The parameter name.
	 * @return The parameter value.
	 */
	public static String getParameter(String type, String name) {
		int state = 0;
		StringBuffer buff = new StringBuffer();
		String pname = "", value = "";
		;
		for (int i = 0; i < type.length(); ++i) {
			char c = type.charAt(i);
			switch (state) {
			case 0:
				if (c == '=') {
					pname = buff.toString().trim();
					buff = new StringBuffer();
				} else if (c == ';') {
					if (name.equalsIgnoreCase(pname)) {
						return value + buff.toString().trim();
					}
					pname = "";
					value = "";
					buff = new StringBuffer();
				} else if (c == '"') {
					state = 1;
					buff = new StringBuffer(buff.toString().trim());
				} else if (c == '\'') {
					state = 2;
					buff = new StringBuffer(buff.toString().trim());
				} else {
					buff.append(c);
				}
				break;
			case 1:
				if (c == '"') {
					value = buff.toString();
					buff = new StringBuffer();
					state = 0;
				} else {
					buff.append(c);
				}
				break;
			case 2:
				if (c == '\'') {
					value = buff.toString();
					buff = new StringBuffer();
					state = 0;
				} else {
					buff.append(c);
				}
				break;
			}
		}
		if (name.equalsIgnoreCase(pname)) {
			return value + buff.toString().trim();
		}
		return null;
	}
}