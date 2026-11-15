package ro.filegrid.upload;

/** Contract schema {@code ChunkAck}. */
public record ChunkAck(int n, long size, String sha256, int nextChunk, boolean duplicate) {
}
