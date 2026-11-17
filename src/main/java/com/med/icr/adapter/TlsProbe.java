package com.med.icr.adapter;

import com.med.icr.util.Times;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 真实 TLS 探测：握手 + 读取 X509Certificate.getNotAfter()；任意异常统一收敛为「未通过」。 */
public final class TlsProbe {
    public static final String CERT_EXPIRED = "CERT_EXPIRED";
    public static final String HOSTNAME_MISMATCH = "HOSTNAME_MISMATCH";
    public static final String TRUST_CHAIN_UNTRUSTED = "TRUST_CHAIN_UNTRUSTED";
    public static final String CONNECTION_REFUSED = "CONNECTION_REFUSED";
    public static final String TIMEOUT = "PROBE_TIMEOUT";
    public static final String CERT_NOT_YET_VALID = "CERT_NOT_YET_VALID";
    public static final String UNKNOWN = "TLS_UNKNOWN";
    private static final Pattern CN = Pattern.compile("(?:^|,)CN=([^,]+)");

    private final int timeoutMillis;

    public TlsProbe() { this(5000); }
    public TlsProbe(int timeoutMillis) { this.timeoutMillis = timeoutMillis; }

    /** 探测只读不改：不推进任何凭据状态。 */
    public ProbeResult probe(String host, int port) {
        String endpoint = host + ":" + port;
        try (SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            socket.startHandshake();
            X509Certificate cert = (X509Certificate) socket.getSession().getPeerCertificates()[0];
            return evaluate(cert, host, Instant.now(), endpoint);
        } catch (Exception e) {
            return ProbeResult.failed(classify(e), codeFor(classify(e)), Instant.now(), endpoint,
                    e.getClass().getSimpleName());
        }
    }

    /** 证书语义判定：过期 / 未生效 / 主机名不符 / 通过（含剩余天数），可注入固定 Clock 时间。 */
    public static ProbeResult evaluate(X509Certificate cert, String host, Instant now, String endpoint) {
        try {
            Instant notAfter = cert.getNotAfter().toInstant();
            Instant notBefore = cert.getNotBefore().toInstant();
            if (notAfter.isBefore(now)) {
                return ProbeResult.failed(CERT_EXPIRED, CERT_EXPIRED, now, endpoint, "证书已过期");
            }
            if (notBefore.isAfter(now)) {
                return ProbeResult.failed(CERT_NOT_YET_VALID, CERT_NOT_YET_VALID, now, endpoint, "证书尚未生效");
            }
            if (!hostnameMatches(cert, host)) {
                return ProbeResult.failed(HOSTNAME_MISMATCH, HOSTNAME_MISMATCH, now, endpoint, "主机名不符");
            }
            return ProbeResult.passed(Times.daysUntil(now, notAfter), now, endpoint);
        } catch (Exception e) {
            return ProbeResult.failed(UNKNOWN, UNKNOWN, now, endpoint, e.getClass().getSimpleName());
        }
    }

    private static boolean hostnameMatches(X509Certificate cert, String host) {
        if (host == null || host.isBlank()) return false;
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> entry : sans) {
                    if (entry.size() >= 2 && entry.get(0) instanceof Integer type && type == 2) {
                        String dns = String.valueOf(entry.get(1));
                        if (dns.equalsIgnoreCase(host)) return true;
                        if (dns.startsWith("*.") && host.toLowerCase().endsWith(dns.substring(1).toLowerCase())) return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // 无 SAN 时回退 CN
        }
        Matcher m = CN.matcher(cert.getSubjectX500Principal().getName());
        return m.find() && m.group(1).equalsIgnoreCase(host);
    }

    /** 握手异常分类：证书过期 / 主机名不符 / 信任链不受信 / 连接被拒 / 超时。 */
    public static String classify(Throwable t) {
        if (t == null) return UNKNOWN;
        String msg = String.valueOf(t.getMessage()).toLowerCase();
        if (t instanceof CertificateExpiredException || msg.contains("expired") || msg.contains("notafter")) {
            return CERT_EXPIRED;
        }
        if (msg.contains("hostname") || msg.contains("no subject alternative") || msg.contains("subject alternative")) {
            return HOSTNAME_MISMATCH;
        }
        if (t instanceof CertPathValidatorException || t.getCause() instanceof CertPathValidatorException
                || msg.contains("unable to find valid certification path") || msg.contains("self signed")) {
            return TRUST_CHAIN_UNTRUSTED;
        }
        if (t instanceof ConnectException || msg.contains("connection refused")) return CONNECTION_REFUSED;
        if (t instanceof SocketTimeoutException || msg.contains("timed out") || msg.contains("timeout")) return TIMEOUT;
        if (t instanceof SSLHandshakeException) return TRUST_CHAIN_UNTRUSTED;
        return UNKNOWN;
    }

    public static String codeFor(String failureClass) { return failureClass; }
}
