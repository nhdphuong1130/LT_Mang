package vn.edu.ltm.common.transfer;

public record TransferResult(Status status, String message) {
    public enum Status { READY, STORED, UNCHANGED, REJECTED, ERROR }
}
