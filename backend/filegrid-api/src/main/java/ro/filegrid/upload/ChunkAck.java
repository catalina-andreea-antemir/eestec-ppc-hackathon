package ro.filegrid.upload;

/** Schema {@code ChunkAck} din contract. */
public record ChunkAck(int n, long size, String sha256, int nextChunk, boolean duplicate) {
}
