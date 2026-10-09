package vn.edu.ltm.common.transfer;

public record FileTransferHeader(FileOperation operation, String transferId, String deviceId,
                                 String credential, String relativePath, long fileSize,
                                 String sha256, String versionId) {
}
