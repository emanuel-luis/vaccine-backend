package io.github.emanuelluis.vaccine.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.util.HexFormat;

/** SHA-256 of a canonical serialization of a validated dose: fixed field order, instant in UTC. */
final class RequestHash {

    private RequestHash() {}

    static String sha256(DoseRequest r) {
        String canonical = String.join(
                "\n",
                r.personId().toString(),
                r.birthDate().toString(),
                r.sex(),
                r.cnesCode(),
                r.vaccineCode(),
                r.doseLabel(),
                r.lotNumber() == null ? "" : r.lotNumber(),
                r.administeredAt()
                        .withOffsetSameInstant(ZoneOffset.UTC)
                        .toInstant()
                        .toString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
