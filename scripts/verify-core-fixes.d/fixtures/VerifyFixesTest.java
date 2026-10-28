import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class VerifyFixesTest {
    private static final int MAX_HEADER_LINE_BYTES = 16 * 1024;

    private static String readBoundedHeaderLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        while (true) {
            int next = input.read();
            if (next < 0) {
                return buffer.size() == 0 ? null : buffer.toString(StandardCharsets.UTF_8);
            }
            if (next == '\n') break;
            if (next != '\r') {
                if (buffer.size() >= MAX_HEADER_LINE_BYTES) {
                    throw new IOException("HTTP header line exceeds maximum allowed length");
                }
                buffer.write(next);
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static boolean constantTimeAuth(String headerValue, String expectedSecret) {
        if (headerValue == null || headerValue.isBlank()) return false;
        String candidate = headerValue.regionMatches(true, 0, "Bearer ", 0, 7)
            ? headerValue.substring(7).trim()
            : headerValue.trim();
        return MessageDigest.isEqual(
            candidate.getBytes(StandardCharsets.UTF_8),
            expectedSecret.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String safeStorageId(String id) {
        String trimmed = id.trim();
        if (trimmed.isEmpty() ||
            trimmed.equals(".") ||
            trimmed.equals("..") ||
            trimmed.contains("/") ||
            trimmed.contains("\\") ||
            trimmed.contains("..") ||
            trimmed.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Unsafe storage identifier");
        }
        return trimmed;
    }

    private static String redactDshText(String value) {
        return value
            .replaceAll("sk-[A-Za-z0-9_-]{8,}", "sk-••••")
            .replaceAll("(?i)(api[_-]?key|token|secret|password)(\\s*[=:]\\s*)([^\\s'\",}]+)", "$1$2••••")
            .replaceAll("(?i)(authorization:\\s*bearer\\s+)[^\\s'\",}]+", "$1••••");
    }

    public static void main(String[] args) throws Exception {
        // 1. Test constant-time auth check (BUG-007)
        assert constantTimeAuth("Bearer nextcraft-local-12345", "nextcraft-local-12345");
        assert !constantTimeAuth("Bearer wrong-token", "nextcraft-local-12345");
        assert !constantTimeAuth("", "nextcraft-local-12345");

        // 2. Test bounded header line reader rejects > 16KB line without OOM (BUG-008)
        byte[] validLine = "POST /v1/messages HTTP/1.1\r\n".getBytes(StandardCharsets.UTF_8);
        assert "POST /v1/messages HTTP/1.1".equals(readBoundedHeaderLine(new ByteArrayInputStream(validLine)));

        byte[] oversizedLine = new byte[MAX_HEADER_LINE_BYTES + 100];
        java.util.Arrays.fill(oversizedLine, (byte) 'A');
        boolean threwOversized = false;
        try {
            readBoundedHeaderLine(new ByteArrayInputStream(oversizedLine));
        } catch (IOException expected) {
            threwOversized = true;
        }
        assert threwOversized : "Expected IOException on oversized HTTP header line";

        // 3. Test safeStorageId rejects path traversal (BUG-017)
        assert "proj-123".equals(safeStorageId("proj-123"));
        for (String bad : new String[]{"../secrets", "..", "a/b", "a\\b", "foo..bar", "id\0null", "   "}) {
            boolean rejected = false;
            try {
                safeStorageId(bad);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            assert rejected : "Failed to reject unsafe storage ID: " + bad;
        }

        // 4. Test DSH secret redaction (BUG-016)
        String raw = "curl -H 'Authorization: Bearer eyJhbGciOiJIUzI1NiJ9' -d 'api_key=my_secret_123' https://api.example.com?key=sk-proj-abcdef1234567890";
        String redacted = redactDshText(raw);
        assert !redacted.contains("eyJhbGciOiJIUzI1NiJ9");
        assert !redacted.contains("my_secret_123");
        assert !redacted.contains("sk-proj-abcdef1234567890");
    }
}
