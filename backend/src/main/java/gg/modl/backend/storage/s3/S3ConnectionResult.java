package gg.modl.backend.storage.s3;

public record S3ConnectionResult(boolean ok, String code, String message) {
    public static final String OK = "OK";
    public static final String INCOMPLETE = "S3_INCOMPLETE";
    public static final String INVALID_ADDRESS = "S3_INVALID_ADDRESS";
    public static final String ACCESS_DENIED = "S3_ACCESS_DENIED";
    public static final String BUCKET_NOT_FOUND = "S3_BUCKET_NOT_FOUND";
    public static final String WRONG_REGION = "S3_WRONG_REGION";
    public static final String UNREACHABLE = "S3_UNREACHABLE";
    public static final String NOT_OWNED = "S3_NOT_OWNED";
    public static final String FAILED = "S3_FAILED";

    static S3ConnectionResult passed() {
        return new S3ConnectionResult(true, OK, "Connected: modl could write, read and delete a test file in the bucket");
    }

    static S3ConnectionResult failed(String code, String message) {
        return new S3ConnectionResult(false, code, message);
    }
}
