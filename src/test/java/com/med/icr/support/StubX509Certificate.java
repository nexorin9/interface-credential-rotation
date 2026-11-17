package com.med.icr.support;

import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * 证书夹具：只实现 TlsProbe.evaluate 会读到的语义（有效期 / 主机名 / SAN），
 * 不生成私钥、不做签名，避免测试依赖外部 CA 或 JDK 内部类。
 */
public final class StubX509Certificate extends X509Certificate {
    private final Date notBefore;
    private final Date notAfter;
    private final String subjectCn;
    private final Collection<List<?>> sans;

    public StubX509Certificate(Date notBefore, Date notAfter, String subjectCn, Collection<List<?>> sans) {
        this.notBefore = notBefore;
        this.notAfter = notAfter;
        this.subjectCn = subjectCn;
        this.sans = sans;
    }

    public static StubX509Certificate validFor(String host, java.time.Instant now) {
        return new StubX509Certificate(Date.from(now.minusSeconds(86400L)),
                Date.from(now.plusSeconds(30L * 86400)), host, null);
    }

    public static StubX509Certificate withSans(java.time.Instant now, String dns) {
        return new StubX509Certificate(Date.from(now.minusSeconds(86400L)),
                Date.from(now.plusSeconds(30L * 86400)), "unrelated.invalid",
                List.of(List.of(2, dns)));
    }

    @Override public X500Principal getSubjectX500Principal() { return new X500Principal("CN=" + subjectCn); }
    @Override public X500Principal getIssuerX500Principal() { return new X500Principal("CN=StubIssuer"); }
    @Override public Date getNotBefore() { return notBefore; }
    @Override public Date getNotAfter() { return notAfter; }
    @Override public Collection<List<?>> getSubjectAlternativeNames() { return sans; }
    @Override public Collection<List<?>> getIssuerAlternativeNames() { return null; }

    @Override public void checkValidity() {}
    @Override public void checkValidity(Date date) {}
    @Override public int getVersion() { return 3; }
    @Override public BigInteger getSerialNumber() { return BigInteger.ONE; }
    @Override public Principal getIssuerDN() { return new X500Principal("CN=StubIssuer"); }
    @Override public Principal getSubjectDN() { return new X500Principal("CN=" + subjectCn); }
    @Override public byte[] getTBSCertificate() throws CertificateEncodingException { return new byte[0]; }
    @Override public byte[] getSignature() { return new byte[0]; }
    @Override public String getSigAlgName() { return "SHA256withRSA"; }
    @Override public String getSigAlgOID() { return "1.2.840.113549.1.1.11"; }
    @Override public byte[] getSigAlgParams() { return null; }
    @Override public boolean[] getIssuerUniqueID() { return null; }
    @Override public boolean[] getSubjectUniqueID() { return null; }
    @Override public boolean[] getKeyUsage() { return null; }
    @Override public int getBasicConstraints() { return -1; }
    @Override public byte[] getEncoded() throws CertificateEncodingException { return new byte[0]; }
    @Override public void verify(PublicKey key) {}
    @Override public void verify(PublicKey key, String sigProvider) {}
    @Override public String toString() { return "StubX509Certificate(" + subjectCn + ")"; }
    @Override public PublicKey getPublicKey() { return null; }
    @Override public Set<String> getCriticalExtensionOIDs() { return Set.of(); }
    @Override public Set<String> getNonCriticalExtensionOIDs() { return Set.of(); }
    @Override public byte[] getExtensionValue(String oid) { return null; }
    @Override public boolean hasUnsupportedCriticalExtension() { return false; }
}
