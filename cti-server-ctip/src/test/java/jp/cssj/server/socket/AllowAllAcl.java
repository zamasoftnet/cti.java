package jp.cssj.server.socket;

import java.net.InetAddress;

import jp.cssj.server.acl.Acl;

public class AllowAllAcl implements Acl {
	public boolean match(Object key) {
		return true;
	}

	public boolean checkAccess(InetAddress remoteAddress) {
		return true;
	}
}
