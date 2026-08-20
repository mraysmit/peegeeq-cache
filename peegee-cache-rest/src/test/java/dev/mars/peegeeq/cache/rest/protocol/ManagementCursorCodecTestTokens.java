package dev.mars.peegeeq.cache.rest.protocol;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

final class ManagementCursorCodecTestTokens {

    private ManagementCursorCodecTestTokens() {
    }

    static String withVersion(String cursor, byte[] key, int version) throws Exception {
        Base64.Decoder decoder = Base64.getUrlDecoder();
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String[] parts = cursor.split("\\.", -1);
        byte[] payload = decoder.decode(parts[0]);
        payload[4] = (byte) version;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return encoder.encodeToString(payload) + "." + encoder.encodeToString(mac.doFinal(payload));
    }
}
