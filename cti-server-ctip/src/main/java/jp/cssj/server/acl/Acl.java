package jp.cssj.server.acl;

import java.net.InetAddress;
import java.util.ServiceLoader;

/**
 * アクセス制御リストです。
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: Acl.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public interface Acl {
	public boolean match(Object key);

	public boolean checkAccess(InetAddress remoteAddress);

	public static Acl find(Object key) {
		for (Acl acl : ServiceLoader.load(Acl.class)) {
			if (acl.match(key)) {
				return acl;
			}
		}
		return null;
	}
}