package io.github.maparham.autotether;

import android.content.Context;
import android.os.Build;


import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/** adb connection manager backed by a persisted RSA key + self-signed certificate. */
public class AdbManager extends AbsAdbConnectionManager {
    private static AdbManager INSTANCE;

    public static synchronized AdbManager getInstance(Context ctx) throws Exception {
        if (INSTANCE == null) INSTANCE = new AdbManager(ctx.getApplicationContext());
        return INSTANCE;
    }

    private final PrivateKey privateKey;
    private final Certificate certificate;

    private AdbManager(Context ctx) throws Exception {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        Security.addProvider(new BouncyCastleProvider());
        setApi(Build.VERSION.SDK_INT);

        File keyFile = new File(ctx.getFilesDir(), "adb_key.pk8");
        File certFile = new File(ctx.getFilesDir(), "adb_cert.der");
        if (keyFile.exists() && certFile.exists()) {
            privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(read(keyFile)));
            certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(new FileInputStream(certFile));
        } else {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            privateKey = kp.getPrivate();
            certificate = selfSignedCert(kp);
            write(keyFile, privateKey.getEncoded());
            write(certFile, certificate.getEncoded());
        }
    }

    private static X509Certificate selfSignedCert(KeyPair kp) throws Exception {
        X500Name subject = new X500Name("CN=AutoTether");
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 24L * 60 * 60 * 1000);
        Date notAfter = new Date(now + 10L * 365 * 24 * 60 * 60 * 1000);
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(kp.getPrivate());
        X509CertificateHolder holder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.ONE, notBefore, notAfter, subject, kp.getPublic()).build(signer);
        return new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME).getCertificate(holder);
    }

    @Override protected PrivateKey getPrivateKey() { return privateKey; }
    @Override protected Certificate getCertificate() { return certificate; }
    @Override protected String getDeviceName() { return "AutoTether"; }

    private static byte[] read(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
        }
        return b;
    }

    private static void write(File f, byte[] b) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(b); }
    }
}
