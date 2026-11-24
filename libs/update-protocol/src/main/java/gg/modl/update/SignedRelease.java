package gg.modl.update;

import gg.modl.update.ReleaseVerificationException.Reason;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;

public final class SignedRelease {
    public static final String ENVELOPE_FILE = "modl-release.json";
    public static final int MAX_ENVELOPE_BYTES = 16 * 1024;
    public static final int ENVELOPE_FORMAT = 1;

    private static final String ALGORITHM = "Ed25519";
    private static final int SIGNATURE_BYTES = 64;
    private static final Pattern KEY_ID = Pattern.compile("[0-9a-f]{" + ReleaseKeys.KEY_ID_LENGTH + "}");

    private final byte[] envelope;
    private final String keyId;
    private final ReleaseManifest manifest;

    private SignedRelease(byte[] envelope, String keyId, ReleaseManifest manifest) {
        this.envelope = envelope;
        this.keyId = keyId;
        this.manifest = manifest;
    }

    public static SignedRelease verify(byte[] envelope, ReleaseKeys keys, JsonMaps json) throws ReleaseVerificationException {
        if (envelope == null || envelope.length == 0 || envelope.length > MAX_ENVELOPE_BYTES) {
            throw new ReleaseVerificationException(Reason.INVALID_MANIFEST,
                "The release envelope is empty or larger than " + MAX_ENVELOPE_BYTES + " bytes");
        }
        JsonFields fields = new JsonFields(readEnvelope(envelope, json), "The release envelope");
        try {
            int format = fields.integer("format");
            if (format > ENVELOPE_FORMAT) {
                throw new ReleaseVerificationException(Reason.UNSUPPORTED_FORMAT,
                    "The release envelope has format " + format + ", which this modl does not read; update modl manually");
            }
            if (format != ENVELOPE_FORMAT) {
                throw new ReleaseVerificationException(Reason.INVALID_MANIFEST, "The release envelope has an invalid format");
            }
            String keyId = fields.text("keyId");
            if (!KEY_ID.matcher(keyId).matches()) {
                throw new ReleaseVerificationException(Reason.INVALID_MANIFEST, "The release envelope has an invalid key id");
            }
            byte[] publicKey = keys.publicKey(keyId);
            if (publicKey == null) {
                throw new ReleaseVerificationException(Reason.INVALID_SIGNATURE,
                    "The release is signed with key " + keyId + ", which this modl does not know");
            }
            byte[] payload = base64(fields.text("payload"), "payload");
            byte[] signature = base64(fields.text("signature"), "signature");
            if (signature.length != SIGNATURE_BYTES) {
                throw new ReleaseVerificationException(Reason.INVALID_SIGNATURE, "The release signature has the wrong length");
            }
            requireSignature(publicKey, payload, signature);
            return new SignedRelease(envelope.clone(), keyId, ReleaseManifest.parse(payload, json));
        } catch (InvalidJsonFieldException e) {
            throw new ReleaseVerificationException(Reason.INVALID_MANIFEST, e.getMessage(), e);
        }
    }

    private static Map<String, Object> readEnvelope(byte[] envelope, JsonMaps json) throws ReleaseVerificationException {
        try {
            return json.read(envelope);
        } catch (IOException | RuntimeException e) {
            throw new ReleaseVerificationException(Reason.INVALID_MANIFEST, "The release envelope is not valid JSON", e);
        }
    }

    private static byte[] base64(String text, String field) throws ReleaseVerificationException {
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new ReleaseVerificationException(Reason.INVALID_MANIFEST, "The release envelope " + field + " is not base64", e);
        }
    }

    private static void requireSignature(byte[] publicKey, byte[] payload, byte[] signature) throws ReleaseVerificationException {
        boolean valid;
        try {
            PublicKey key = KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(publicKey));
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(payload);
            valid = verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            throw new ReleaseVerificationException(Reason.INVALID_SIGNATURE,
                "The release signature could not be checked (Ed25519 needs Java 15 or newer)", e);
        }
        if (!valid) {
            throw new ReleaseVerificationException(Reason.INVALID_SIGNATURE, "The release signature is not valid");
        }
    }

    public byte[] envelope() {
        return envelope.clone();
    }

    public String keyId() {
        return keyId;
    }

    public ReleaseManifest manifest() {
        return manifest;
    }

    public ReleaseVersion version() {
        return manifest.version();
    }
}
