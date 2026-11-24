package local.headunit.diplay;

import android.util.Base64;
import com.shilapi.xcertplay.airplay.AirPlayCrypto;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.encodings.OAEPEncoding;
import org.bouncycastle.crypto.engines.RSAEngine;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.json.JSONObject;

/** Seals consented audio before it reaches the existing HTTP report transport. */
final class MicrophoneReportAttachment {
    static final String FIELD = "microphoneSample";
    static final int WAV_HEADER_BYTES = 44;
    private static final RSAKeyParameters RECIPIENT = new RSAKeyParameters(false,
        new BigInteger(
            "c233dae0e0ce912a056fd19bc4a50f1cf58e47184289baeca615e87a65e4874a"
            + "0b63ade63a6ebe504ac24e1d9dbe898b006b58ada511dc5a59347f6a790443d5"
            + "bc48c74ef8def61ae6ceef4fe51c0c672412e45ab4b0ec6715b4f15dcca0e68e"
            + "e46dd53b570721b7066e861e4a04ec4c92d372ff855e591768998512a86c4272"
            + "94456dcf349a5bc808d9d4608c544de12290b845daf4966e8d776c9e2f6fd17c"
            + "23d97eea84876f46213a38c8e1ccf1bd5a2bd44286732cd1b30c02dc4fc36b26"
            + "62416b6f87e8e46e4465dd8a1f27548ede15d837554fac8ce3cdd5181e60b362"
            + "b3f8b0a55c3edb4763b1d83f60406a4ae0760b3ab62ef8065bb1ebc8da108a65",
            16),
        BigInteger.valueOf(65537));

    private MicrophoneReportAttachment() { }

    static JSONObject seal(String id, int sampleRate, int pcmBytes, byte[] before, byte[] after) throws Exception {
        if (id == null || !id.matches("[a-f0-9]{32}") || sampleRate < 8000 || sampleRate > 48000
                || pcmBytes < 2 || pcmBytes > sampleRate * 20 || pcmBytes % 2 != 0
                || before.length != WAV_HEADER_BYTES + pcmBytes || after.length != before.length) {
            throw new IllegalArgumentException("Invalid diagnostic microphone clip");
        }
        byte[] entropy = new byte[76];
        byte[] key = null;
        byte[] audio = null;
        try {
            DataInputStream randomSource = new DataInputStream(new FileInputStream("/dev/urandom"));
            try { randomSource.readFully(entropy); } finally { randomSource.close(); }
            key = Arrays.copyOfRange(entropy, 0, 32);
            byte[] nonce = Arrays.copyOfRange(entropy, 32, 44);
            SecureRandom random = new SecureRandom(Arrays.copyOfRange(entropy, 44, entropy.length));
            OAEPEncoding envelope = new OAEPEncoding(new RSAEngine(), new SHA256Digest());
            envelope.init(true, new ParametersWithRandom(RECIPIENT, random));
            byte[] wrappedKey = envelope.processBlock(key, 0, key.length);
            audio = Arrays.copyOf(before, before.length + after.length);
            System.arraycopy(after, 0, audio, before.length, after.length);
            byte[] aad = ("diplay-microphone-sample-v1:" + id + ":" + sampleRate + ":" + pcmBytes).getBytes("US-ASCII");
            byte[] ciphertext = AirPlayCrypto.INSTANCE.chachaSeal(key, nonce, audio, aad);
            return new JSONObject().put("id", id).put("sampleRate", sampleRate).put("pcmBytes", pcmBytes)
                .put("keyBase64", Base64.encodeToString(wrappedKey, Base64.NO_WRAP))
                .put("nonceBase64", Base64.encodeToString(nonce, Base64.NO_WRAP))
                .put("ciphertextBase64", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
        } finally {
            Arrays.fill(entropy, (byte) 0);
            if (key != null) Arrays.fill(key, (byte) 0);
            if (audio != null) Arrays.fill(audio, (byte) 0);
        }
    }

    static int audioBytes(JSONObject sample) throws Exception {
        return 2 * (WAV_HEADER_BYTES + sample.getInt("pcmBytes"));
    }
}
