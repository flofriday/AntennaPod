package de.danoeh.antennapod.parser.media.vorbis;

import androidx.annotation.NonNull;
import org.apache.commons.io.EndianUtils;
import org.apache.commons.io.IOUtils;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Locale;

public abstract class VorbisCommentReader {
    private static final String TAG = "VorbisCommentReader";
    private static final int FIRST_OGG_PAGE_LENGTH = 58;
    private static final int FIRST_OPUS_PAGE_LENGTH = 47;
    private static final int SECOND_PAGE_MAX_LENGTH = 64 * 1024 * 1024;
    private static final int PACKET_TYPE_IDENTIFICATION = 1;
    private static final int PACKET_TYPE_COMMENT = 3;

    private final VorbisInputStream input;

    VorbisCommentReader(InputStream input) {
        this.input = new VorbisInputStream(input);
    }

    public void readInputStream() throws VorbisCommentReaderException {
        try {
            findCommentHeader();
            VorbisCommentHeader commentHeader = readCommentHeader();
            Log.d(TAG, commentHeader.toString());
            for (int i = 0; i < commentHeader.getUserCommentLength(); i++) {
                readUserComment();
            }
        } catch (IOException e) {
            Log.d(TAG, "Vorbis parser: " + e.getMessage());
        }
    }

    private void readUserComment() throws VorbisCommentReaderException {
        try {
            long vectorLength = EndianUtils.readSwappedUnsignedInteger(input);
            if (vectorLength > 20 * 1024 * 1024) {
                String keyPart = readUtf8String(10);
                throw new VorbisCommentReaderException("User comment unrealistically long. "
                        + "key=" + keyPart + ", length=" + vectorLength);
            }
            String key = readContentVectorKey(vectorLength).toLowerCase(Locale.US);
            boolean shouldReadValue = handles(key);
            Log.d(TAG, "key=" + key + ", length=" + vectorLength + ", handles=" + shouldReadValue);
            if (shouldReadValue) {
                String value = readUtf8String(vectorLength - key.length() - 1);
                onContentVectorValue(key, value);
            } else {
                IOUtils.skipFully(input, vectorLength - key.length() - 1);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String readUtf8String(long length) throws IOException {
        byte[] buffer = new byte[(int) length];
        IOUtils.readFully(input, buffer);
        Charset charset = Charset.forName("UTF-8");
        return charset.newDecoder().decode(ByteBuffer.wrap(buffer)).toString();
    }

    private void findCommentHeader() throws IOException {
        byte[] buffer = new byte[64]; // Enough space for some bytes. Used circularly.
        final byte[] oggCommentHeader = new byte[]{ PACKET_TYPE_COMMENT, 'v', 'o', 'r', 'b', 'i', 's' };
        for (int bytesRead = 0; bytesRead < SECOND_PAGE_MAX_LENGTH; bytesRead++) {
            buffer[bytesRead % buffer.length] = (byte) input.read();
            if (bufferMatches(buffer, oggCommentHeader, bytesRead)) {
                return;
            } else if (bufferMatches(buffer, "OpusTags".getBytes(), bytesRead)) {
                return;
            }
        }
        throw new IOException("No comment header found");
    }

    /**
     * Reads backwards in haystack, starting at position. Checks if the bytes match needle.
     * Uses haystack circularly, so when reading at (-1), it reads at (length - 1).
     */
    boolean bufferMatches(byte[] haystack, byte[] needle, int position) {
        for (int i = 0; i < needle.length; i++) {
            int posInHaystack = position - i;
            while (posInHaystack < 0) {
                posInHaystack += haystack.length;
            }
            posInHaystack = posInHaystack % haystack.length;
            if (haystack[posInHaystack] != needle[needle.length - 1 - i]) {
                return false;
            }
        }
        return true;
    }

    @NonNull
    private VorbisCommentHeader readCommentHeader() throws IOException, VorbisCommentReaderException {
        try {
            long vendorLength = EndianUtils.readSwappedUnsignedInteger(input);
            String vendorName = readUtf8String(vendorLength);
            long userCommentLength = EndianUtils.readSwappedUnsignedInteger(input);
            return new VorbisCommentHeader(vendorName, userCommentLength);
        } catch (UnsupportedEncodingException e) {
            throw new VorbisCommentReaderException(e);
        }
    }

    private String readContentVectorKey(long vectorLength) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < vectorLength; i++) {
            char c = (char) input.read();
            if (c == '=') {
                return builder.toString();
            } else {
                builder.append(c);
            }
        }
        return null; // no key found
    }

    /**
     * Is called every time the Reader finds a content vector. The handler
     * should return true if it wants to handle the content vector.
     */
    protected abstract boolean handles(String key);

    /**
     * Is called if onContentVectorKey returned true for the key.
     */
    protected abstract void onContentVectorValue(String key, String value) throws VorbisCommentReaderException;
}
