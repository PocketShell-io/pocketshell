package com.pocketshell.app;

import android.content.Context;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.Factory;
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider;
import net.schmizz.sshj.userauth.keyprovider.KeyFormat;
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil;
import net.schmizz.sshj.userauth.password.PasswordUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** Test-only bridge from a generated opaque handle to Docker's public-key fixture. */
public final class GeneratedSshKeyTestSupport {
    private GeneratedSshKeyTestSupport() {}

    public static void writeDockerAuthorizationEvidence(
        Context context,
        String handleId,
        String expectedFingerprint,
        File publicKeyOutput,
        File fingerprintOutput
    ) throws Exception {
        byte[] privateBytes = CredentialHandleVault.forContext(context).resolvePrivateKey(handleId);
        byte[] publicBlob = null;
        try (SSHClient client = new SSHClient()) {
            String pem = new String(privateBytes, StandardCharsets.UTF_8);
            KeyFormat format = KeyProviderUtil.detectKeyFileFormat(pem, false);
            FileKeyProvider provider = Factory.Named.Util.create(
                client.getTransport().getConfig().getFileKeyProviderFactories(), format.toString());
            if (provider == null) throw new IllegalStateException("generated key format is not supported by sshj");
            provider.init(pem, null, PasswordUtils.createOneOff(new char[0]));
            Buffer<?> publicKey = new Buffer<>();
            publicKey.putPublicKey(provider.getPublic());
            publicBlob = publicKey.getCompactData();
            String fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(publicBlob));
            if (!expectedFingerprint.equals(fingerprint)) {
                throw new IllegalStateException("generated public key does not match the selected key fingerprint");
            }
            String authorization = provider.getType() + " " + Base64.getEncoder().encodeToString(publicBlob)
                + " pocketshell-generated-key\n";
            writeSynced(publicKeyOutput, authorization.getBytes(StandardCharsets.US_ASCII));
            writeSynced(fingerprintOutput, (fingerprint + "\n").getBytes(StandardCharsets.US_ASCII));
        } finally {
            java.util.Arrays.fill(privateBytes, (byte) 0);
            if (publicBlob != null) java.util.Arrays.fill(publicBlob, (byte) 0);
        }
    }

    private static void writeSynced(File output, byte[] bytes) throws Exception {
        File parent = output.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IllegalStateException("generated-key evidence directory is unavailable");
        }
        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write(bytes);
            stream.getFD().sync();
        }
    }
}
