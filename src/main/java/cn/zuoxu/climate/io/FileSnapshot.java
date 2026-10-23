package cn.zuoxu.climate.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** A bounded immutable input for the transaction; never deletes the incoming file. */
public record FileSnapshot(Path path, String fileName, String sha256, long size) implements AutoCloseable {
    public static FileSnapshot capture(Path original,long maxBytes) throws Exception {
        BasicFileAttributes before=Files.readAttributes(original,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size()>maxBytes || original.getFileName().toString().length()>255) {
            throw new IllegalArgumentException("Only regular CSV files within the configured size limit are accepted");
        }
        Path copy=Files.createTempFile("climate-incremental-", ".csv");
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            long count=0;
            try (var input=new DigestInputStream(Files.newInputStream(original,LinkOption.NOFOLLOW_LINKS),digest);
                 var output=Files.newOutputStream(copy)) {
                byte[] buffer=new byte[8192];
                int read;
                while ((read=input.read(buffer))!=-1) {
                    count+=read;
                    if (count>maxBytes) throw new IllegalArgumentException("File exceeded the configured size limit while reading");
                    output.write(buffer,0,read);
                }
            }
            BasicFileAttributes after=Files.readAttributes(original,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if (count!=before.size() || after.size()!=before.size() || !after.lastModifiedTime().equals(before.lastModifiedTime())
                    || !Objects.equals(before.fileKey(),after.fileKey())) {
                throw new IOException("Incoming file changed; publish using an atomic rename");
            }
            return new FileSnapshot(copy,original.getFileName().toString(),HexFormat.of().formatHex(digest.digest()),count);
        } catch (Exception exception) {
            Files.deleteIfExists(copy);
            throw exception;
        }
    }

    @Override public void close() throws IOException { Files.deleteIfExists(path); }
}
